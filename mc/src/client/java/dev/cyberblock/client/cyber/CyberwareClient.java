package dev.cyberblock.client.cyber;

import dev.cyberblock.Bridge;
import dev.cyberblock.client.HostState;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Cyberware;
import dev.cyberblock.cyber.CyberwareRules;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;

/**
 * The client side of the implants (plan retest2 section 1.7), every client tick. The legs implants' particles and sounds
 * follow V's own jumps (Cyberpunk does the jump, Steve follows V): reinforced tendons on a jump pressed while V is already in
 * the air, fortified ankles on a charged jump (jump held on the ground at least 10 ticks, V off the ground at most 4 ticks
 * after the release). Kiroshi optics outline hostile NPCs' proxies and hostile mobs within 32 blocks, and every proxy a Compass
 * Ping marked gets a gold outline (per-tick gizmos, drawn where they stand in Night City).
 */
final class CyberwareClient {
	private static final double KIROSHI_RANGE = 32.0;
	private static final int OUTLINE_STROKE = 0xC0FF2B45;
	private static final int OUTLINE_FILL = 0x10FF2B45;
	/** Compass Ping (plan retest3 C3): pinged proxies get a gold outline, seen through walls, within this many blocks. */
	private static final double PING_RANGE = 96.0;
	private static final int PING_STROKE = 0xF0FFD34A;
	private static final int PING_FILL = 0x18FFD34A;
	/** The host's jump key presses (CyberLink, link thread; megaplan task 1-C1 replaces Bridge.hostJumpPresses), and as last seen. */
	private static final AtomicInteger jumpPresses = new AtomicInteger();
	private static volatile boolean jumpHeld;
	private static int lastJumps;
	private static boolean wasAirborne;
	/** Ticks the host's jump key has been held with V on the ground; at the release, how long it was. */
	private static int heldTicks;
	private static int heldAtRelease;
	/** Ticks since the jump key was let go after a hold, -1 when no charged jump can follow. */
	private static int sinceRelease = -1;

	private CyberwareClient() {
	}

	/** {"t":"key","k":"jump"} from the host (link thread): a press counts once, however often its key-down repeats. */
	static void hostJump(final boolean down) {
		if (down && !jumpHeld) {
			jumpPresses.incrementAndGet();
		}

		jumpHeld = down;
	}

	static void tick(final Minecraft minecraft) {
		int jumps = jumpPresses.get();
		boolean pressed = jumps != lastJumps;
		lastJumps = jumps;
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) {
			return;
		}

		HostState.Pose pose = HostState.frame();
		if (pose != null) {
			legs(level, player, pressed, pose.air());
		}

		for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(PING_RANGE), e -> !e.isRemoved() && Bridge.pingedProxy.test(e))) {
			Gizmos.cuboid(e.getBoundingBox().inflate(0.1), GizmoStyle.strokeAndFill(PING_STROKE, 3.0F, PING_FILL));
		}

		if (CyberData.has(player, Cyberware.KIROSHI_OPTICS)) {
			for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(KIROSHI_RANGE),
				e -> !e.isRemoved() && (e instanceof Enemy || Bridge.hostileProxy.test(e)) && e.distanceToSqr(player) <= KIROSHI_RANGE * KIROSHI_RANGE)) {
				Gizmos.cuboid(e.getBoundingBox().inflate(0.05), GizmoStyle.strokeAndFill(OUTLINE_STROKE, 2.0F, OUTLINE_FILL));
			}
		}
	}

	private static void legs(final ClientLevel level, final LocalPlayer player, final boolean pressed, final boolean airborne) {
		if (pressed && wasAirborne && CyberData.has(player, Cyberware.REINFORCED_TENDONS)) {
			burst(level, player, ParticleTypes.CLOUD, 8, ParticleTypes.SMALL_GUST, SoundEvents.BREEZE_JUMP);
		}

		boolean jumpDown = HostState.jumpDown();
		if (jumpDown && !airborne) {
			heldTicks++;
		} else if (!jumpDown && heldTicks > 0) {
			heldAtRelease = heldTicks;
			heldTicks = 0;
			sinceRelease = 0;
		} else {
			heldTicks = 0;
		}

		if (sinceRelease >= 0) {
			if (airborne && !wasAirborne && CyberwareRules.chargeJump(heldAtRelease, sinceRelease)) {
				if (CyberData.has(player, Cyberware.FORTIFIED_ANKLES)) {
					burst(level, player, ParticleTypes.DUST_PLUME, 12, ParticleTypes.GUST_EMITTER_SMALL, SoundEvents.BREEZE_CHARGE);
				}

				sinceRelease = -1;
			} else if (++sinceRelease > CyberwareRules.CHARGE_TAKEOFF_TICKS) {
				sinceRelease = -1;
			}
		}

		wasAirborne = airborne;
	}

	/** A ring of `count` particles at Steve's feet, one `centre` particle, and a sound. */
	private static void burst(final ClientLevel level, final LocalPlayer player, final SimpleParticleType ring, final int count,
		final SimpleParticleType centre, final SoundEvent sound) {
		for (int i = 0; i < count; i++) {
			double a = Math.PI * 2.0 * i / count;
			level.addParticle(ring, player.getX() + Math.cos(a) * 0.6, player.getY() + 0.1, player.getZ() + Math.sin(a) * 0.6,
				Math.cos(a) * 0.08, 0.02, Math.sin(a) * 0.08);
		}

		level.addParticle(centre, player.getX(), player.getY() + 0.1, player.getZ(), 0.0, 0.0, 0.0);
		level.playLocalSound(player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS, 0.8F, 1.0F, false);
	}
}
