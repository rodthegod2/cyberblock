package dev.cyberblock.env;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.HostSession;
import dev.cyberblock.net.HostSessions;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;
import org.jspecify.annotations.Nullable;

/**
 * Megaplan 3-E1: Night City's time of day and weather drive the Minecraft world while a primary host (the earliest active session)
 * exists. Its {@code stats.time} and {@code env.time} set the day time ({@code ticks = ((h*60 + m) / 1440 * 24000 + 18000) mod 24000},
 * with {@code advance_time} off); its {@code env.weather} sets the weather (rain, storm = thunder, the rest clear, with
 * {@code advance_weather} off). Phase 0 left weather readability unconfirmed in game (CET ENV): {@code other} or no weather keeps
 * the current weather, and one line says which path is active ({@code env: weather from host <name> (<value>)} or
 * {@code env: time only (weather unreadable)}) whenever it changes. With no host the world keeps the last values; a second host
 * never changes them. Server thread only.
 *
 * <p>26.3 names (javap on the Loom jar): the day time lives on the dimension's default {@code WorldClock} in
 * {@code MinecraftServer.clockManager()} (what {@code /time set} uses); weather is {@code MinecraftServer.setWeatherParameters}
 * (what {@code /weather} uses) and {@code ServerLevel.getWeatherData()}.
 */
public final class EnvSync {
	/** What Minecraft's weather becomes; KEEP leaves it as it is (unreadable or unmapped weather). */
	public enum Weather {
		CLEAR, RAIN, THUNDER, KEEP
	}

	static final String TIME_ONLY = "env: time only (weather unreadable)";
	/** A host that sent this many stats without any env has no weather reader (an older DLL): the time-only path. */
	static final int STATS_WITHOUT_ENV = 5;
	/** How long a set weather lasts on its own; advance_weather is off meanwhile and each change sets it again. */
	private static final int WEATHER_TICKS = 24_000 * 7;
	/** Per host session: stats seen without any env (-1 once an env came). */
	private static final Map<UUID, Integer> statsWithoutEnv = new HashMap<>();
	private static @Nullable String lastLine;
	private static boolean warnedNoClock;

	private EnvSync() {
	}

	public static void init() {
		HostSessions.listen("stats", (s, m) -> {
			if (s != HostSessions.primary()) {
				return;
			}

			int seen = statsWithoutEnv.merge(s.uuid(), 1, (a, b) -> a < 0 ? a : a + b);
			if (seen >= STATS_WITHOUT_ENV) {
				logPath(TIME_ONLY);
			}

			int[] t = s.time(); // HostSessions applied and validated stats before the listeners
			setTime(s, t[0], t[1]);
		});
		HostSessions.listen("env", EnvSync::onEnv);
		HostSessions.onEnd(s -> statsWithoutEnv.remove(s.uuid()));
	}

	/** env {time:[h,m], weather}: from the primary host only. A bad time is ignored; bad or unreadable weather keeps the weather. */
	private static void onEnv(final HostSession s, final JsonObject m) {
		if (s != HostSessions.primary()) {
			return;
		}

		statsWithoutEnv.put(s.uuid(), -1);
		int[] t = time(m);
		if (t != null) {
			setTime(s, t[0], t[1]);
		}

		JsonElement raw = m.get("weather");
		Weather w = weather(raw);
		logPath(pathLine(s.name(), raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString() ? raw.getAsString() : null, w));
		ServerPlayer player = s.player();
		if (w != Weather.KEEP && player != null) {
			setWeather(player.level().getServer(), w);
		}
	}

	/** Minecraft day time (0..23999, 0 = 06:00) for a Night City hour and minute. */
	static int dayTicks(final int hour, final int minute) {
		return (int) Math.floorMod((hour * 60L + minute) * 24_000L / 1440L + 18_000L, 24_000L);
	}

	/**
	 * The clock's next total ticks for this day time: the same Minecraft day as now, or the neighbouring one when the step crosses
	 * the day boundary (Night City's 05:59 to 06:00 is a few ticks forward, not a day back), so the day count and moon go on; never
	 * negative.
	 */
	static long nextTotal(final long current, final int dayTicks) {
		long target = Math.floorDiv(current, 24_000L) * 24_000L + dayTicks;
		if (target < current - 12_000L) {
			return target + 24_000L;
		}

		// never before tick 0 (a new world's first day has no day before it)
		return target > current + 12_000L && target >= 24_000L ? target - 24_000L : target;
	}

	/** The 1.9 env weather as Minecraft weather: rain, storm = thunder, the other named kinds clear; other or anything else KEEP. */
	static Weather weather(final @Nullable JsonElement value) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			return Weather.KEEP;
		}

		return switch (value.getAsString()) {
			case "rain" -> Weather.RAIN;
			case "storm" -> Weather.THUNDER;
			case "clear", "cloudy", "fog", "smog", "sandstorm" -> Weather.CLEAR;
			default -> Weather.KEEP;
		};
	}

	/** env.time [h 0..23, m 0..59] (whole numbers), or null when absent or bad. */
	static int @Nullable [] time(final JsonObject m) {
		if (!(m.get("time") instanceof JsonArray a) || a.size() != 2) {
			return null;
		}

		int h = whole(a.get(0), 23), min = whole(a.get(1), 59);
		return h < 0 || min < 0 ? null : new int[] {h, min};
	}

	private static int whole(final JsonElement e, final int max) {
		if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
			return -1;
		}

		double v = p.getAsDouble();
		return v >= 0 && v <= max && v == Math.rint(v) ? (int) v : -1;
	}

	/** The line that says where the world's weather comes from. */
	static String pathLine(final String host, final @Nullable String raw, final Weather w) {
		return w == Weather.KEEP ? TIME_ONLY : "env: weather from host " + host + " (" + raw + ")";
	}

	private static void logPath(final String line) {
		if (!line.equals(lastLine)) {
			lastLine = line;
			Cyberblock.LOG.info(line);
		}
	}

	private static void setTime(final HostSession s, final int hour, final int minute) {
		ServerPlayer player = s.player();
		if (player == null) {
			return;
		}

		MinecraftServer server = player.level().getServer();
		ServerLevel level = server.overworld();
		Optional<Holder<WorldClock>> clock = level.dimensionTypeRegistration().value().defaultClock();
		if (clock.isEmpty()) {
			if (!warnedNoClock) {
				warnedNoClock = true;
				Cyberblock.LOG.warn("env: the overworld has no default clock: Night City's time is not shown");
			}

			return;
		}

		GameRules rules = server.getGameRules();
		if (rules.get(GameRules.ADVANCE_TIME)) {
			rules.set(GameRules.ADVANCE_TIME, false, server);
		}

		ServerClockManager clocks = server.clockManager();
		long now = clocks.getInstance(clock.get()).totalTicks();
		long next = nextTotal(now, dayTicks(hour, minute));
		if (next != now) {
			clocks.setTotalTicks(clock.get(), next);
		}
	}

	private static void setWeather(final MinecraftServer server, final Weather w) {
		GameRules rules = server.getGameRules();
		if (rules.get(GameRules.ADVANCE_WEATHER)) {
			rules.set(GameRules.ADVANCE_WEATHER, false, server);
		}

		WeatherData now = server.overworld().getWeatherData();
		boolean rain = w != Weather.CLEAR, thunder = w == Weather.THUNDER;
		if (now.isRaining() != rain || now.isThundering() != thunder) {
			server.setWeatherParameters(rain ? 0 : WEATHER_TICKS, rain ? WEATHER_TICKS : 0, rain, thunder);
		}
	}
}
