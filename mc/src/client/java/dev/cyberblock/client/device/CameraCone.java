package dev.cyberblock.client.device;

import dev.cyberblock.client.replica.ReplicaRender;
import dev.cyberblock.device.block.DeviceBlockEntity;
import dev.cyberblock.device.block.EyeCameraBlock;
import dev.cyberblock.device.block.FacingDeviceBlock;
import dev.cyberblock.device.block.TurretBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The eye camera's vision cone (section 1.11 cameras; Phase 4 in-game check "a camera's cone turns red when it spots the guest"): a
 * client-side cone of dust particles out of the camera's front, {@link #LENGTH} blocks long. A watching camera sweeps slowly and
 * draws amber; an alarmed one turns red and follows the nearest player; a friendly one is green. None while the camera is off, and
 * none on an active host's screen (Night City draws the real camera there).
 */
final class CameraCone {
	static final double LENGTH = 8.0;
	static final double HALF_ANGLE = Math.toRadians(16.0);
	/** Particles per burst, a burst every {@link #EVERY} client ticks. */
	static final int PARTICLES = 7;
	static final int EVERY = 3;
	/** Cameras farther than this from the player draw nothing. */
	static final double VISIBLE = 48.0;
	static final float TILT = 22.0F;
	static final int AMBER = 0xE8C040;
	static final int RED = 0xFF2020;
	static final int GREEN = 0x30E060;

	private CameraCone() {
	}

	/** The camera block entity's client tick. */
	static void tick(final Level level, final BlockPos pos, final BlockState state, final DeviceBlockEntity camera) {
		camera.age++;
		EyeCameraBlock.Mode mode = state.getValue(EyeCameraBlock.MODE);
		Direction facing = state.getValue(FacingDeviceBlock.FACING);
		Vec3 eye = Vec3.atCenterOf(pos).add(facing.getStepX() * 0.55, -0.1, facing.getStepZ() * 0.55);
		AbstractClientPlayer seen = mode == EyeCameraBlock.Mode.ALARM ? nearest(level, eye) : null;
		float yaw = seen == null ? TurretBlock.sweep(facing, camera.age / 2) : TurretBlock.yawToward(seen.getX() - eye.x, seen.getZ() - eye.z);
		float pitch = seen == null ? TILT : TurretBlock.pitchToward(Math.hypot(seen.getX() - eye.x, seen.getZ() - eye.z), seen.getEyeY() - 0.4 - eye.y);
		camera.yaw = camera.age == 1 ? yaw : TurretBlock.approach(camera.yaw, yaw, 6.0F);
		camera.pitch = camera.age == 1 ? pitch : camera.pitch + Mth.clamp(pitch - camera.pitch, -4.0F, 4.0F);
		Minecraft minecraft = Minecraft.getInstance();
		if (mode == EyeCameraBlock.Mode.OFF || ReplicaRender.hiding() || camera.age % EVERY != 0 || minecraft.player == null
			|| minecraft.player.distanceToSqr(eye) > VISIBLE * VISIBLE) {
			return;
		}

		DustParticleOptions dust = new DustParticleOptions(colour(mode), 0.7F);
		Vec3 axis = Vec3.directionFromRotation(camera.pitch, camera.yaw).normalize();
		Vec3 u = axis.cross(new Vec3(0.0, 1.0, 0.0)).normalize();
		Vec3 v = u.cross(axis).normalize();
		RandomSource random = level.getRandom();
		for (int i = 0; i < PARTICLES; i++) {
			Vec3 p = point(eye, axis, u, v, 0.1 + 0.9 * random.nextDouble(), random.nextDouble() * Mth.TWO_PI, random.nextDouble() < 0.75);
			level.addParticle(dust, p.x, p.y, p.z, 0.0, 0.0, 0.0);
		}
	}

	/** A point of the cone: {@code t} 0..1 along its length, at angle {@code around}, on the rim ({@code edge}) or inside it. Pure. */
	static Vec3 point(final Vec3 eye, final Vec3 axis, final Vec3 u, final Vec3 v, final double t, final double around, final boolean edge) {
		double along = t * LENGTH;
		double radius = along * Math.tan(HALF_ANGLE) * (edge ? 1.0 : Math.sqrt(t));
		return eye.add(axis.scale(along)).add(u.scale(Math.cos(around) * radius)).add(v.scale(Math.sin(around) * radius));
	}

	static int colour(final EyeCameraBlock.Mode mode) {
		return switch (mode) {
			case ALARM -> RED;
			case FRIENDLY -> GREEN;
			default -> AMBER;
		};
	}

	/** The nearest player (not a spectator) within the cone's length, or null. */
	static @Nullable AbstractClientPlayer nearest(final Level level, final Vec3 from) {
		AbstractClientPlayer best = null;
		double bestDistance = LENGTH * LENGTH * 4.0;
		for (var player : level.players()) {
			if (player instanceof AbstractClientPlayer p && !p.isSpectator()) {
				double d = p.distanceToSqr(from);
				if (d < bestDistance) {
					bestDistance = d;
					best = p;
				}
			}
		}

		return best;
	}
}
