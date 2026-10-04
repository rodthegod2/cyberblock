package dev.cyberblock.cyber;

import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.net.ServerNet;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The cyber track on the multiplayer net (megaplan 2026-10-04 task 1-C1), server thread: the UP messages it handles
 * (`scaninfo`, `hackresult`, `hackspread`, `killed`, `state`) through HostSessions.listen, the resync after each host hello,
 * the player requests of CyberActs (`cyberblock:act`), the answers to them (`cyberblock:ui`), and the DOWN messages that go
 * to a player's own session only (`perks`, `cyberware`, `cyberfx`). Singleplayer goes the same way over the integrated
 * server's in-memory connection.
 */
public final class CyberNet {
	/** A fire key's `aim` farther than this from the player's eyes is dropped (the ray reaches 64 blocks from the camera). */
	private static final double FIRE_AIM_SLACK = CyberwareRules.PLS_RANGE + 16.0;
	private static int bad;

	private CyberNet() {
	}

	/** CyberMod.onInitialize. */
	static void init() {
		listen("scaninfo", (session, player, m) -> {
			ScanInfo info = ScanInfo.parse(m);
			if (info == null) {
				warn("bad scaninfo dropped");
			} else {
				Hacking.onScanInfo(player.level().getServer(), owner(session), info);
			}
		});
		listen("hackresult", (session, player, m) -> {
			HackResult result = HackResult.parse(m);
			if (result == null) {
				warn("bad hackresult dropped");
			} else {
				Hacking.onResult(player.level().getServer(), session.uuid(), result);
			}
		});
		listen("hackspread", (session, player, m) -> {
			HackSpread spread = HackSpread.parse(m);
			if (spread == null) {
				warn("bad hackspread dropped");
			} else {
				HackFx.onSpread(player.level().getServer(), new CyberRoute.Key(session.uuid(), spread.id()), spread);
			}
		});
		listen("killed", (session, player, m) -> {
			Kills.Killed killed = Kills.parse(m);
			if (killed == null) {
				warn("bad killed dropped");
			} else {
				Kills.onKilled(player, killed);
			}
		});
		listen("state", (session, player, m) -> {
			if (!m.has("active") || m.get("active").getAsBoolean()) {
				Kit.onActive(player);
			}
		});
		// after each host hello (megaplan section 1.2 "hello"): Cyberpunk gets this player's perks and implants again
		HostSessions.onStart(session -> {
			ServerPlayer player = session.player();
			if (player != null) {
				PerkShop.sendPerks(player);
				Ripperdoc.sendCyberware(player);
			}
		});
		HostSessions.onEnd(session -> Hacking.ownerEnded(session.uuid()));
		// the net core receives `cyberblock:act`, parses the json and hands each type to its handler (catching what one throws)
		ACTS.forEach((t, handler) -> ServerNet.onAct(t, (player, m) -> {
			if (!handler.test(player, m)) {
				warn("bad act " + t + " dropped");
			}
		}));
	}

	/** An UP listener for a host that is online (HostSession.player is null for a moment around a disconnect). */
	private interface Up {
		void on(HostSession session, ServerPlayer player, JsonObject m);
	}

	private static void listen(final String t, final Up up) {
		HostSessions.listen(t, (session, m) -> {
			ServerPlayer player = session.player();
			if (player != null) {
				up.on(session, player, m);
			}
		});
	}

