// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client;

import dev.cyberblock.Bridge;
import dev.cyberblock.client.view.ViewInterp;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the Minecraft player on the host's player: it stands where they stand and looks where the host camera looks. While
 * Steve glides (Flight) Minecraft moves him instead, and the host follows.
 */
public final class PlayerSync {
	private static final double TELEPORT_SQ = 64.0 * 64.0;
	/** How far the host's player moved over the last client tick (drives the walk animation). */
	private static float tickDistance;
	private static double lastX = Double.NaN, lastZ;

	private PlayerSync() {
	}

	public static float tickDistance() {
		return tickDistance;
	}

	/** Every frame, before the camera update: position, rotation, and first/third person to match the host. */
	public static void frame(final float partialTick) {
		HostState.Pose p = HostState.frame();
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (p == null || player == null) {
			return;
		}

		CameraType cameraType = p.firstPerson() ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK;
		if (minecraft.options.getCameraType() != cameraType) {
			minecraft.options.setCameraType(cameraType);
		}

		Vec3 feet = anchor(p, player.getEyeHeight());
		INTERP.sample(p.receivedNanos(), feet.x, feet.y, feet.z);
		if (Flight.driving()) {
			Flight.frame(player, p, partialTick);
			return;
		}

		float pitch = pitch(p);
		player.setYRot(p.yaw());
		player.setXRot(pitch);
		player.yRotO = p.yaw();
		player.xRotO = pitch;
		player.yHeadRot = player.yHeadRotO = headYaw(p);
		player.yBodyRot = player.yBodyRotO = bodyYaw(p);
		if (!Flight.followsCam()) {
			return; // just landed: Steve waits at the landing spot until the host has placed V there
		}

		// the model stands exactly where the host's player is this frame (not a tick behind, interpolating); the next tick sets the
		// position the server hears from the newest pose again
		player.setPos(feet.x, feet.y, feet.z);
		player.xo = player.xOld = feet.x;
		player.yo = player.yOld = feet.y;
		player.zo = player.zOld = feet.z;
	}

	/** The host's poses as they arrive (Q-V1). Render thread. */
	private static final ViewInterp INTERP = new ViewInterp();

	/**
	 * Q-V1: where third-person Steve is drawn this frame, {x, y, z} (AvatarRendererMixin, every frame, gliding and launched too):
	 * at the feet (or seat) of the pose the frame is rendered and exported with, whatever the tick or Minecraft's physics have.
	 * The compositor re-projects the frame by that pose's own camera and moves Steve's capsule by V's motion since then
	 * (Cyberblock.fx cb_body_move), so that pose's time is the one he is drawn for (see ViewInterp). Null without a host.
	 */
	public static double @Nullable [] renderFeet() {
		HostState.Pose p = HostState.frame();
		return p == null ? null : INTERP.at(p.receivedNanos());
	}

	/** The interpolation factor Steve is drawn with this frame (1: exactly the frame's pose), for the view debug overlay. */
	public static double renderFactor() {
		HostState.Pose p = HostState.frame();
		return p == null ? Double.NaN : INTERP.factor(p.receivedNanos());
	}

	/** How far below V's hips (cam.pl while seated) Steve's feet go, so his riding pose sits on V's seat (plan retest3 M2). */
	static final double SEAT_DROP = 0.70;

	/**
	 * Where Steve's feet go: seated in a vehicle (cam.seat) SEAT_DROP below V's hips whatever the camera; otherwise his eyes at the
	 * host camera in first person, his feet at V's in third person.
	 */
	static Vec3 anchor(final HostState.Pose p, final double eyeHeight) {
		if (p.seat()) {
			return new Vec3(p.px(), p.py() - SEAT_DROP, p.pz());
		}

		return p.firstPerson() ? new Vec3(p.x(), p.y() - eyeHeight, p.z()) : new Vec3(p.px(), p.py(), p.pz());
	}

	/** Steve's body: V's in a seat and in third person; in first person it turns with the camera. */
	static float bodyYaw(final HostState.Pose p) {
		return p.seat() || !p.firstPerson() ? p.bodyYaw() : p.yaw();
	}

	/** Steve's head (plan retest3 M2): the camera's yaw in first person, the body's in third person (an orbiting camera is not his gaze). */
	static float headYaw(final HostState.Pose p) {
		return p.firstPerson() ? p.yaw() : p.bodyYaw();
	}

	/** Steve's pitch: the camera's, except seated under a third-person vehicle camera, where he looks straight ahead. */
	static float pitch(final HostState.Pose p) {
		return p.seat() && !p.firstPerson() ? 0.0F : p.pitch();
	}

	/**
	 * Every client tick, at the start of the player's tick (the old position is already saved, so the model
	 * interpolates and walks): in first person the player's eyes are at the host camera, in third person
	 * their feet are at the host player's.
	 */
	public static void tick(final LocalPlayer player) {
		Flight.tick(player);
		HostState.Pose p = HostState.live();
		if (p == null || !Flight.followsCam()) {
			return;
		}

		Vec3 at = anchor(p, player.getEyeHeight());
		double x = at.x, y = at.y, z = at.z;
		tickDistance = Double.isNaN(lastX) ? 0.0F : (float) Math.min(Math.hypot(x - lastX, z - lastZ), 1.0);
		lastX = x;
		lastZ = z;
		boolean teleport = player.distanceToSqr(x, y, z) > TELEPORT_SQ;
		player.setPos(x, y, z);
		if (teleport) {
			player.xo = player.xOld = x;
			player.yo = player.yOld = y;
			player.zo = player.zOld = z;
		}

		player.setDeltaMovement(Vec3.ZERO);
		Abilities abilities = player.getAbilities();
		// creative flies, except while V crouches: a flying player never takes the crouch pose (he has no gravity either way)
		boolean fly = !Bridge.hostCrouching || player.isSpectator();
		if (abilities.mayfly && abilities.flying != fly) {
			abilities.flying = fly;
			player.onUpdateAbilities();
		}

		// Steve has no physics, so nothing else tells Minecraft that he stands: V's ground is his (cam.air). Off the ground he mines
		// 5 times slower and every hit can crit; the server takes a stale "on ground" for standing on air. A flying player is never
		// on the ground (vanilla's aiStep would stop the creative flight every tick).
		player.setOnGround(!p.air() && !abilities.flying);
	}
}
