package dev.cyberblock;

import com.google.gson.JsonObject;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.replica.ReplicaModule;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntBiFunction;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * The world track on per-host sessions (plan 2026-10-04 task 1-W1): registers its server events, its UP handlers
 * (HostSessions.listen), the session end cleanup and /cyberblock level, and holds the guest toggle gate. Cyberblock.onInitialize
 * calls init() once.
 *
 * <p>hstate, state, aim and stats are not listened to here: the session (package 1N) keeps their fields and the world track reads
 * them every tick (HostSession.active, held, vehicle, stands, feetCpZ, flying, launching), so no handler depends on listener order.
 */
public final class WorldModule {
	/**
	 * The acting player's perk rank by perk id, 0 when not owned (plan section 1.5). Per player, never the server's first player:
	 * a guest's punch or TNT gets the guest's own perks. init() points it at the cyber track's CyberData; tests may replace it.
	 */
	static volatile ToIntBiFunction<Player, String> perkRank = (player, id) -> 0;
	/** The player has this implant installed (plan retest2 section 1.7 ids). init() points it at CyberData; tests may replace it. */
	static volatile BiPredicate<Player, String> hasCyberware = (player, id) -> false;
	/**
	 * Steve is launched (plan retest2 R5) on this client: Minecraft's physics move him. The client entrypoint installs it (its Flight);
	 * the server reads the session's hstate instead (launching(Player)).
	 */
	public static volatile Predicate<Player> clientLaunching = p -> false;
	private static int bad;

	private WorldModule() {
	}

