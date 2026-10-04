package dev.cyberblock.net;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The join password's rules without Minecraft (JoinLock applies them): who is locked, the /login attempts (one per second, five
 * wrong ones kick) and the 120 s limit. The wrong-attempt count is kept per UUID, in memory only, across reconnects until
 * STRIKE_MEMORY_MS pass without an attempt, so leaving and joining again does not reset the five tries (the typed text is never
 * kept). One instance per server; the maps are concurrent because the lock checks also run from mixins, but every change happens
 * on the server thread.
 */
final class JoinGate {
	static final int MAX_ATTEMPTS = 5;
	static final long ATTEMPT_GAP_MS = 1_000;
	static final long TIMEOUT_MS = 120_000;
	/** Wrong attempts are remembered this long after the last one (10 minutes). */
	static final long STRIKE_MEMORY_MS = 600_000;

	enum Outcome {
		/** The player is not locked (no join password, or already verified): nothing was checked. */
		NOT_LOCKED,
		/** Less than a second after the last attempt: not checked, not counted. */
		TOO_FAST,
		WRONG,
		/** The fifth wrong password: the caller kicks. */
		KICK,
		OK
	}

	record Result(Outcome outcome, int attemptsLeft) {
	}

	private static final class Pending {
		final long since;
		long last;
		boolean tried;

		Pending(final long since) {
			this.since = since;
		}
	}

	/** One UUID's wrong attempts and when the last one was made; it outlives the connection. */
	private record Strikes(int wrong, long last) {
	}

	private final Map<UUID, Pending> locked = new ConcurrentHashMap<>();
	private final Map<UUID, Strikes> strikes = new ConcurrentHashMap<>();

	boolean locked(final UUID player) {
		return this.locked.containsKey(player);
	}

	int lockedCount() {
		return this.locked.size();
	}

	/** Locks the player from now (a fresh 120 s and five attempts); a player who is locked already keeps its clock. */
	void lock(final UUID player, final long nowMs) {
		this.locked.putIfAbsent(player, new Pending(nowMs));
	}

	/** Verified, gone or the join password turned off. */
	void unlock(final UUID player) {
		this.locked.remove(player);
	}

	/** Server stop: the locks and the remembered wrong attempts go. */
	void clear() {
		this.locked.clear();
		this.strikes.clear();
	}

	/** Wrong attempts this UUID made in the last STRIKE_MEMORY_MS (counted from its last attempt), also over earlier connections. */
	int wrong(final UUID player, final long nowMs) {
		Strikes s = this.strikes.get(player);
		if (s == null) {
			return 0;
		}

		if (nowMs - s.last() >= STRIKE_MEMORY_MS) {
			this.strikes.remove(player);
			return 0;
		}

		return s.wrong();
	}

	/** All five tries are used up and not yet forgotten: the player is refused at once when it joins. */
	boolean exhausted(final UUID player, final long nowMs) {
		return this.wrong(player, nowMs) >= MAX_ATTEMPTS;
	}

	/** Minutes until an exhausted player may try again (at least 1). */
	long minutesUntilRetry(final UUID player, final long nowMs) {
		Strikes s = this.strikes.get(player);
		return s == null ? 0 : Math.max(1, (s.last() + STRIKE_MEMORY_MS - nowMs + 59_999) / 60_000);
	}

	/** Seconds the player has left to log in (0 when not locked or out of time). */
	long secondsLeft(final UUID player, final long nowMs) {
		Pending p = this.locked.get(player);
		return p == null ? 0 : Math.max(0, (p.since + TIMEOUT_MS - nowMs + 999) / 1000);
	}

	/** One /login: checked against the password in constant time; OK unlocks the player. */
	Result attempt(final UUID player, final String typed, final String password, final long nowMs) {
		Pending p = this.locked.get(player);
		if (p == null) {
			return new Result(Outcome.NOT_LOCKED, MAX_ATTEMPTS);
		}

		int wrong = this.wrong(player, nowMs);
		if (p.tried && nowMs - p.last < ATTEMPT_GAP_MS) {
			return new Result(Outcome.TOO_FAST, Math.max(0, MAX_ATTEMPTS - wrong));
		}

		p.tried = true;
		p.last = nowMs;
		if (wrong < MAX_ATTEMPTS && matches(typed, password)) {
			this.locked.remove(player);
			this.strikes.remove(player);
			return new Result(Outcome.OK, MAX_ATTEMPTS - wrong);
		}

		wrong = Math.min(MAX_ATTEMPTS, wrong + 1);
		this.strikes.put(player, new Strikes(wrong, nowMs));
		int left = MAX_ATTEMPTS - wrong;
		return new Result(left <= 0 ? Outcome.KICK : Outcome.WRONG, left);
	}

	/** The locked players whose 120 s are up (they stay locked until the caller kicks them and they leave); forgotten strikes go. */
	List<UUID> timedOut(final long nowMs) {
		this.strikes.values().removeIf(s -> nowMs - s.last() >= STRIKE_MEMORY_MS);
		List<UUID> out = new ArrayList<>();
		this.locked.forEach((uuid, p) -> {
			if (nowMs - p.since >= TIMEOUT_MS) {
				out.add(uuid);
			}
		});
		return out;
	}

	/**
	 * The typed password (trimmed, like the configured one) equals the password: MessageDigest.isEqual on the UTF-8 bytes takes
	 * the same time wherever the first difference is. An empty password never matches.
	 */
	static boolean matches(final String typed, final String password) {
		if (password.isEmpty()) {
			return false;
		}

		return MessageDigest.isEqual(typed.strip().getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8));
	}

	/** {@code login ...} or {@code cyberblock login ...} (with or without the slash): the only command a locked player may run. */
	static boolean isLoginCommand(final String command) {
		String c = command.strip();
		if (c.startsWith("/")) {
			c = c.substring(1);
		}

		String[] words = c.toLowerCase(Locale.ROOT).split("\\s+", 3);
		return words[0].equals("login") || words.length >= 2 && words[0].equals("cyberblock") && words[1].equals("login");
	}
}
