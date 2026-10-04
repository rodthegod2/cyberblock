// Drive mode based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough (PlayerSync)
package dev.cyberblock.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.FlightRules;
import dev.cyberblock.Wire;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Cyberware;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Layer 4 elytra flight. Steve normally hangs at the host's V (PlayerSync). With a usable elytra and at least 3 blocks of air
 * below V, the host's jump key while V falls (with the Double Jump perk: the second press in the air), or a long fast fall,
 * starts a vanilla glide: Minecraft's physics move Steve ("drive mode"), the look comes from the host
 * camera (or from the host's raw look deltas in its camera-only fallback), and every frame the host hears where he is
 * ({"t":"flight"}) and moves V there. Touchdown ends it. The host says in every cam whether it follows (cam.fly): a glide it does
 * not take up within half a second, or stops following, is called off and Steve goes back to V (in-game 2026-10-03: the host
 * refused a glide and Steve flew 320 m alone, into the void).
 *
 * <p>Plan retest2 R5, the launch: a knockback that lifts Steve (his own or anyone's wind charge, a breeze's, a mace's wind burst,
 * any explosion: ClientPacketListenerMixin hands it over) throws him off V's ground with vanilla physics, and V follows him like a
 * glide until he lands ({"t":"flight","kind":"launch"}). The jump key while he falls in it opens his elytra: the launch goes on as a
 * glide, with no new handshake. Every flight sample carries Minecraft's clock (ts) so the host can play them out smoothly every
 * frame. Client thread only.
 */
public final class Flight {
	/** A launch (vanilla physics from a knockback) or a glide (the elytra). */
	enum Mode {
		GLIDE, LAUNCH
	}

	/** The origin of the flight samples' clock (ts, ms since this): System.nanoTime is monotonic. */
	public static final long EPOCH = System.nanoTime();
	private static final long FRAME_NANOS = 1_000_000_000L / 120;
	private static final FlightRules.Fall fall = new FlightRules.Fall();
	private static final FlightRules.Follow follow = new FlightRules.Follow();
	private static final FlightRules.AirJumps airJumps = new FlightRules.AirJumps();
	private static long lastPose;
	private static boolean driving;
	/** Steve is launched (plan retest2 R5): Minecraft's physics move him, so he collides with blocks (PlayerMixin) as while gliding. */
	private static volatile boolean launching;
	private static Mode mode = Mode.GLIDE;
	private static long launchStart;
	/** Knockback waiting for the next tick (blocks per tick): explosions add up, a motion packet replaces the last one. Null: none. */
	private static @Nullable Vec3 explosionKick, motionKick;
	private static boolean landed;
	private static long touchdownNanos;
	/** Where the last flight touched down: V standing near it ends the settle (followsCam). */
	private static Vec3 landingSpot = Vec3.ZERO;
	/** No glide starts before this (System.nanoTime, compared as a difference) after one was called off. */
	private static long retryAt = System.nanoTime();
	/** A Cyberpunk menu paused the glide: where Steve waits, and the velocity he carries on with. Both null while not paused. */
	private static @Nullable Vec3 pausedAt, pausedVelocity;
	/** The host sent look deltas during this flight: they steer, not its camera. */
	private static boolean lookFromDeltas;
	private static float lookYaw, lookPitch;
	private static long lastSent;

	private Flight() {
	}

	/** Steve is launched (sent to the server as hstate.launch). Any thread. */
	public static boolean launching() {
		return launching;
	}

	/** Minecraft moves Steve (gliding or launched). */
	public static boolean driving() {
		return driving;
	}

	/** Steve glides on his elytra (a glide, or a launch that opened them): from the start tick to the landing tick. */
	public static boolean gliding() {
		return driving && mode == Mode.GLIDE;
	}

	/** When the current glide started (System.nanoTime), for the pose's fall-flying ramp. */
	private static long glideStart;

	/**
	 * Q-V2: ticks (fractional) since the glide started, for the render state's fall-flying time: counted from Flight's own start,
	 * so a blip in the synced fall-flying flag never restarts the body's tilt into the glide. 0 while not gliding.
	 */
	public static float glideTicks() {
		return gliding() ? (System.nanoTime() - glideStart) / 50_000_000.0F : 0.0F;
	}

	/**
	 * Steve is placed at the host's V: not while he flies, nor after touchdown until the host has placed V at the landing spot (a
	 * pose that arrived since says V no longer follows and stands within 1.5 m of it), half a second at most.
	 */
	static boolean followsCam() {
		if (driving) {
			return false;
		}

		if (!landed) {
			return true;
		}

		HostState.Pose p = HostState.live();
		long now = System.nanoTime();
		boolean settled = p == null ? now - touchdownNanos >= FlightRules.SETTLE_NANOS
			: FlightRules.settled(now, touchdownNanos, p.receivedNanos(), p.fly(), landingSpot.distanceTo(new Vec3(p.px(), p.py(), p.pz())));
		if (settled) {
			landed = false; // V walking off afterwards does not make Steve wait again
		}

		return settled;
	}

	/**
	 * Client thread, from ClientPacketListenerMixin: knockback the server gave Steve (blocks per tick), from an explosion (its
	 * playerKnockback, added up) or a motion packet (the latest one counts). The next tick decides whether it launches him.
	 */
	public static void impulse(final Vec3 v, final boolean explosion) {
		if (explosion) {
			explosionKick = explosionKick == null ? v : explosionKick.add(v);
		} else {
			motionKick = v;
		}
	}

	/** The knockback since the last tick that launches Steve (an explosion's first), or null. Either way nothing is left waiting. */
	private static @Nullable Vec3 takeKick() {
		Vec3 explosion = explosionKick, motion = motionKick;
		explosionKick = motionKick = null;
		if (explosion != null && FlightRules.launch(explosion.x, explosion.y, explosion.z, true)) {
			return explosion;
		}

		return motion != null && FlightRules.launch(motion.x, motion.y, motion.z, false) ? motion : null;
	}

	/** Every client tick, at the start of the player's tick, before PlayerSync follows the host. */
	static void tick(final LocalPlayer player) {
		boolean jumpPressed = HostState.takeJumpPress();
		// flying already, vanilla has applied the knockback to his motion: nothing more to do with it
		Vec3 kick = takeKick();
		HostState.Pose p = HostState.live();
		if (driving) {
			drive(player, p, jumpPressed);
			return;
		}

		if (p == null || HostState.vehicle() || !player.isAlive() || player.isSpectator() || player.isPassenger()) {
			fall.reset();
			airJumps.reset();
			return;
		}

		// a host that cannot move V (held by the game, or paused in a menu) would only refuse the launch
		if (kick != null && !p.held() && !p.menu()) {
			startLaunch(player, p, kick);
			return;
		}

		if (p.receivedNanos() != lastPose) {
			lastPose = p.receivedNanos();
			fall.sample(lastPose, p.px(), p.py(), p.pz());
		}

		// with the reinforced tendons implant V's first jump in the air is V's own double jump (plan retest2 section 1.7)
		boolean glidePress = airJumps.press(p.air(), jumpPressed, CyberData.has(player, Cyberware.REINFORCED_TENDONS));
		// a host that cannot move V (held by the game, or paused in a menu) would only refuse the glide
		if (!followsCam() || p.held() || p.menu() || System.nanoTime() - retryAt < 0L || !hasElytra(player)) {
			return;
		}

		// Steve's gliding box at V's feet, and the free air under them (barriers count: they are Night City's ground)
		AABB box = player.getDimensions(Pose.FALL_FLYING).makeBoundingBox(p.px(), p.py(), p.pz());
		boolean overlapping = !player.level().noCollision(player, box);
		if (FlightRules.start(glidePress, true, fall.speed(), fall.fastNanos(), clearance(player, new Vec3(p.px(), p.py(), p.pz())), overlapping)) {
			start(player, p);
		}
	}

	/** Free air (blocks) straight down from these feet, MIN_CLEARANCE when nothing solid is that close. */
	private static double clearance(final LocalPlayer player, final Vec3 feet) {
		Vec3 below = feet.subtract(0.0, FlightRules.MIN_CLEARANCE, 0.0);
		BlockHitResult hit = player.level().clip(new ClipContext(feet, below, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.MISS ? FlightRules.MIN_CLEARANCE : feet.y - hit.getLocation().y;
	}

	/** A client tick while flying: carry on, call it off when the host does not follow, open the elytra from a launch, or land. */
	private static void drive(final LocalPlayer player, final HostState.@Nullable Pose p, final boolean jumpPressed) {
		if (!player.isAlive()) {
			abort(player, p); // died mid-glide: no landing spot (after the respawn it would be the world spawn)
			return;
		}

		if (p == null) {
			land(player, null); // the host went away
			return;
		}

		long now = System.nanoTime();
		if (p.menu()) {
			pause(player);
			return;
		}

		if (pausedAt != null) {
			resume(player, now);
		}

		if (!follow.ok(now, p.receivedNanos(), p.fly())) {
			abort(player, p);
			return;
		}

		double apart = player.position().distanceTo(new Vec3(p.px(), p.py(), p.pz()));
		if (follow.tooFarApart(now, apart)) {
			Cyberblock.LOG.info("{}: the host's V stayed {} blocks from Steve for a second", mode == Mode.LAUNCH ? "launch" : "glide", Math.round(apart));
			abort(player, p);
			return;
		}

		if (mode == Mode.LAUNCH) {
			if (FlightRules.launchLanded(player.onGround(), player.isInWater(), now - launchStart)) {
				land(player, p);
			} else if (FlightRules.launchToGlide(jumpPressed, hasElytra(player), -player.getDeltaMovement().y * 20.0, clearance(player, player.position()))) {
				glideFromLaunch(player);
			}

			return;
		}

		if (FlightRules.touchdown(player.onGround(), player.isFallFlying())) {
			land(player, p);
		}
	}

	/** The jump key while a launch falls: vanilla's glide start, and the flight goes on as a glide (the host already follows it). */
	private static void glideFromLaunch(final LocalPlayer player) {
		if (!player.tryToStartFallFlying()) {
			return;
		}

		player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
		mode = Mode.GLIDE;
		glideStart = System.nanoTime();
		Cyberblock.LOG.info("launch turned into a glide at {}, yaw {}", player.position(), player.getYRot());
	}

	/**
	 * A knockback lifts Steve off V's ground (plan retest2 R5): from V's feet (or, while the host is still placing V after a landing,
	 * from where he stands), clear of any block, with the knockback as his motion and gravity on; vanilla's physics carry him and the
	 * host follows with V as in a glide. A creative player stops flying for it (else he would hover); PlayerSync gives the flight
	 * back once he is with V again.
	 */
	private static void startLaunch(final LocalPlayer player, final HostState.Pose p, final Vec3 kick) {
		Abilities abilities = player.getAbilities();
		if (abilities.flying) {
			abilities.flying = false;
			player.onUpdateAbilities();
		}

		player.setOnGround(false);
		player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(false, player.horizontalCollision));
		Vec3 at = clearOfBlocks(player, followsCam() ? new Vec3(p.px(), p.py(), p.pz()) : player.position());
		player.setPos(at);
		player.xo = player.xOld = at.x;
		player.yo = player.yOld = at.y;
		player.zo = player.zOld = at.z;
		player.setDeltaMovement(kick);
		gravity(player, true, false);
		launching = true; // collisions, as while gliding (PlayerMixin)
		driving = true;
		mode = Mode.LAUNCH;
		landed = false;
		lookFromDeltas = false;
		lastSent = 0L;
		long now = System.nanoTime();
		launchStart = now;
		follow.start(now);
		fall.reset();
		airJumps.reset();
		Cyberblock.LOG.info("launch started at {} with {} m/s, yaw {}", at, kick.length() * 20.0, player.getYRot());
	}

	/** The lowest spot at most one block above feet (in 1/16 steps) where Steve's box is free of blocks and barriers; feet if none. */
	private static Vec3 clearOfBlocks(final LocalPlayer player, final Vec3 feet) {
		for (int i = 0; i <= 16; i++) {
			Vec3 at = feet.add(0.0, i / 16.0, 0.0);
			if (player.level().noCollision(player, player.getDimensions(player.getPose()).makeBoundingBox(at.x, at.y, at.z))) {
				return at;
			}
		}

		return feet;
	}

	/**
	 * A Cyberpunk menu is open mid-glide: Cyberpunk is paused and V cannot follow, so Steve waits in the air where he is, with no
	 * gravity and no flight samples (the host holds V when the samples stop, and carries on when they come back).
	 */
	private static void pause(final LocalPlayer player) {
		if (pausedAt == null) {
			pausedAt = player.position();
			pausedVelocity = player.getDeltaMovement();
			gravity(player, false, false);
			Cyberblock.LOG.info("{} paused at {} (Cyberpunk menu)", mode == Mode.LAUNCH ? "launch" : "glide", pausedAt);
		}

		player.setPos(pausedAt);
		player.xo = player.xOld = pausedAt.x;
		player.yo = player.yOld = pausedAt.y;
		player.zo = player.zOld = pausedAt.z;
		player.setDeltaMovement(Vec3.ZERO);
	}

	/** The menu closed: the glide carries on with the velocity it had. */
	private static void resume(final LocalPlayer player, final long now) {
		player.setDeltaMovement(pausedVelocity);
		gravity(player, true, false);
		pausedAt = pausedVelocity = null;
		follow.restart(now); // a host that had not followed yet gets its full half second again
		Cyberblock.LOG.info("{} resumed", mode == Mode.LAUNCH ? "launch" : "glide");
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
		gravity(player, true, false);
		driving = true;
		mode = Mode.GLIDE;
		glideStart = System.nanoTime();
		landed = false;
		lookFromDeltas = false;
		lastSent = 0L;
		// V's fall damage is only ignored once the host follows (drive): a refused glide leaves V falling for real
		follow.start(System.nanoTime());
		// yaw in start and end lines: the F4 check (plan retest3 M6) compares them with the DLL's flight summary
		Cyberblock.LOG.info("glide started at {} with {} m/s, yaw {}", player.position(), Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]), player.getYRot());
	}

	/**
	 * Touchdown (or the host went away mid-air): the host hears where, and Steve hangs there again until the host has placed V. A
	 * glide the host never followed has no landing spot for V: it is called off instead.
	 */
	private static void land(final LocalPlayer player, final HostState.@Nullable Pose p) {
		if (!follow.seen()) {
			abort(player, p);
			return;
		}

		driving = false;
		landed = true;
		touchdownNanos = System.nanoTime();
		Vec3 feet = player.position();
		landingSpot = feet;
		boolean launch = mode == Mode.LAUNCH;
		HostLink.send(Wire.flight(false, feet.x, feet.y, feet.z, 0.0, 0.0, 0.0, player.getYRot(), player.getXRot(), launch, clock(touchdownNanos)));
		endGlide(player);
		Cyberblock.LOG.info("{} ended at {}, yaw {}", launch ? "launch" : "glide", feet, player.getYRot());
	}

	/** Minecraft's sample clock (flight.ts): milliseconds since EPOCH. */
	private static double clock(final long nanos) {
		return (nanos - EPOCH) / 1.0E6;
	}

	/**
	 * The host did not take the glide up in time, or stopped following it (V held by the game, refused, left in mid-air): there is
	 * no landing spot to move V to. Steve goes straight back to V, and the host only hears that the flight is over, at V's own
	 * feet, so a host that still follows leaves V where it is.
	 */
	private static void abort(final LocalPlayer player, final HostState.@Nullable Pose p) {
		boolean followed = follow.seen();
		long now = System.nanoTime();
		driving = false;
		landed = false; // followsCam at once
		// a host that does not follow would otherwise get a new glide every second of a long fall
		retryAt = now + FlightRules.RETRY_NANOS;
		Vec3 at = p == null ? player.position() : new Vec3(p.px(), p.py(), p.pz());
		boolean launch = mode == Mode.LAUNCH;
		HostLink.send(Wire.flight(false, at.x, at.y, at.z, 0.0, 0.0, 0.0, player.getYRot(), player.getXRot(), launch, clock(now)));
		endGlide(player);
		player.setPos(at);
		player.xo = player.xOld = at.x;
		player.yo = player.yOld = at.y;
		player.zo = player.zOld = at.z;
		Cyberblock.LOG.info("{} called off ({}), Steve back at {}", launch ? "launch" : "glide",
			followed ? "the host stopped following" : "the host did not follow", at);
	}

	/**
	 * Steve hangs with no gravity again. Vanilla ends a glide only on the ground: in mid-air he would hover gliding. The next flight is
	 * a glide unless a launch starts it.
	 */
	private static void endGlide(final LocalPlayer player) {
		gravity(player, false, true);
		player.stopFallFlying();
		player.setDeltaMovement(Vec3.ZERO);
		pausedAt = pausedVelocity = null;
		fall.reset();
		mode = Mode.GLIDE;
		launching = false;
	}

	/**
	 * Gravity on (gliding) or off (hanging at V, or paused by a menu), on the client and on the integrated server, so neither
	 * resyncs the other. endGlide also ends the server's glide, which otherwise keeps taking elytra durability while Steve hangs
	 * in the air.
	 */
	private static void gravity(final LocalPlayer player, final boolean on, final boolean endGlide) {
		player.setNoGravity(!on);
		IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server != null) {
			server.execute(() -> {
				ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player.getUUID());
				if (serverPlayer != null) {
					serverPlayer.setNoGravity(!on);
					if (endGlide) {
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

	/**
	 * Every frame while flying: steer with the host's look, and tell the host where Steve is drawn (at most 120 times a second), with
	 * the flight's kind and the time of the sample (ts) so the host can play the samples out evenly whatever their arrival jitter.
	 */
	static void frame(final LocalPlayer player, final HostState.Pose p, final float partialTick) {
		float yaw = lookFromDeltas ? lookYaw : p.yaw();
		float pitch = lookFromDeltas ? lookPitch : p.pitch();
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.yRotO = yaw;
		player.xRotO = pitch;
		player.yHeadRot = player.yHeadRotO = yaw;
		long now = System.nanoTime();
		if (pausedAt != null || now - lastSent < FRAME_NANOS) {
			return; // paused by a Cyberpunk menu: the host hears nothing until it is over
		}

		lastSent = now;
		// where the player is drawn this frame and how fast that moves (the slope of the tick interpolation, per second)
		Vec3 at = player.getPosition(partialTick);
		HostLink.send(Wire.flight(true, at.x, at.y, at.z, (player.getX() - player.xo) * 20.0, (player.getY() - player.yo) * 20.0,
			(player.getZ() - player.zo) * 20.0, yaw, pitch, mode == Mode.LAUNCH, clock(now)));
	}
}
