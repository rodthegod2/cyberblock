// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client;

import dev.cyberblock.Bridge;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.phys.Vec3;

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

		if (Flight.driving()) {
			Flight.frame(player, p, partialTick);
			return;
		}

		player.setYRot(p.yaw());
		player.setXRot(p.pitch());
		player.yRotO = p.yaw();
		player.xRotO = p.pitch();
		player.yHeadRot = player.yHeadRotO = p.yaw();
		player.yBodyRot = player.yBodyRotO = p.firstPerson() ? p.yaw() : p.bodyYaw();
		if (!Flight.followsCam()) {
			return; // just landed: Steve waits at the landing spot until the host has placed V there
		}

		// the model stands exactly where the host's player is this frame (not a tick behind, interpolating)
		double x = p.firstPerson() ? p.x() : p.px();
		double y = p.firstPerson() ? p.y() - player.getEyeHeight() : p.py();
		double z = p.firstPerson() ? p.z() : p.pz();
		player.setPos(x, y, z);
		player.xo = player.xOld = x;
		player.yo = player.yOld = y;
		player.zo = player.zOld = z;
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

		double x = p.firstPerson() ? p.x() : p.px();
		double y = p.firstPerson() ? p.y() - player.getEyeHeight() : p.py();
		double z = p.firstPerson() ? p.z() : p.pz();
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
