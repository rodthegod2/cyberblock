package dev.cyberblock.client.cyber;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.cyber.mixin.CyberKeyMappingAccessor;
import dev.cyberblock.cyber.CyberWire;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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

	private CyberKeys() {
	}

	static void register() {
		hack = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.hack", InputConstants.KEY_G, CATEGORY));
		skills = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.skills", InputConstants.KEY_K, CATEGORY));
	}

	private static int vk(final KeyMapping mapping, final int fallback) {
		InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mapping);
		return VirtualKeys.orDefault(key.getType() == InputConstants.Type.KEYBOARD, key.getValue(), fallback);
	}

	/** {"t":"binds"} with the current bindings (host hello, or a rebinding). Client thread. */
	static void sendBinds() {
		sentHack = vk(hack, VirtualKeys.VK_G);
		sentSkills = vk(skills, VirtualKeys.VK_K);
		Cyberblock.events.accept(CyberWire.binds(sentHack, sentSkills));
	}

	/** The key whose binding Cyberpunk cannot forward, or null when both are letters, digits or F-keys. */
	static @Nullable KeyMapping unforwardable() {
		for (KeyMapping mapping : new KeyMapping[]{hack, skills}) {
			InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mapping);
			if (VirtualKeys.of(key.getType() == InputConstants.Type.KEYBOARD, key.getValue()) < 0) {
				return mapping;
			}
		}

		return null;
	}

	/** A note for a binding Cyberpunk cannot forward: it works there as the default key instead. */
	static @Nullable Component note() {
		KeyMapping bad = unforwardable();
		if (bad == null) {
			return null;
		}

		String fallback = bad == hack ? "G" : "K";
		return Component.translatable("cyberblock.binds.note", bad.getTranslatedKeyMessage(), fallback);
	}

	/** Every client tick: a changed binding is sent to Cyberpunk at once (and noted when it cannot be forwarded). */
	static void tick(final Minecraft minecraft) {
		InputConstants.Key hackKey = KeyMappingHelper.getBoundKeyOf(hack);
		InputConstants.Key skillsKey = KeyMappingHelper.getBoundKeyOf(skills);
		if (lastHackKey == null) {
			lastHackKey = hackKey;
			lastSkillsKey = skillsKey;
			return;
		}

		if (!hackKey.equals(lastHackKey) || !skillsKey.equals(lastSkillsKey)) {
			lastHackKey = hackKey;
			lastSkillsKey = skillsKey;
			if (vk(hack, VirtualKeys.VK_G) != sentHack || vk(skills, VirtualKeys.VK_K) != sentSkills) {
				sendBinds();
			}

			Component note = note();
			if (note != null && minecraft.player != null) {
				minecraft.player.sendSystemMessage(note);
			}
		}
	}

	/**
	 * {"t":"key","k":"hack"|"skills","down":bool} from the host, on the client thread: pressed and released as vanilla's
	 * keyboard handler would (a press counts a click). With a screen open the game never sees a key, as in ClientInput.
	 */
	static void onHostKey(final Minecraft minecraft, final KeyMapping mapping, final boolean down) {
		if (minecraft.gui.screen() != null) {
			return;
		}

		if (down && !mapping.isDown()) {
			CyberKeyMappingAccessor access = (CyberKeyMappingAccessor) mapping;
			access.cyberblock$setCyberClickCount(access.cyberblock$cyberClickCount() + 1);
		}

		mapping.setDown(down);
	}
}
