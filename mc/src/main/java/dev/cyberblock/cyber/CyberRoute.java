package dev.cyberblock.cyber;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Where the cyber track's messages go on a multiplayer server (megaplan 2026-10-04 task 1-C1, sections 1.2 and 1.4): a scan or
 * a quickhack goes DOWN to the owner of the target (a guest's request too, when the owner's `hacks` toggle allows it), with a
 * request id that owner's session issued; `perks`, `cyberware` and `cyberfx` go only to the player's own session. Pure (the
 * owners are views of HostSession, CyberNet adapts them), so the routing is unit-tested without a server.
 */
public final class CyberRoute {
	/** What the cyber track needs of a host session (net.HostSession, adapted by CyberNet). */
	public interface Owner {
		UUID uuid();

		/** Cyberpunk is On and streaming (hstate.active). */
		boolean active();

		/** The host lets this guest scan and hack its game (its `hacks` toggle, megaplan section 1.6). */
		boolean allowsHacks(UUID guest);

		/** A request id that routes the answer back to the asker (HostSession.newRequest). */
		int newRequest(UUID asker);

		/** Who asked request `id`, null when this session never issued it or forgot it (HostSession.asker). */
		@Nullable UUID asker(int id);

		/** DOWN to this host's Cyberpunk (HostSession.send). */
		void send(String json);
	}

	/** Why a request was not sent. */
	public enum Refusal {
		NONE,
		/** Nothing under the ray and the asker has no Cyberpunk game of his own (`target:null` is only for the host's own game). */
		NO_HOST,
		/** The owner does not let guests hack its game. */
		BLOCKED
	}

	/** A request id is only meaningful together with the session that issued it. */
	public record Key(UUID owner, int id) {
	}

	/** The outcome of a routed request: where it went and under which id, or why it did not go. */
	public record Sent(@Nullable Owner owner, int id, Refusal refusal) {
		public boolean ok() {
			return this.refusal == Refusal.NONE;
		}

		public @Nullable Key key() {
			return this.owner == null || !this.ok() ? null : new Key(this.owner.uuid(), this.id);
		}

		static Sent refused(final Refusal refusal) {
			return new Sent(null, -1, refusal);
		}
	}

	private CyberRoute() {
	}

	/** May `actor` scan or hack this owner's game? The host itself always may; a guest needs the `hacks` toggle. */
	public static Refusal check(final Owner owner, final UUID actor) {
		return owner.uuid().equals(actor) || owner.allowsHacks(actor) ? Refusal.NONE : Refusal.BLOCKED;
	}

	/**
	 * A scan: `picked` is the owner of the proxy under the ray (null: none) and `target` its Cyberpunk id; `own` is the asker's
	 * own session (null for a guest). With nothing picked the scan goes to the asker's own game with `target:null`, so
	 * Cyberpunk looks at whatever V aims at.
	 */
	public static Sent scan(final UUID asker, final @Nullable Owner picked, final long target, final @Nullable Owner own) {
		Owner owner = picked != null ? picked : own;
		if (owner == null) {
			return Sent.refused(Refusal.NO_HOST);
		}

		Refusal refusal = check(owner, asker);
		if (refusal != Refusal.NONE) {
			return Sent.refused(refusal);
		}

		int id = owner.newRequest(asker);
		owner.send(CyberWire.scan(id, picked != null ? target : 0L));
		return new Sent(owner, id, Refusal.NONE);
	}

	/** A quickhack at the locked target of `owner`'s game. */
	public static Sent hack(final UUID asker, final Owner owner, final long target, final Hack hack, final boolean chain) {
		Refusal refusal = check(owner, asker);
		if (refusal != Refusal.NONE) {
			return Sent.refused(refusal);
		}

		int id = owner.newRequest(asker);
		owner.send(CyberWire.hack(id, target, hack, chain));
		return new Sent(owner, id, Refusal.NONE);
	}

	/** `perks`, `cyberware`, `cyberfx`: only to the player's own session (guests have none; their perks stay Minecraft-side). */
	public static boolean toOwn(final @Nullable Owner own, final String json) {
		if (own == null) {
			return false;
		}

		own.send(json);
		return true;
	}
}
