package dev.cyberblock.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.mode.overlord.Intensity;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The world's difficulty settings (megaplan 9-S1, the settings screen's Difficulty tab): the Overlord intensity a new match starts with
 * (8-O1's Chill, Normal or Brutal; the match's host can still change it per match) and the Heat scaling, the percentage of every chaos
 * gain that reaches the Server Heat meter (8-T1). Saved with the world as {@code cyberblock:difficulty}, like the creative erase setting;
 * changed by operators and the singleplayer owner with {@code /cyberblock difficulty overlord|heat}. Readable from any thread.
 */
public final class Difficulty {
	/** Heat scaling in percent: 25 (a quarter of every gain) to 200 (double), 100 = the 8T tuning as it ships. */
	public static final int HEAT_MIN = 25, HEAT_MAX = 200, HEAT_DEFAULT = 100, HEAT_STEP = 25;
	public static final Intensity OVERLORD_DEFAULT = Intensity.NORMAL;

	/** The saved values; a bad intensity or an out-of-range percentage (a hand-edited save) reads as its default. */
	static final class Data extends SavedData {
		static final Codec<Data> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.optionalFieldOf("overlord", OVERLORD_DEFAULT.key()).forGetter(d -> d.overlord.key()),
			Codec.INT.optionalFieldOf("heat", HEAT_DEFAULT).forGetter(d -> d.heat)
		).apply(i, Data::new));
		/** No data fixer: Fabric API's SavedDataStorageMixin reads a type without one as it is (as OutlineRules.Settings). */
		static final SavedDataType<Data> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Cyberblock.ID, "difficulty"), Data::new, CODEC, null);
		Intensity overlord;
		int heat;

		Data() {
			this(OVERLORD_DEFAULT.key(), HEAT_DEFAULT);
		}

		Data(final String overlord, final int heat) {
			this.overlord = intensity(overlord);
			this.heat = heat(heat);
		}
	}

	private static Data data = new Data();
	private static volatile Intensity overlord = OVERLORD_DEFAULT;
	private static volatile int heat = HEAT_DEFAULT;

	private Difficulty() {
	}

	/** A key of {@link Intensity} (chill, normal, brutal); anything else is the default, Normal. */
	static Intensity intensity(final String key) {
		Intensity i = Intensity.byKey(key);
		return i == null ? OVERLORD_DEFAULT : i;
	}

	/** A Heat scaling percentage; outside 25..200 it is the default, 100. */
	static int heat(final int percent) {
		return percent >= HEAT_MIN && percent <= HEAT_MAX ? percent : HEAT_DEFAULT;
	}

	/** The intensity a new match's Overlord starts with (8O's MatchState reads it). */
	public static Intensity overlordDefault() {
		return overlord;
	}

	/** The Heat scaling in percent. */
	public static int heatPercent() {
		return heat;
	}

	/** The factor every chaos gain is multiplied by before it reaches the meter (8T's ServerHeat.add reads it). */
	public static double heatScale() {
		return heat / 100.0;
	}

	/** Server thread, when the world has started: its saved values. */
	static void attach(final MinecraftServer server) {
		use(server.overworld().getDataStorage().computeIfAbsent(Data.TYPE));
	}

	/** Server thread, when the world stops; tests use it too. */
	static void use(final Data d) {
		data = d;
		overlord = d.overlord;
		heat = d.heat;
	}

	/** Server thread: the new default intensity (saved). True when it changed. */
	static boolean setOverlord(final Intensity value) {
		if (data.overlord == value) {
			return false;
		}

		data.overlord = value;
		data.setDirty();
		overlord = value;
		return true;
	}

	/** Server thread: the new Heat scaling (25..200, saved). True when it changed. */
	static boolean setHeat(final int percent) {
		if (percent < HEAT_MIN || percent > HEAT_MAX || data.heat == percent) {
			return false;
		}

		data.heat = percent;
		data.setDirty();
		heat = percent;
		return true;
	}

	/** "Overlord intensity default normal, Heat scaling 100 %". */
	static String describe() {
		return "Overlord intensity default " + overlord.key() + ", Heat scaling " + heat + " %";
	}
}
