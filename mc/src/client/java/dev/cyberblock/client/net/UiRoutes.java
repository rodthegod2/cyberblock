package dev.cyberblock.client.net;

import dev.cyberblock.Cyberblock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * {@code cyberblock:ui} types the client net hands on (megaplan section 1.3): Fabric takes one receiver per payload type, and the
 * cyber track's CyberLink registered it; it asks {@link LoginScreen#onUi} first, which answers {@code login} itself and every
 * type listed here through {@link #dispatch}. Client thread.
 */
public final class UiRoutes {
	private static final Map<String, Consumer<String>> routes = new ConcurrentHashMap<>();

	private UiRoutes() {
	}

	/** {@code handler} gets the json of every {@code cyberblock:ui} message of type {@code t} (client thread). One handler per type. */
	public static void listen(final String t, final Consumer<String> handler) {
		if (routes.putIfAbsent(t, handler) != null) {
			throw new IllegalStateException("cyberblock:ui " + t + " already has a handler");
		}
	}

	/** True when a handler took the message; a handler that throws is logged, never fatal. */
	static boolean dispatch(final String t, final String json) {
		Consumer<String> handler = routes.get(t);
		if (handler == null) {
			return false;
		}

		try {
			handler.accept(json);
		} catch (RuntimeException e) {
			Cyberblock.LOG.warn("ui {} failed: {}", t, e.toString());
		}

		return true;
	}
}