	/**
	 * The cyber track's `cyberblock:act` requests (CyberActs) by type, server thread: false when the request breaks its rules
	 * (dropped and counted). CyberNetTest checks that every CyberActs type is here.
	 */
	static final Map<String, BiPredicate<ServerPlayer, JsonObject>> ACTS = Map.of(
		CyberActs.SCAN, (player, m) -> {
			CyberActs.Scan scan = CyberActs.parseScan(m);
			if (scan != null) {
				Hacking.scan(player, scan);
			}

			return scan != null;
		},
		CyberActs.HACK, (player, m) -> {
			Hack hack = CyberActs.parseHack(m);
			if (hack != null) {
				Hacking.useChip(player, hack);
			}

			return hack != null;
		},
		CyberActs.PERK, (player, m) -> {
			Perk perk = CyberActs.parsePerk(m);
			if (perk != null) {
				PerkShop.buy(player, perk);
			}

			return perk != null;
		},
		CyberActs.WARE, (player, m) -> {
			CyberActs.Ware ware = CyberActs.parseWare(m);
			if (ware != null && ware.install()) {
				Ripperdoc.install(player, ware.cyberware());
			} else if (ware != null) {
				Ripperdoc.remove(player, ware.slot());
			}

			return ware != null;
		},
		CyberActs.FIRE, (player, m) -> {
			CyberActs.Fire fire = CyberActs.parseFire(m);
			if (fire != null) {
				Vec3 aim = fire.aim() != null && fire.aim().distanceTo(player.getEyePosition()) <= FIRE_AIM_SLACK ? fire.aim() : null;
				CyberwareEffects.fire(player, fire.trigger(), aim);
			}

			return fire != null;
		},
		CyberActs.SANDEVISTAN, (player, m) -> {
			CyberwareEffects.sprintSandevistan(player);
			return true;
		});

	/** `perks`, `cyberware`, `cyberfx`: DOWN to the player's own session only; a guest has none and nothing is sent. */
	static void toOwn(final ServerPlayer player, final String json) {
		CyberRoute.toOwn(own(player), json);
	}

	/** The player's own host session as a route owner, null for a guest. */
	static CyberRoute.@Nullable Owner own(final ServerPlayer player) {
		return owner(HostSessions.of(player));
	}

	static CyberRoute.@Nullable Owner owner(final @Nullable UUID uuid) {
		return uuid == null ? null : owner(HostSessions.of(uuid));
	}

	private static CyberRoute.@Nullable Owner owner(final @Nullable HostSession session) {
		return session == null ? null : new SessionOwner(session);
	}

	/** The player is a host whose Cyberpunk is On (the server-side Cyberblock.active of before). */
	static boolean hostActive(final ServerPlayer player) {
		HostSession session = HostSessions.of(player);
		return session != null && session.active();
	}

	/** V (for an active host) or Steve is in the air. */
	static boolean airborne(final ServerPlayer player) {
		HostSession session = HostSessions.of(player);
		return session != null && session.active() ? session.airborne() : !player.onGround();
	}

	/** V is crouched (an active host's hstate). */
	static boolean crouching(final ServerPlayer player) {
		HostSession session = HostSessions.of(player);
		return session != null && session.active() && session.crouching();
	}

	/** Metres to the Night City surface the player's host aims at, negative when none (or a guest). */
	static double aim(final ServerPlayer player) {
		HostSession session = HostSessions.of(player);
		return session != null && session.active() ? session.aim() : -1.0;
	}

	/** A hostile host could make this a log flood: say it once, then every 100th time. */
	static void warn(final String what) {
		if (bad++ % 100 == 0) {
			Cyberblock.LOG.warn("{} (seen {} times)", what, bad);
		}
	}

	/** A HostSession as the cyber track's route owner. */
	private record SessionOwner(HostSession session) implements CyberRoute.Owner {
		@Override
		public UUID uuid() {
			return this.session.uuid();
		}

		@Override
		public boolean active() {
			return this.session.active();
		}

		@Override
		public boolean allowsHacks(final UUID guest) {
			return Permissions.allows(this.session.uuid(), guest, Permissions.Toggle.HACKS);
		}

		@Override
		public int newRequest(final UUID asker) {
			return this.session.newRequest(asker);
		}

		@Override
		public @Nullable UUID asker(final int id) {
			return this.session.asker(id);
		}

		@Override
		public void send(final String json) {
			this.session.send(json);
		}
	}
}
