package dev.cyberblock;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Hooks between the Minecraft world track (it sets the proxy hooks in WorldModule) and the cyber track (C). Plan 2026-10-03 section
 * 1.6 with the re-test 2 and re-test 3 additions, as megaplan section 1.4 left it: the JVM-shared host fields (hostAirborne,
 * hostCrouching, hostHeld, hostInVehicle, hostFeetCpZ, hostStands, hostJumpPresses), the listeners (listen/dispatch),
 * installedCyberware and surveyCommand are gone. Server listeners use HostSessions.listen and read the HostSession; client listeners
 * use HostLink.listen; installed cyberware is per player (CyberData). Night City ids are scoped by their owner (the host whose game
 * they belong to): two hosts never collide on an id.
 */
public final class Bridge {
	/** Scan pick from authoritative proxy positions (plan retest2 R2). M sets it (ProxyManager::aimedAt); call on the server thread only. */
	@FunctionalInterface
	public interface ProxyAimer {
		@Nullable Entity at(Vec3 eye, Vec3 dir, double reach);
	}

	/** The live proxy entity for one owner's Night City id (megaplan section 1.4). */
	@FunctionalInterface
	public interface ProxyLookup {
		@Nullable Entity at(UUID owner, long id);
	}

	public static volatile ProxyAimer proxyAimedAt = (eye, dir, reach) -> null;
	/** The proxy stands for a hostile NPC (synced entity data, so it is valid on the client). M sets it. */
	public static volatile Predicate<Entity> hostileProxy = e -> false;
	/** Ground alignment k of the attached world (0 without one). M sets it. */
	public static volatile int alignK;
	/** The proxy is pinged (Compass Ping): synced entity data, valid on the client. M sets it. */
	public static volatile Predicate<Entity> pingedProxy = e -> false;
	/** Marks a proxy pinged for `ticks` (server thread; 0 clears). M sets it. */
	public static volatile ObjIntConsumer<Entity> pingProxy = (e, ticks) -> {};
	/** Perk rank (0 = not owned) of the local player by perk id (plan section 1.5). C sets it; M reads it on the server thread. */
	public static volatile ToIntFunction<String> perkRank = id -> 0;
	/** The live proxy entity for (owner, Cyberpunk id), or null. M sets it; server thread only. */
	public static volatile ProxyLookup proxyById = (owner, id) -> null;
	/** The Cyberpunk id a proxy stands for, 0 when the entity is not a proxy. M sets it; server thread only. */
	public static volatile ToLongFunction<Entity> proxyIdOf = e -> 0L;
	/** The host (owner UUID) whose game a proxy belongs to, null when the entity is not a proxy. M sets it; server thread only. */
	public static volatile Function<Entity, @Nullable UUID> proxyOwnerOf = e -> null;

	private Bridge() {
	}
}
