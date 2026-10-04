package dev.cyberblock.client.settings;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.HostState;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /cyberblock debug aim on} (plan Q-B1): one {@code aim:} line per client tick with what the pick found, its distance from
 * the camera, the limit it used, the host's raw aim distance, the attack key and the destroy progress (MultiPlayerGameMode's
 * public isDestroying() and getDestroyStage(), 0..9, -1 for none).
 */
public final class AimDebug {
	private AimDebug() {
	}

	/** Client thread, at the end of each client tick. */
	public static void tick(final Minecraft minecraft) {
		if (!DebugFlags.aim() || minecraft.player == null || HostState.frame() == null) {
			return;
		}

		Cyberblock.LOG.info(line(minecraft));
	}

	private static String line(final Minecraft minecraft) {
		HitResult hit = minecraft.hitResult;
		String target = "none";
		String at = "";
		double d = -1.0;
		if (hit != null && hit.getType() != HitResult.Type.MISS) {
			Vec3 camera = minecraft.gameRenderer.mainCamera().position();
			d = hit.getLocation().distanceTo(camera);
			if (hit instanceof BlockHitResult block) {
				BlockPos pos = block.getBlockPos();
				target = "block";
				at = String.format(Locale.ROOT, " pos=%d %d %d %s", pos.getX(), pos.getY(), pos.getZ(), minecraft.level.getBlockState(pos).getBlock().getDescriptionId());
			} else {
				target = "entity";
			}
		}

		MultiPlayerGameMode mode = minecraft.gameMode;
		boolean destroying = mode != null && mode.isDestroying();
		int stage = mode == null ? -1 : mode.getDestroyStage();
		return String.format(Locale.ROOT, "aim: target %s d=%.3f limit=%.3f%s raw=%.3f attack=%s destroying=%s stage=%d", target, d, HostState.lastLimit(),
			at, HostState.aimRaw(), minecraft.options.keyAttack.isDown() ? "down" : "up", destroying, stage);
	}
}
