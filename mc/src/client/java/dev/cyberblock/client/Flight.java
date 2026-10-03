// Drive mode based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough (PlayerSync)
package dev.cyberblock.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.FlightRules;
import dev.cyberblock.HostDamage;
import dev.cyberblock.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;

/**
 * Layer 4 elytra flight. Steve normally hangs at the host's V (PlayerSync). With a usable elytra, the host's jump key while V
 * falls (or a long fast fall) starts a vanilla glide: Minecraft's physics move Steve ("drive mode"), the look comes from the host
 * camera (or from the host's raw look deltas in its camera-only fallback), and every frame the host hears where he is
 * ({"t":"flight"}) and moves V there. Touchdown ends it. Client thread only.
 */
public final class Flight {
	private static final long FRAME_NANOS = 1_000_000_000L / 120;
	private static final FlightRules.Fall fall = new FlightRules.Fall();
	private static long lastPose;
	private static boolean driving;
	private static boolean landed;
	private static long touchdownNanos;
	/** The host sent look deltas during this flight: they steer, not its camera. */
	private static boolean lookFromDeltas;
	private static float lookYaw, lookPitch;
	private static long lastSent;

	private Flight() {
	}

	/** Minecraft moves Steve (gliding). */
	public static boolean driving() {
		return driving;
	}

	/** Steve is placed at the host's V: not while gliding, nor for half a second after touchdown (the host is placing V). */
	static boolean followsCam() {
		return !driving && (!landed || System.nanoTime() - touchdownNanos >= FlightRules.SETTLE_NANOS);
	}

	/** Every client tick, at the start of the player's tick, before PlayerSync follows the host. */
	static void tick(final LocalPlayer player) {
		boolean jumpPressed = HostState.takeJumpPress();
		HostState.Pose p = HostState.live();
		if (driving) {
			HostDamage.fallFreeUntil = System.nanoTime() + FlightRules.FALL_GRACE_NANOS;
			if (p == null || FlightRules.touchdown(player.onGround(), player.isFallFlying())) {
				land(player);
			}

			return;
		}

		if (p == null || HostState.vehicle() || !player.isAlive() || player.isSpectator() || player.isPassenger()) {
			fall.reset();
			return;
		}

		if (p.receivedNanos() != lastPose) {
			lastPose = p.receivedNanos();
			fall.sample(lastPose, p.px(), p.py(), p.pz());
		}

		if (followsCam() && FlightRules.start(jumpPressed, hasElytra(player), fall.speed3(), fall.fastNanos())) {
			start(player, p);
		}
	}

	private static boolean hasElytra(final LivingEntity player) {
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			if (LivingEntity.canGlideUsing(player.getItemBySlot(slot), slot)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Vanilla's glide start (LocalPlayer.aiStep: tryToStartFallFlying, then the START_FALL_FLYING command), from V's feet with V's
	 * velocity and gravity on. The server only allows it to a player that is not on the ground and not flying, so it hears that
	 * first (Steve hung at V with no physics: what it last heard may be stale).
	 */
	private static void start(final LocalPlayer player, final HostState.Pose p) {
		Abilities abilities = player.getAbilities();
		if (abilities.flying) {
			abilities.flying = false;
			player.onUpdateAbilities();
		}

		player.setOnGround(false);
		player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(false, player.horizontalCollision));
		player.setPos(p.px(), p.py(), p.pz());
		player.xo = player.xOld = p.px();
		player.yo = player.yOld = p.py();
		player.zo = player.zOld = p.pz();
		double[] v = fall.velocity();
		player.setDeltaMovement(v[0] / 20.0, v[1] / 20.0, v[2] / 20.0);
		if (!player.tryToStartFallFlying()) {
			return; // in water, levitating: stays with V
		}

		player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
		gravity(player, true);
		driving = true;
		landed = false;
		lookFromDeltas = false;
		lastSent = 0L;
		HostDamage.fallFreeUntil = System.nanoTime() + FlightRules.FALL_GRACE_NANOS;
		Cyberblock.LOG.info("glide started at {} with {} m/s", player.position(), Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]));
	}

	/** Touchdown (or the host went away mid-air): the host hears where, and Steve hangs there again until the host has placed V. */
	private static void land(final LocalPlayer player) {
		driving = false;
		landed = true;
		touchdownNanos = System.nanoTime();
		HostDamage.fallFreeUntil = touchdownNanos + FlightRules.FALL_GRACE_NANOS;
		Vec3 feet = player.position();
		Cyberblock.events.accept(Wire.flight(false, feet.x, feet.y, feet.z, 0.0, 0.0, 0.0, player.getYRot(), player.getXRot()));
		gravity(player, false);
		// vanilla ends a glide only on the ground: when the host went away mid-air Steve would hover gliding with no gravity
		player.stopFallFlying();
		player.setDeltaMovement(Vec3.ZERO);
		fall.reset();
		Cyberblock.LOG.info("glide ended at {}", feet);
	}

	/**
	 * Gravity on (gliding) or off (hanging at V), on the client and on the integrated server, so neither resyncs the other. Off
	 * also ends the server's glide, which otherwise keeps taking elytra durability while Steve hangs in the air.
	 */
	private static void gravity(final LocalPlayer player, final boolean on) {
		player.setNoGravity(!on);
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server != null) {
			server.execute(() -> {
				ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
				if (serverPlayer != null) {
					serverPlayer.setNoGravity(!on);
					if (!on) {
						serverPlayer.stopFallFlying();
					}
				}
			});
		}
	}

	/** {"t":"look","d":[dyaw,dpitch]} (the host's camera-only fallback): summed onto Steve's look while gliding. */
	static void look(final JsonObject m) {
		JsonArray d = m.getAsJsonArray("d");
		double dyaw = d.get(0).getAsDouble(), dpitch = d.get(1).getAsDouble();
		LocalPlayer player = Minecraft.getInstance().player;
		if (!driving || player == null || !(Math.abs(dyaw) <= 360.0) || !(Math.abs(dpitch) <= 360.0)) {
			return;
		}

		if (!lookFromDeltas) {
			lookFromDeltas = true;
			lookYaw = player.getYRot();
			lookPitch = player.getXRot();
		}

		lookYaw += (float) dyaw;
		lookPitch = Math.clamp(lookPitch + (float) dpitch, -90.0F, 90.0F);
	}

	/** Every frame while gliding: steer with the host's look, and tell the host where Steve is drawn (at most 120 times a second). */
	static void frame(final LocalPlayer player, final HostState.Pose p, final float partialTick) {
		float yaw = lookFromDeltas ? lookYaw : p.yaw();
		float pitch = lookFromDeltas ? lookPitch : p.pitch();
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.yRotO = yaw;
		player.xRotO = pitch;
		player.yHeadRot = player.yHeadRotO = yaw;
		long now = System.nanoTime();
		if (now - lastSent < FRAME_NANOS) {
			return;
		}

		lastSent = now;
		// where the player is drawn this frame and how fast that moves (the slope of the tick interpolation, per second)
		Vec3 at = player.getPosition(partialTick);
		Cyberblock.events.accept(Wire.flight(true, at.x, at.y, at.z, (player.getX() - player.xo) * 20.0, (player.getY() - player.yo) * 20.0,
			(player.getZ() - player.zo) * 20.0, yaw, pitch));
	}
}