	public static void init() {
		ProxyEntities.register();
		ReplicaModule.init(); // the replica blocks, material_palette, the shared Night City map, the Offline Builds log (megaplan 3R)
		// the cyber track keeps perks and implants on each player (Fabric attachments); read lazily, at game time
		perkRank = CyberData::rank;
		hasCyberware = (player, id) -> CyberData.slots(player).containsValue(id);
		Bridge.proxyById = ProxyManager::byId;
		Bridge.proxyOwnerOf = ProxyManager::ownerOf;
		Bridge.proxyIdOf = ProxyManager::idOf;
		Bridge.hostileProxy = e -> e instanceof ProxyEntity p && p.hostile();
		Bridge.proxyAimedAt = ProxyManager::aimedAt;
		// Compass Ping (plan retest3 section 1.5)
		Bridge.pingedProxy = e -> e instanceof ProxyEntity p && p.pinged();
		Bridge.pingProxy = (e, ticks) -> {
			if (e instanceof ProxyEntity p) {
				p.setPinged(ticks);
			}
		};
		ServerLifecycleEvents.SERVER_STARTED.register(WorldBridge::attach);
		ServerLifecycleEvents.SERVER_STARTED.register(OutlineRules::attach);
		// before the world is saved, so Night City's barriers and the proxies are never kept in the save
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			ProxyManager.clear();
			MobWar.clear();
			WorldBridge.detach();
			OutlineRules.detach();
			SafeSpots.clear();
		});
		// outline blocks: survival may not break them, creative breaks come back (plan Q-B4); server side only
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
			!(level instanceof ServerLevel server && player instanceof ServerPlayer breaker) || WorldBridge.mayBreak(server, breaker, pos, state));
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level instanceof ServerLevel server && player instanceof ServerPlayer breaker) {
				WorldBridge.broken(server, breaker, pos, state);
			}
		});
		ServerEntityEvents.ENTITY_LOAD.register(MobWar::onEntityLoad);
		// the resync (align, blocks) runs before PlayerEffects in the same tick: one tick, one queue (plan section 1.3)
		ServerTickEvents.END_SERVER_TICK.register(WorldBridge::tick);
		ServerTickEvents.END_SERVER_TICK.register(ProxyManager::tick);
		ServerTickEvents.END_SERVER_TICK.register(MobWar::tick);
		ServerTickEvents.END_SERVER_TICK.register(Projectiles::tick);
		ServerTickEvents.END_SERVER_TICK.register(PlayerEffects::tick);
		ServerTickEvents.END_SERVER_TICK.register(HostDamage::tick);
		ServerTickEvents.END_SERVER_TICK.register(SafeSpots::tick);

		// UP messages of section 1.2, on the server thread in seq order
		listen("hello", (s, m) -> {
			WorldBridge.hello(s);
			PlayerEffects.hello(s);
		});
		listen("ground", WorldBridge::solid);
		listen("unground", WorldBridge::unsolid);
		listen("clear", (s, m) -> WorldBridge.clearSolid(s));
		listen("npcs", ProxyManager::update);
		listen("dmg", HostDamage::update);
		listen("mobdmg", MobWar::damage);
		listen("unlead", ProxyManager::unlead);
		listen("pcells", (s, m) -> {
			int[] cells = Projectiles.cells(m);
			if (cells == null) {
				warn("ignoring a bad pcells message");
			} else {
				WorldBridge.projectileCells(s, cells);
			}
		});
		HostSessions.onEnd(WorldModule::end);

		// /cyberblock level, for everyone (permission level 0; Brigadier merges it with the other /cyberblock literals)
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
			Commands.literal("cyberblock").then(Commands.literal("level").executes(command -> level(command.getSource())))));
	}

	/** A handler that throws is logged (at most every 100th time), never fatal: a bad message never ends a session. */
	private static void listen(final String t, final BiConsumer<HostSession, JsonObject> handler) {
		HostSessions.listen(t, (s, m) -> {
			try {
				handler.accept(s, m);
			} catch (RuntimeException e) {
				warn("bad " + t + " message: " + e);
			}
		});
	}

	/** Server thread: the session ended (disconnect, link lost, role revoked, server stop): nothing of its Night City stays. */
	static void end(final HostSession s) {
		MobWar.end(s.uuid());
		ProxyManager.end(s.uuid());
		WorldBridge.end(s.uuid());
		Projectiles.end(s.uuid());
		PlayerEffects.end(s.uuid());
		HostDamage.end(s.uuid());
	}

	/**
	 * Plan section 1.6: whether an action reaches the owner's Cyberpunk game. The owner's own actions and those with no player behind
	 * them (a mob, a lone creeper, lava) always do; a guest's only with the owner's toggle on.
	 */
	static boolean allows(final UUID owner, final @Nullable Entity actor, final Permissions.Toggle toggle) {
		UUID guest = actor instanceof Player player ? player.getUUID() : null;
		return !byGuest(owner, guest) || Permissions.allows(owner, guest, toggle);
	}

	/** The actor is a guest of this owner: a player, and not the owner. */
	static boolean byGuest(final UUID owner, final @Nullable UUID actor) {
		return actor != null && !actor.equals(owner);
	}

	/** A guest's action did not reach a host's game (it still happened in Minecraft): the guest is told on the action bar. */
	static void blocked(final @Nullable Entity actor) {
		if (actor instanceof ServerPlayer player) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.guest.blocked", "That host does not let guests do this to their Night City"));
		}
	}

	/** The session of a server player when it is active, else null. */
	static @Nullable HostSession activeSession(final @Nullable Entity entity) {
		HostSession s = entity instanceof ServerPlayer player ? HostSessions.of(player) : null;
		return s != null && s.active() ? s : null;
	}

	/** Both sides (PlayerMixin): Steve is launched. The server asks the player's session, a client its own Flight. */
	public static boolean launching(final Player player) {
		if (player instanceof ServerPlayer server) {
			HostSession s = HostSessions.of(server);
			return s != null && s.launching();
		}

		return clientLaunching.test(player);
	}

	/** /cyberblock level (plan retest2 section 1.9): re-aligns the world's ground to the floor the issuing host's V stands on. */
	private static int level(final CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		HostSession s = player != null ? HostSessions.of(player) : HostSessions.primary();
		String refused = WorldBridge.levelRefused(s);
		if (refused != null) {
			source.sendFailure(Component.translatable(refused));
			return 0;
		}

		int was = WorldBridge.level(s, "/cyberblock level by " + source.getTextName());
		String now = metres(WorldBridge.k()), before = metres(Math.max(was, 0));
		source.sendSuccess(() -> Component.translatable("cyberblock.level.done", now, before), false);
		return 1;
	}

	private static String metres(final int k) {
		return String.format(Locale.ROOT, "%.3f", GroundAlign.offset(k));
	}

	/** A hostile host could make this a log flood: say it once, then every 100th time. */
	static void warn(final String what) {
		if (bad++ % 100 == 0) {
			Cyberblock.LOG.warn("{} (seen {} times)", what, bad);
		}
	}
}
