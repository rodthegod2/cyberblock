package dev.cyberblock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

/**
 * Layer 4: each host's Steve's projectiles in flight ({"t":"proj"}, 10 Hz, to that host), so the host can find the Night City surfaces
 * on their paths and answer with cells to make solid for a moment ({"t":"pcells"}, WorldBridge): pearls, arrows and tridents, rockets,
 * thrown potions, wind charges and fishing hooks then hit Night City. Server thread only.
 */
public final class Projectiles {
	static final int MAX = 16;
	/** The protocol's limit for proj.g (m/s^2). */
	static final double MAX_GRAVITY = 40.0;
	private static final EntityTypeTest<Entity, Projectile> PROJECTILES = EntityTypeTest.forClass(Projectile.class);
	private static int ticks;
	/** Hosts whose last list had something in it: one empty list follows when the last one is gone. */
	private static final Set<UUID> reported = new HashSet<>();

	/** One projectile: entity id, position, velocity (m/s) and gravity (m/s^2, down). */
	public record Flying(int id, double x, double y, double z, double vx, double vy, double vz, double g, double distanceSq) {
	}

	private Projectiles() {
	}

	/** A projectile class's gravity (blocks per tick squared) in m/s^2: 20 ticks a second, squared. Within 0..40. */
	public static double gravity(final double blocksPerTick2) {
		return blocksPerTick2 > 0.0 ? Math.min(blocksPerTick2 * 400.0, MAX_GRAVITY) : 0.0;
	}

	/** The kinds the host traces. */
	static boolean tracked(final Projectile p) {
		return p instanceof ThrownEnderpearl || p instanceof AbstractArrow || p instanceof FireworkRocketEntity || p instanceof AbstractThrownPotion
			|| p instanceof AbstractWindCharge || p instanceof FishingHook;
	}

	/**
	 * The cells of {"t":"pcells","c":[x,y,z, ...]} (at most 64), or null when there is no such list or it is too long. A triple
	 * that is not three whole numbers within +-3e7 is skipped; a trailing partial one too.
	 */
	public static int[] cells(final JsonObject m) {
		if (!(m.get("c") instanceof JsonArray c) || c.size() / 3 > MAX_CELLS) {
			return null;
		}

		IntArrayList out = new IntArrayList();
		for (int i = 0; i + 2 < c.size(); i += 3) {
			int x = whole(c.get(i)), y = whole(c.get(i + 1)), z = whole(c.get(i + 2));
			if (x != BAD && y != BAD && z != BAD) {
				out.add(x);
				out.add(y);
				out.add(z);
			}
		}

		return out.toIntArray();
	}

	private static final int MAX_CELLS = 64;
	private static final int BAD = Integer.MIN_VALUE;

	private static int whole(final JsonElement e) {
		double v = e instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : Double.NaN;
		return Math.abs(v) < 3.0E7 && v == Math.floor(v) ? (int) v : BAD;
	}

	/** At most MAX, nearest to Steve first. */
	public static List<Flying> nearest(final List<Flying> all) {
		List<Flying> sorted = new ArrayList<>(all);
		sorted.sort(Comparator.comparingDouble(Flying::distanceSq));
		return sorted.size() > MAX ? List.copyOf(sorted.subList(0, MAX)) : sorted;
	}

	/** Every server tick: every other tick, each active host gets its own Steve's projectiles. */
	static void tick(final MinecraftServer s) {
		if (++ticks % 2 != 0) {
			return;
		}

		ServerLevel level = s.overworld();
		Map<HostSession, List<Flying>> flying = new HashMap<>();
		// a projectile at rest (an arrow stuck in a wall, a hook hanging still) has nowhere to go
		for (Projectile p : level.getEntities(PROJECTILES, p -> p.isAlive() && tracked(p) && p.getDeltaMovement().lengthSqr() > 1.0E-8)) {
			HostSession host = WorldModule.activeSession(p.getOwner());
			if (host != null) {
				ServerPlayer steve = host.player();
				Vec3 v = p.getDeltaMovement().scale(20.0);
				flying.computeIfAbsent(host, h -> new ArrayList<>())
					.add(new Flying(p.getId(), p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, gravity(p.getGravity()), p.distanceToSqr(steve)));
			}
		}

		for (HostSession host : HostSessions.active()) {
			List<Flying> mine = flying.getOrDefault(host, List.of());
			if (!mine.isEmpty() || reported.contains(host.uuid())) {
				host.send(Wire.proj(nearest(mine)));
			}

			if (mine.isEmpty()) {
				reported.remove(host.uuid());
			} else {
				reported.add(host.uuid());
			}
		}
	}

	/** The session ended. */
	static void end(final UUID owner) {
		reported.remove(owner);
	}
}
