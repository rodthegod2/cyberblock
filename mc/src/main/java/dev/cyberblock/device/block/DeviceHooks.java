package dev.cyberblock.device.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.ServerNet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Where a device block's use goes (megaplan 4-B1 with 4-M1). The blocks only ask; package 4M ({@code device/**}) owns the answer:
 * it {@link #install installs} a {@link Handler} that finds the device at the clicked cell, checks the owner's toggles and type bits,
 * sends {@code devact} DOWN and changes the block only after {@code devresult ok} (or the next {@code devstate}), so Minecraft never
 * shows a state Night City refused.
 *
 * <p>Two ways in, both on the server thread:
 * <ul>
 * <li>A right-click on a device block (vanilla's use path: the server already checked reach): {@code open, close, on, off, enable,
 * disable, friendly, call, shop, view}.</li>
 * <li>{@code cyberblock:act t:"devact"} from a screen ({@link ShopWire}: the vending screen's {@code buy}), checked here: the act is one a
 * screen may send, the cell holds a device block in the player's level and is within {@link #REACH}.</li>
 * </ul>
 * Quest-locked blocks refuse here, before the handler, with {@code cyberblock.device.quest_locked}.
 */
public final class DeviceHooks {
	/** What the server does with a device block's use (4M installs it). Server thread. */
	@FunctionalInterface
	public interface Handler {
		/**
		 * {@code player} used the device block at {@code pos} (any cell of a multi-block device: a door half, a gate panel, a lift
		 * platform) asking for {@code act}; {@code extra} carries the request's other fields ({@code buy}: {@code line}), empty for a
		 * right-click.
		 */
		void use(ServerPlayer player, BlockPos pos, String act, JsonObject extra);
	}

	/** The {@code cyberblock:act} type screens send device requests with (megaplan section 1.3). */
	public static final String T_DEVACT = "devact";
	/** The acts a screen may send; everything else comes from the block's own use path. */
	static final Set<String> SCREEN_ACTS = Set.of("buy");
	/** Blocks: how far from the player's eyes a screen request's cell may be (vanilla's block reach plus a margin). */
	static final double REACH = 8.0;
	private static volatile @Nullable Handler handler;

	private DeviceHooks() {
	}

	/** 4M: the device layer's answer to every device block use (replaces any earlier handler). */
	public static void install(final Handler h) {
		handler = h;
		Cyberblock.LOG.info("device: block use handler installed ({})", h.getClass().getName());
	}

	/** Is a handler installed (the device layer is in this build)? */
	public static boolean installed() {
		return handler != null;
	}

	static @Nullable Handler handler() {
		return handler;
	}

	/** Puts back a handler a dev stand-in replaced (null: none). */
	static void restore(final @Nullable Handler h) {
		handler = h;
	}

	static void init() {
		ServerNet.onAct(T_DEVACT, DeviceHooks::onScreenRequest);
	}

	/**
	 * A right-click asking for {@code act}: on the client the hand swings and the server decides; on the server a quest-locked
	 * block refuses, otherwise the handler gets it.
	 */
	static InteractionResult act(final BlockState state, final Level level, final BlockPos pos, final Player player, final String act) {
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.SUCCESS;
		}

		if (questLocked(state)) {
			tell(serverPlayer, "cyberblock.device.quest_locked", "Night City is holding this device for a quest");
		} else {
			request(serverPlayer, pos, act, new JsonObject());
		}

		return InteractionResult.SUCCESS_SERVER;
	}

	/** A right-click the block answers itself, with a message on the action bar. */
	static InteractionResult say(final Player player, final String key, final String fallback) {
		if (player instanceof ServerPlayer serverPlayer) {
			tell(serverPlayer, key, fallback);
			return InteractionResult.SUCCESS_SERVER;
		}

		return InteractionResult.SUCCESS;
	}

	/** To the handler, or "not linked" when no device layer is installed. */
	static void request(final ServerPlayer player, final BlockPos pos, final String act, final JsonObject extra) {
		Handler h = handler;
		if (h == null) {
			tell(player, "cyberblock.device.offline", "This device is not linked to a Night City game right now");
			return;
		}

		h.use(player, pos.immutable(), act, extra);
	}

	/** A short line on the player's action bar. */
	public static void tell(final ServerPlayer player, final String key, final String fallback) {
		player.sendOverlayMessage(Component.translatableWithFallback(key, fallback));
	}

	public static boolean questLocked(final BlockState state) {
		return state.hasProperty(DeviceBlock.QUEST_LOCKED) && state.getValue(DeviceBlock.QUEST_LOCKED);
	}

	/** {@code cyberblock:act devact} from a screen: only screen acts, on a device block the player can reach, else dropped (logged). */
	private static void onScreenRequest(final ServerPlayer player, final JsonObject m) {
		Request r = parse(m);
		if (r == null) {
			Cyberblock.LOG.debug("device: bad devact from {} dropped", player.getPlainTextName());
			return;
		}

		BlockPos pos = new BlockPos(r.x(), r.y(), r.z());
		BlockState state = player.level().getBlockState(pos);
		if (!DeviceBlocks.is(state) || player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH) {
			Cyberblock.LOG.debug("device: devact {} from {} at {} refused (no device in reach)", r.act(), player.getPlainTextName(), pos.toShortString());
			return;
		}

		if (questLocked(state)) {
			tell(player, "cyberblock.device.quest_locked", "Night City is holding this device for a quest");
			return;
		}

		request(player, pos, r.act(), m);
	}

	/** One screen request: the device cell and the act. */
	record Request(int x, int y, int z, String act) {
	}

	/** {@code {"pos":[x,y,z],"act":"buy",...}} with a screen act, or null. Pure. */
	static @Nullable Request parse(final JsonObject m) {
		try {
			JsonElement pos = m.get("pos");
			JsonElement act = m.get("act");
			if (pos == null || !pos.isJsonArray() || act == null || !act.isJsonPrimitive() || !act.getAsJsonPrimitive().isString()) {
				return null;
			}

			JsonArray p = pos.getAsJsonArray();
			String a = act.getAsString();
			if (p.size() != 3 || !SCREEN_ACTS.contains(a)) {
				return null;
			}

			int[] c = new int[3];
			for (int i = 0; i < 3; i++) {
				double v = p.get(i).getAsDouble();
				if (v != Math.rint(v) || Math.abs(v) > 30_000_000) {
					return null;
				}

				c[i] = (int) v;
			}

			return new Request(c[0], c[1], c[2], a);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
