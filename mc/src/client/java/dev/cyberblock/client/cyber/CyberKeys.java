package dev.cyberblock.client.cyber;

import com.mojang.blaze3d.platform.InputConstants;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.client.HostState;
import dev.cyberblock.client.cyber.mixin.CyberKeyMappingAccessor;
import dev.cyberblock.cyber.CyberMod;
import dev.cyberblock.cyber.CyberWire;
import dev.cyberblock.cyber.CyberwareEffects;
import dev.cyberblock.cyber.CyberwareRules;
import dev.cyberblock.cyber.Ripperdoc;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Cyberblock key mappings (Hack G, Skill tree K, Cyberware screen U, Activate implant Y: plan retest2 section 1.8),
 * rebindable in Minecraft's Controls. While Cyberpunk has the focus it forwards them as {"t":"key","k":...} once it knows
 * them from `binds`. Y: a tap (let go within 8 ticks) fires the selected implant, holding it 8 ticks selects the next one.
 */
public final class CyberKeys {
	static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Cyberblock.ID, "cyberblock"));
	/** Shown when two of the four keys would be one in Cyberpunk, until the lang file has its line. */
	private static final String CLASH_FALLBACK = "Two Cyberblock keys would be the same key in Cyberpunk, so there all four work as %s %s %s %s";
	public static KeyMapping hack;
	public static KeyMapping skills;
	public static KeyMapping cyberware;
	public static KeyMapping activate;
	/** The binds last sent (virtual-key codes, `binds` order), so a rebinding is sent once. */
	private static int[] sent = {-1, -1, -1, -1};
	private static InputConstants.Key @Nullable [] lastKeys;
	/** A host said hello: the note about the binds (if any) goes to chat once a player is there to read it. */
	private static boolean notePending;
	/** Keys the host holds down: released when the host goes away, so a lost key-up never leaves the hack key held. */
	private static final Set<KeyMapping> hostHeld = new HashSet<>();
	/** Client ticks the activate key has been down (-1: up), and whether this hold already cycled the selection. */
	private static int activateTicks = -1;
	private static boolean activateCycled;

	private CyberKeys() {
	}

	static void register() {
		hack = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.hack", InputConstants.KEY_G, CATEGORY));
		skills = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.skills", InputConstants.KEY_K, CATEGORY));
		cyberware = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.cyberware", InputConstants.KEY_U, CATEGORY));
		activate = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cyberblock.activate", InputConstants.KEY_Y, CATEGORY));
	}

	/** The four mappings in `binds` order (VirtualKeys.DEFAULTS). */
	private static KeyMapping[] mappings() {
		return new KeyMapping[]{hack, skills, cyberware, activate};
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

	/** {"t":"binds"} with the current bindings (host hello, or a rebinding): a refused key as its default, a clash as all four. */
	static void sendBinds() {
		sent = VirtualKeys.binds(vks());
		Cyberblock.events.accept(CyberWire.binds(sent));
	}

	/** A host said hello (client thread): it gets the binds, and the player the note about them as soon as he is in a world. */
	static void hostHello() {
		sendBinds();
		notePending = true;
	}

	/**
	 * What the player should know about the binds Cyberpunk uses, or null when it uses all four as bound: a key it cannot
	 * forward works there as its default (G, K, U, Y); two keys that would be the same there make all four the defaults.
	 */
	static @Nullable Component note() {
		int[] vks = vks();
		int[] used = VirtualKeys.binds(vks);
		if (VirtualKeys.clash(vks)) {
			return Component.translatableWithFallback("cyberblock.binds.clash", CLASH_FALLBACK,
				VirtualKeys.name(used[0]), VirtualKeys.name(used[1]), VirtualKeys.name(used[2]), VirtualKeys.name(used[3]));
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
	 * Every client tick: the skill and cyberware keys open their screens; the activate key fires or cycles; keys the host held
	 * are let go once it stopped driving (an activate key held then neither fires nor cycles); a changed binding is sent to
	 * Cyberpunk at once (and noted when it cannot be forwarded). The first tick frees G from Minecraft's Quick Actions.
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

		if (!Cyberblock.active && !hostHeld.isEmpty()) {
			if (hostHeld.contains(activate)) {
				activateTicks = -1;
				while (activate.consumeClick()) {
					// the press the host never let go of: dropped with it
				}
			}

			hostHeld.forEach(mapping -> mapping.setDown(false));
			hostHeld.clear();
		}

		activateTick(minecraft);
		if (notePending && minecraft.player != null) {
			notePending = false;
			showNote(minecraft);
		}

		InputConstants.Key[] keys = boundKeys();
		if (lastKeys == null) {
			freeQuickActions(minecraft);
			lastKeys = keys;
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

	/** The activate key's tap and hold (plan retest2 section 1.8), never while a screen is open. */
	private static void activateTick(final Minecraft minecraft) {
		boolean clicked = false;
		while (activate.consumeClick()) {
			clicked = true;
		}

		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.gui.screen() != null || player.isSpectator()) {
			activateTicks = -1;
			return;
		}

		if (activate.isDown()) {
			if (activateTicks < 0) {
				activateTicks = 0;
				activateCycled = false;
			}

			if (++activateTicks >= CyberwareRules.TAP_TICKS && !activateCycled) {
				activateCycled = true;
				onServer(minecraft, Ripperdoc::cycle);
			}

			return;
		}

		// a press and release between two ticks shows only as a click: a tap too
		boolean tapped = activateTicks >= 0 ? !activateCycled : clicked;
		activateTicks = -1;
		if (tapped) {
			Vec3 aim = aim(minecraft, player);
			onServer(minecraft, serverPlayer -> CyberwareEffects.activate(serverPlayer, aim));
		}
	}

	/**
	 * Where the launcher's rocket goes: the first block or entity along the host camera's ray (as EntityPickMixin aims, from
	 * level with the player in third person), at most 64 blocks and never past the Night City surface under the crosshair.
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

	/** Singleplayer only (spec), as the skill tree's purchases: the integrated server's player does it. */
	private static void onServer(final Minecraft minecraft, final Consumer<ServerPlayer> task) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> {
				ServerPlayer player = CyberMod.localPlayer();
				if (player != null) {
					task.accept(player);
				}
			});
		}
	}

	/**
	 * {"t":"key","k":"hack"|"skills"|"cyberware"|"activate","down":bool} from the host, on the client thread: pressed and
	 * released as vanilla's keyboard handler would (a press counts a click). With a screen open the game never sees a key, as in
	 * ClientInput; the skill and cyberware keys still close their own screens.
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
