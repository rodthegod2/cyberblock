package dev.cyberblock;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One host's proxies (plan 2026-10-04 section 1.4): a Night City id only means something together with its owner, the host whose
 * Cyberpunk game it belongs to, so two hosts can never collide on one. P is the proxy (ProxyEntity in the game, anything in tests).
 * Server thread only.
 */
final class ProxyFleet<P> {
	final Map<Long, P> proxies = new HashMap<>();
	final Map<Long, ProxyDiff.Kind> kinds = new HashMap<>();
	/** Where each proxy was last put and how fast it was going: it runs on at that speed until the next list. */
	final Map<Long, ProxyDiff.Motion> motions = new HashMap<>();

	/** Every host's fleet, by owner. */
	static final class ByOwner<P> {
		private final Map<UUID, ProxyFleet<P>> fleets = new HashMap<>();

		/** The owner's fleet, created empty when it has none. */
		ProxyFleet<P> of(final UUID owner) {
			return this.fleets.computeIfAbsent(owner, o -> new ProxyFleet<>());
		}

		/** The proxy standing for (owner, id), or null. */
		@Nullable P get(final @Nullable UUID owner, final long id) {
			ProxyFleet<P> fleet = owner == null ? null : this.fleets.get(owner);
			return fleet == null ? null : fleet.proxies.get(id);
		}

		/** Forgets the owner's fleet and hands it back (null when it had none). */
		@Nullable ProxyFleet<P> remove(final UUID owner) {
			return this.fleets.remove(owner);
		}

		Map<UUID, ProxyFleet<P>> all() {
			return this.fleets;
		}
	}
}
