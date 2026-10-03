package dev.cyberblock.client.cyber;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.cyber.mixin.CyberKeyMappingAccessor;
import dev.cyberblock.cyber.CyberWire;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The Cyberblock key mappings (Hack, default G; Skill tree, default K), rebindable in Minecraft's Controls. While Cyberpunk
 * has the focus it forwards them as {"t":"key","k":"hack"|"skills"} once it knows them from `binds`.
 */
public final class CyberKeys {
	static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Cyberblock.ID, "cyberblock"));
	public static KeyMapping hack;
	public static KeyMapping skills;
	/** The binds last sent (virtual-key codes), so a rebinding is sent once. */
	private static int sentHack = -1;
	private static int sentSkills = -1;
	private static InputConstants.@Nullable Key lastHackKey;
	private static InputConstants.@Nullable Key lastSkillsKey;
	/** A host said hello: the note about the binds (if any) goes to chat once a player is there to read it. */
	private static boolean notePending;
	/** Keys the host holds down: released when the host goes away, so a lost key-up never leaves the hack key held. */
	private static final Set<KeyMapping> hostHeld = new HashSet<>();

	private CyberKeys() {
	}

	static void register() {
		hack = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.hack", InputConstants.KEY_G, CATEGORY));
		skills = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.skills", InputConstants.KEY_K, CATEGORY));
	}

	/** The virtual key Cyberpunk forwards for a mapping's binding, -1 when it cannot (VirtualKeys.of). */
	private static int vk(final KeyMapping mapping) {
		InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mapping);
		return VirtualKeys.of(key.getType() == InputConstants.Type.KEYBOARD, key.getValue());
	}

	/** The {hack, skills} pair Cyberpunk uses: a refused key stands for its default, and a clash makes both the defaults. */
	private static int[] binds() {
		return VirtualKeys.binds(vk(hack), vk(skills));
	}

	/** {"t":"binds"} with the current bindings (host hello, or a rebinding). Client thread. */
	static void sendBinds() {
		int[] keys = binds();
		sentHack = keys[0];
		sentSkills = keys[1];
		Cyberblock.events.accept(CyberWire.binds(sentHack, sentSkills));
	}

	/** A host said hello (client thread): it gets the binds, and the player the note about them as soon as he is in a world. */
	static void hostHello() {
		sendBinds();
		notePending = true;
	}

	/**
	 * What the player should know about the binds Cyberpunk uses, or null when it uses both as bound: a key it cannot forward
	 * works there as its default (G, K); two keys that would be the same there both work as the defaults.
	 */
	static @Nullable Component note() {
		int h = vk(hack);
		int s = vk(skills);
		int[] used = VirtualKeys.binds(h, s);
		if (VirtualKeys.clash(h, s)) {
			return Component.translatable("cyberblock.binds.same", hack.getTranslatedKeyMessage(), skills.getTranslatedKeyMessage(),
				VirtualKeys.name(used[0]), VirtualKeys.name(used[1]));
		}

		MutableComponent note = null;
		for (int i = 0; i < 2; i++) {
			KeyMapping mapping = i == 0 ? hack : skills;
			if ((i == 0 ? h : s) < 0) {
				Component line = Component.translatable("cyberblock.binds.note", mapping.getTranslatedKeyMessage(),
					Component.translatable(mapping.getName()), VirtualKeys.name(used[i]));
				note = note == null ? line.copy() : note.append(" ").append(line);
			}
		}

		return note;
	}

	/**
	 * Minecraft 26.3's Quick Actions key (Options.keyQuickActions) is G by default too, so both fired on G and the Controls
	 * screen showed a conflict. On the first tick (the options are loaded by then) it is unbound when it shares the hack key,
	 * and the options are saved; the player may bind it to another key.
	 */
	private static void freeQuickActions(final Minecraft minecraft) {
		KeyMapping quickActions = minecraft.options.keyQuickActions;
		if (!quickActions.isUnbound() && quickActions.same(hack)) {
			quickActions.setKey(InputConstants.UNKNOWN);
			KeyMapping.resetMapping();
			minecraft.options.save();
			Cyberblock.LOG.info("unbound Minecraft's Quick Actions key ({}): the Cyberblock hack key uses it", hack.saveString());
		}
	}

	private static void showNote(final Minecraft minecraft) {
		Component note = note();
		if (note != null && minecraft.player != null) {
			minecraft.player.sendSystemMessage(note);
		}
	}

	/**
	 * Every client tick: a skill key press opens the skill tree; keys the host held are let go once it stopped driving; a
	 * changed binding is sent to Cyberpunk at once (and noted when it cannot be forwarded). The first tick frees G from
	 * Minecraft's Quick Actions.
	 */
	static void tick(final Minecraft minecraft) {
		while (skills.consumeClick()) {
			if (minecraft.player != null && minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new SkillTreeScreen());
			}
		}

		if (!Cyberblock.active && !hostHeld.isEmpty()) {
			hostHeld.forEach(mapping -> mapping.setDown(false));
			hostHeld.clear();
		}

		if (notePending && minecraft.player != null) {
			notePending = false;
			showNote(minecraft);
		}

		InputConstants.Key hackKey = KeyMappingHelper.getBoundKeyOf(hack);
		InputConstants.Key skillsKey = KeyMappingHelper.getBoundKeyOf(skills);
		if (lastHackKey == null) {
			freeQuickActions(minecraft);
			lastHackKey = hackKey;
			lastSkillsKey = skillsKey;
			return;
		}

		if (!hackKey.equals(lastHackKey) || !skillsKey.equals(lastSkillsKey)) {
			lastHackKey = hackKey;
			lastSkillsKey = skillsKey;
			int[] keys = binds();
			if (keys[0] != sentHack || keys[1] != sentSkills) {
				sendBinds();
			}

			showNote(minecraft);
		}
	}

	/**
	 * {"t":"key","k":"hack"|"skills","down":bool} from the host, on the client thread: pressed and released as vanilla's
	 * keyboard handler would (a press counts a click). With a screen open the game never sees a key, as in ClientInput;
	 * the skill key still closes the skill tree.
	 */
	static void onHostKey(final Minecraft minecraft, final KeyMapping mapping, final boolean down) {
		if (minecraft.gui.screen() != null) {
			// the skill key closes the skill tree, as the inventory key closes the inventory
			if (down && mapping == skills && minecraft.gui.screen() instanceof SkillTreeScreen screen) {
				screen.onClose();
			}

			return;
		}

		if (down && !mapping.isDown()) {
			CyberKeyMappingAccessor access = (CyberKeyMappingAccessor) mapping;
			access.cyberblock$setCyberClickCount(access.cyberblock$cyberClickCount() + 1);
		}

		mapping.setDown(down);
		if (down) {
			hostHeld.add(mapping);
		} else {
			hostHeld.remove(mapping);
		}
	}
}
