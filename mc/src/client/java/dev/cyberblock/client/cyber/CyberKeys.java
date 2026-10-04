package dev.cyberblock.client.cyber;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.client.HostLink;
import dev.cyberblock.client.HostState;
import dev.cyberblock.client.cyber.mixin.CyberKeyMappingAccessor;
import dev.cyberblock.cyber.CyberActs;
import dev.cyberblock.cyber.CyberWire;
import dev.cyberblock.cyber.CyberwareRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Cyberblock key mappings (plan retest3 section 1.7): Hack G, Skill tree K, Cyberware screen U, Operating System implant
 * Y, Arms implant L, Optical Camo O, rebindable in Minecraft's Controls. While Cyberpunk has the focus it forwards them as
 * {"t":"key","k":...} once it knows them from `binds`. Y, L and O each fire the ACTIVE implant of their own place
 * (CyberwareEffects.fire); there is no selection to cycle any more.
 */
public final class CyberKeys {
	static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Cyberblock.ID, "cyberblock"));
	/** Shown when two of the six keys would be one in Cyberpunk, until the lang file has its line. */
	private static final String CLASH_FALLBACK = "Two Cyberblock keys would be the same key in Cyberpunk, so there all six work as their defaults: %s";
	/** Shown when a vanilla mapping lost its key to ours, until the lang file has its line. */
	private static final String VANILLA_FALLBACK = "Cyberblock took %s from %s (rebind it in Controls)";
	/** Vanilla mappings (by name) already freed once, one per line, so a player who binds them back keeps his choice. */
	private static final String FREED_FILE = "cyberblock-freed-keys.txt";
	public static KeyMapping hack;
	public static KeyMapping skills;
	public static KeyMapping cyberware;
	/** The Operating System implant (Sandevistan or Berserk). */
	public static KeyMapping activate;
	public static KeyMapping arms;
	public static KeyMapping camo;
	/** The binds last sent (virtual-key codes, `binds` order), so a rebinding is sent once. */
	private static int[] sent = {-1, -1, -1, -1, -1, -1};
	private static InputConstants.Key @Nullable [] lastKeys;
	/** A host said hello: the note about the binds (if any) goes to chat once a player is there to read it. */
	private static boolean notePending;
	/** Notes about vanilla keys we took, shown once a player is there. */
	private static final List<Component> vanillaNotes = new ArrayList<>();
	/** Keys the host holds down: released when the host goes away, so a lost key-up never leaves the hack key held. */
	private static final Set<KeyMapping> hostHeld = new HashSet<>();

	private CyberKeys() {
	}

	static void register() {
		hack = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.hack", InputConstants.KEY_G, CATEGORY));
		skills = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.skills", InputConstants.KEY_K, CATEGORY));
		cyberware = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.cyberware", InputConstants.KEY_U, CATEGORY));
		activate = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.activate", InputConstants.KEY_Y, CATEGORY));
		arms = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.arms", InputConstants.KEY_L, CATEGORY));
		camo = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.camo", InputConstants.KEY_O, CATEGORY));
	}

	/** The six mappings in `binds` order (VirtualKeys.DEFAULTS, CyberWire.BIND_NAMES). */
	private static KeyMapping[] mappings() {
		return new KeyMapping[]{hack, skills, cyberware, activate, arms, camo};
	}

	/** The mapping that fires a trigger's implant. */
	static KeyMapping of(final CyberwareRules.Trigger trigger) {
		return switch (trigger) {
			case OS -> activate;
			case ARMS -> arms;
			case CAMO -> camo;
		};
	}

	/** The virtual key Cyberpunk forwards for each mapping's binding, -1 when it cannot (VirtualKeys.of). */
	private static int[] vks() {
		return Arrays.stream(mappings()).mapToInt(mapping -> {
			InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mapping);
			return VirtualKeys.of(key.getType() == InputConstants.Type.KEYBOARD, key.getValue());
		}).toArray();
	}

	private static InputConstants.Key[] boundKeys() {
		return Arrays.stream(mappings()).map(KeyMappingHelper::getBoundKeyOf).toArray(InputConstants.Key[]::new);
	}

	/** {"t":"binds"} with the current bindings (host hello, or a rebinding): a refused key as its default, a clash as all six. */
	static void sendBinds() {
		sent = VirtualKeys.binds(vks());
		HostLink.send(CyberWire.binds(sent));
	}

	/** A host said hello (client thread): it gets the binds, and the player the note about them as soon as he is in a world. */
	static void hostHello() {
		sendBinds();
		notePending = true;
	}

	/**
	 * What the player should know about the binds Cyberpunk uses, or null when it uses all six as bound: a key it cannot
	 * forward works there as its default (G, K, U, Y, L, O); two keys that would be the same there make all six the defaults.
	 */
	static @Nullable Component note() {
		int[] vks = vks();
		int[] used = VirtualKeys.binds(vks);
		if (VirtualKeys.clash(vks)) {
			return Component.translatableWithFallback("cyberblock.binds.clash_all", CLASH_FALLBACK,
				ComponentUtils.formatList(Arrays.stream(used).mapToObj(VirtualKeys::name).toList()));
		}

		MutableComponent note = null;
		KeyMapping[] mappings = mappings();
		for (int i = 0; i < mappings.length; i++) {
			if (vks[i] < 0) {
				Component line = Component.translatable("cyberblock.binds.note", mappings[i].getTranslatedKeyMessage(),
					Component.translatable(mappings[i].getName()), VirtualKeys.name(used[i]));
				note = note == null ? line.copy() : note.append(" ").append(line);
			}
		}

		return note;
	}

	private static Path freedFile() {
		return FabricLoader.getInstance().getConfigDir().resolve(FREED_FILE);
	}

	/**
	 * Minecraft 26.3's own keys on G (Quick Actions), L (Advancements) and O (Friends) would fire with ours and
	 * show conflicts in Controls (section 1.7). On the first tick (the options are loaded by then) every vanilla mapping bound
	 * to one of our six keys is unbound, once per mapping (FREED_FILE remembers it, so binding it back later sticks), and the
	 * options are saved; the player is told in chat and may rebind it.
	 */
	private static void freeVanillaClashes(final Minecraft minecraft) {
		Set<String> freed = new LinkedHashSet<>();
		try {
			if (Files.exists(freedFile())) {
				freed.addAll(Files.readAllLines(freedFile()));
			}
		} catch (IOException e) {
			Cyberblock.LOG.warn("binds: cannot read {}: {}", FREED_FILE, e.toString());
		}

		boolean changed = false;
		for (KeyMapping vanilla : minecraft.options.keyMappings) {
			// debug keys are F3 combinations (F3+G, F3+L): they never fire on our plain keys, so they keep theirs
			if (vanilla.getCategory() == CATEGORY || vanilla.getCategory() == KeyMapping.Category.DEBUG || vanilla.isUnbound()
				|| freed.contains(vanilla.getName())) {
				continue;
			}

			for (KeyMapping ours : mappings()) {
				if (vanilla.same(ours)) {
					Component key = vanilla.getTranslatedKeyMessage();
					vanilla.setKey(InputConstants.UNKNOWN);
					freed.add(vanilla.getName());
					changed = true;
					Cyberblock.LOG.info("binds: unbound vanilla {} ({})", vanilla.getName(), ours.saveString());
					vanillaNotes.add(Component.translatableWithFallback("cyberblock.binds.vanilla_unbound", VANILLA_FALLBACK, key,
						Component.translatable(vanilla.getName())).withStyle(ChatFormatting.YELLOW));
					break;
				}
			}
		}

		if (changed) {
			KeyMapping.resetMapping();
			minecraft.options.save();
			try {
				Files.write(freedFile(), freed);
			} catch (IOException e) {
				Cyberblock.LOG.warn("binds: cannot write {}: {}", FREED_FILE, e.toString());
			}
		}
	}

	private static void showNote(final Minecraft minecraft) {
		Component note = note();
		if (note != null && minecraft.player != null) {
			minecraft.player.sendSystemMessage(note);
		}
	}

	/**
	 * Every client tick: the skill and cyberware keys open their screens; Y, L and O fire their implants; keys the host held
	 * are let go once it stopped driving; a changed binding is sent to Cyberpunk at once (and noted when it cannot be
	 * forwarded). The first tick frees our keys from vanilla's mappings.
	 */
	static void tick(final Minecraft minecraft) {
		while (skills.consumeClick()) {
			if (minecraft.player != null && minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new SkillTreeScreen());
			}
		}

		while (cyberware.consumeClick()) {
			if (minecraft.player != null && minecraft.gui.screen() == null) {
				minecraft.gui.setScreen(new CyberwareScreen());
			}
		}

		if (HostState.frame() == null && !hostHeld.isEmpty()) {
			hostHeld.forEach(mapping -> mapping.setDown(false));
			hostHeld.clear();
		}

		for (CyberwareRules.Trigger trigger : CyberwareRules.Trigger.values()) {
			fireTick(minecraft, trigger);
		}

		if (minecraft.player != null) {
			if (notePending) {
				notePending = false;
				showNote(minecraft);
			}

			vanillaNotes.forEach(minecraft.player::sendSystemMessage);
			vanillaNotes.clear();
		}

		InputConstants.Key[] keys = boundKeys();
		if (lastKeys == null) {
			freeVanillaClashes(minecraft);
			lastKeys = boundKeys();
			return;
		}

		if (!Arrays.equals(keys, lastKeys)) {
			lastKeys = keys;
			if (!Arrays.equals(VirtualKeys.binds(vks()), sent)) {
				sendBinds();
			}

			showNote(minecraft);
		}
	}

	/** One fire key's presses this tick: each fires its implant on the server, never while a screen is open. */
	private static void fireTick(final Minecraft minecraft, final CyberwareRules.Trigger trigger) {
		KeyMapping mapping = of(trigger);
		boolean clicked = false;
		while (mapping.consumeClick()) {
			clicked = true;
		}

		LocalPlayer player = minecraft.player;
		if (!clicked || player == null || minecraft.gui.screen() != null || player.isSpectator()) {
			return;
		}

		CyberLink.act(CyberActs.FIRE, CyberActs.fire(trigger, aim(minecraft, player)));
	}

	/**
	 * Where the launcher's rocket (and the Mantis lunge) goes: the first block or entity along the host camera's ray (as
	 * EntityPickMixin aims, from level with the player in third person), at most 64 blocks and never past the Night City
	 * surface under the crosshair.
	 */
	private static Vec3 aim(final Minecraft minecraft, final LocalPlayer player) {
		Camera camera = minecraft.gameRenderer.mainCamera();
		Vec3 from = camera.position();
		Vec3 dir = new Vec3(camera.forwardVector()).normalize();
		HostState.Pose pose = HostState.frame();
		double along = pose == null || pose.firstPerson() ? 0.0 : Math.max(0.0, player.getEyePosition(1.0F).subtract(from).dot(dir));
		Vec3 start = from.add(dir.scale(along));
		Vec3 end = from.add(dir.scale(HostState.aimLimit(along + CyberwareRules.PLS_RANGE)));
		Vec3 block = player.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getLocation();
		EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, start, block, new AABB(start, block).inflate(1.0),
			e -> !e.isSpectator() && (e.isPickable() || e instanceof ProxyEntity) && !e.isRemoved(), start.distanceToSqr(block));
		return entity == null ? block : entity.getLocation();
	}

	/**
	 * {"t":"key","k":"hack"|"skills"|"cyberware"|"activate"|"arms"|"camo","down":bool} from the host, on the client thread:
	 * pressed and released as vanilla's keyboard handler would (a press counts a click). With a screen open the game never sees
	 * a key, as in ClientInput; the skill and cyberware keys still close their own screens.
	 */
	static void onHostKey(final Minecraft minecraft, final KeyMapping mapping, final boolean down) {
		if (minecraft.gui.screen() != null) {
			if (down && (mapping == skills && minecraft.gui.screen() instanceof SkillTreeScreen
				|| mapping == cyberware && minecraft.gui.screen() instanceof CyberwareScreen)) {
				minecraft.gui.screen().onClose();
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
