package dev.cyberblock.client.atlas;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.HostLink;
import dev.cyberblock.client.HostState;
import dev.cyberblock.client.ui.UiLink;
import dev.cyberblock.client.ui.UiModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * The Atlas's map of Night City, kept ONCE per installation in {@code <game dir>/cyberblock/nightcity-map/} (Night City's geometry
 * is the same in every Minecraft world and every Cyberpunk save, so every world and save shares it; it is never tied to a save):
 * one region file per 8 x 8 tiles ({@code r.<rx>.<rz>.cbat}, see {@link AtlasTiles}), the survey tour's finished stops
 * ({@code survey.json}, the resume state) and where V has been in each district ({@code districts.json}, for the district
 * labels). Loaded when the game starts, filled from {@code terrain} on the link thread, saved every 30 s by a background thread,
 * when a world closes and when the game quits. A per-world copy an earlier build kept in {@code <world>/cyberblock/atlas/} is
 * merged in (cells the shared map does not know yet) when that world opens, and renamed {@code atlas.merged}.
 */
public final class AtlasStore {
	public static final AtlasTiles tiles = new AtlasTiles();
	private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.cbat");
	private static final int SAVE_TICKS = 600;
	private static final int DISTRICT_TICKS = 20;
	private static final double DISTRICT_CAP = 5000.0;
	private static final ExecutorService SAVER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Cyberblock atlas saver");
		t.setDaemon(true);
		return t;
	});
	private static final AtomicInteger bad = new AtomicInteger();
	/** The survey tour's stops Cyberpunk reported finished (keys gx, gy). Guarded by itself. */
	private static final TreeSet<Long> stops = new TreeSet<>();
	/** District name -> {sum x, sum z, weight}: where V has been while the HUD named that district. Guarded by itself. */
	private static final Map<String, double[]> districts = new HashMap<>();
	private static volatile @Nullable Path dir;
	private static volatile boolean extrasDirty;
	/** The last {@code surveystate}; null before any. */
	public static volatile AtlasWire.@Nullable SurveyState survey;
	private static int ticks;

	private AtlasStore() {
	}

	public static void register() {
		HostLink.listen("terrain", m -> {
			int[] cells = AtlasWire.parseTerrain(m);
			if (cells == null) {
				warn("terrain");
				return;
			}

			for (int i = 0; i < cells.length; i += 4) {
				tiles.put(cells[i], cells[i + 1], cells[i + 2], cells[i + 3] == 1);
			}
		});
		HostLink.listen("surveystate", m -> {
			AtlasWire.SurveyState state = AtlasWire.parseSurveyState(m);
			if (state == null) {
				warn("surveystate");
				return;
			}

			AtlasWire.SurveyState before = survey;
			survey = state;
			if (state.stop() != null) {
				synchronized (stops) {
					stops.add(AtlasTiles.key(state.stop()[0], state.stop()[1]));
				}

				extrasDirty = true;
			}

			if (before != null && before.running() && !state.running()) {
				Cyberblock.LOG.info("atlas: survey {} ({}), {} of {} squares", state.state(), state.reason(), state.done(), state.total());
				switch (state.state()) {
					case "done" -> UiLink.toast("level", "Atlas survey done", "All of Night City is on the map", 8.0);
					case "stopped" -> UiLink.toast("warning", "Atlas survey stopped", state.reason() + " (" + state.done() + "/" + state.total() + ")", 6.0);
					default -> {
					}
				}
			}

			if (state.state().equals("done") && (before == null || !before.running())) {
				UiLink.toast("info", "Night City is already mapped", "/cyberblock survey redo maps it again", 6.0);
			}

			if (state.state().equals("refused")) {
				UiLink.toast("warning", "Atlas survey refused", state.reason(), 6.0);
			}
		});
		open(FabricLoader.getInstance().getGameDir().resolve("cyberblock").resolve("nightcity-map"));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> migrate(server.getWorldPath(LevelResource.ROOT).resolve("cyberblock").resolve("atlas")));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveAsync());
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> close());
		ClientTickEvents.END_CLIENT_TICK.register(AtlasStore::tick);
	}

	/** Every client tick: a district sample each second, a save every 30 s. */
	private static void tick(final Minecraft minecraft) {
		ticks++;
		if (ticks % DISTRICT_TICKS == 0) {
			UiModel.Stats stats = UiModel.stats;
			HostState.Pose pose = HostState.frame();
			if (stats != null && pose != null && !stats.district().isBlank()) {
				noteDistrict(stats.district(), pose.px(), pose.pz());
			}
		}

		if (ticks % SAVE_TICKS == 0) {
			saveAsync();
		}
	}

	/** V stands at (x, z) in {@code district}. */
	static void noteDistrict(final String district, final double x, final double z) {
		synchronized (districts) {
			double[] sum = districts.computeIfAbsent(district, d -> new double[3]);
			if (sum[2] >= DISTRICT_CAP) {
				// old samples fade, so a district's label follows where V has been lately as well as long ago
				sum[0] *= 0.5;
				sum[1] *= 0.5;
				sum[2] *= 0.5;
			}

			sum[0] += x;
			sum[1] += z;
			sum[2] += 1.0;
		}

		extrasDirty = true;
	}

	/** District name -> {centre x, centre z}, from where V has been. */
	public static Map<String, double[]> districtCentres() {
		Map<String, double[]> out = new HashMap<>();
		synchronized (districts) {
			districts.forEach((name, sum) -> {
				if (sum[2] > 0.0) {
					out.put(name, new double[] {sum[0] / sum[2], sum[1] / sum[2]});
				}
			});
		}

		return out;
	}

	/** The survey stops Minecraft already has, for {@code survey start}: the reported ones and those its cells cover. */
	public static List<Long> doneStops() {
		List<Long> reported;
		synchronized (stops) {
			reported = new ArrayList<>(stops);
		}

		return AtlasWire.doneStops(tiles, reported);
	}

	/** Loads the shared map (once, at start). */
	static synchronized void open(final Path folder) {
		tiles.clear();
		synchronized (stops) {
			stops.clear();
		}

		synchronized (districts) {
			districts.clear();
		}

		dir = folder;
		int regions = 0;
		if (Files.isDirectory(folder)) {
			try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "r.*.cbat")) {
				for (Path file : files) {
					Matcher m = REGION_FILE.matcher(file.getFileName().toString());
					if (m.matches() && tiles.decodeRegion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Files.readAllBytes(file))) {
						regions++;
					} else {
						Cyberblock.LOG.warn("atlas: {} is not an atlas region file, skipped", file);
					}
				}
			} catch (IOException | NumberFormatException e) {
				Cyberblock.LOG.warn("atlas: could not read {}: {}", folder, e.toString());
			}

			readExtras(folder);
		}

		tiles.takeDirtyRegions(); // what was just loaded is on disk already
		extrasDirty = false;
		Cyberblock.LOG.info("atlas: opened {} ({} regions, {} tiles, {} survey stops)", folder, regions, tiles.tileCount(), stops.size());
	}

	/** The game quits: everything is saved now. */
	static synchronized void close() {
		Path folder = dir;
		if (folder == null) {
			return;
		}

		Map<Long, byte[]> regions = tiles.takeDirtyRegions();
		Map<String, String> extras = extras();
		try {
			// on the saver thread, after any save still queued there, so an older region never overwrites this one
			SAVER.submit(() -> write(folder, regions, extras)).get(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException | TimeoutException e) {
			Cyberblock.LOG.warn("atlas: the last save of {} did not finish: {}", folder, e.toString());
		}

		Cyberblock.LOG.info("atlas: saved {}", folder);
	}

	/**
	 * An earlier build's per-world copy in {@code worldAtlas}: merged into the shared map (only cells it does not know yet), its
	 * finished survey stops and district visits added, the folder renamed {@code atlas.merged} so it is merged once.
	 */
	static synchronized void migrate(final Path worldAtlas) {
		if (!Files.isDirectory(worldAtlas)) {
			return;
		}

		int regions = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(worldAtlas, "r.*.cbat")) {
			for (Path file : files) {
				Matcher m = REGION_FILE.matcher(file.getFileName().toString());
				if (m.matches() && tiles.mergeRegion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Files.readAllBytes(file))) {
					regions++;
				}
			}
		} catch (IOException | NumberFormatException e) {
			Cyberblock.LOG.warn("atlas: could not merge {}: {}", worldAtlas, e.toString());
			return;
		}

		readExtras(worldAtlas);
		extrasDirty = true;
		try {
			Files.move(worldAtlas, worldAtlas.resolveSibling("atlas.merged"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			Cyberblock.LOG.warn("atlas: merged {} but could not rename it: {}", worldAtlas, e.toString());
		}

		Cyberblock.LOG.info("atlas: merged the world's own map {} ({} regions) into the shared Night City map", worldAtlas, regions);
		saveAsync();
	}

	/** {@code /cyberblock survey redo}: the reported stops are forgotten, so the next tour visits every stop again. */
	public static void forgetStops() {
		synchronized (stops) {
			stops.clear();
		}

		extrasDirty = true;
	}

	/** Saves the changed regions on the background thread. */
	static synchronized void saveAsync() {
		Path folder = dir;
		if (folder == null) {
			return;
		}

		Map<Long, byte[]> regions = tiles.takeDirtyRegions();
		@Nullable Map<String, String> extras = extrasDirty ? extras() : null;
		extrasDirty = false;
		if (!regions.isEmpty() || extras != null) {
			SAVER.submit(() -> write(folder, regions, extras));
		}
	}

	private static Map<String, String> extras() {
		JsonArray done = new JsonArray();
		synchronized (stops) {
			for (long k : stops) {
				done.add(AtlasTiles.keyA(k));
				done.add(AtlasTiles.keyB(k));
			}
		}

		JsonObject survey = new JsonObject();
		survey.add("done", done);
		JsonObject places = new JsonObject();
		synchronized (districts) {
			districts.forEach((name, sum) -> {
				JsonArray a = new JsonArray();
				a.add(sum[0]);
				a.add(sum[1]);
				a.add(sum[2]);
				places.add(name, a);
			});
		}

		Gson gson = new Gson();
		return Map.of("survey.json", gson.toJson(survey), "districts.json", gson.toJson(places));
	}

	private static void readExtras(final Path folder) {
		try {
			Path survey = folder.resolve("survey.json");
			if (Files.exists(survey) && JsonParser.parseString(Files.readString(survey, StandardCharsets.UTF_8)) instanceof JsonObject o
				&& o.get("done") instanceof JsonArray done) {
				synchronized (stops) {
					for (int i = 0; i + 1 < done.size(); i += 2) {
						stops.add(AtlasTiles.key(done.get(i).getAsInt(), done.get(i + 1).getAsInt()));
					}
				}
			}

			Path places = folder.resolve("districts.json");
			if (Files.exists(places) && JsonParser.parseString(Files.readString(places, StandardCharsets.UTF_8)) instanceof JsonObject o) {
				synchronized (districts) {
					for (Map.Entry<String, JsonElement> e : o.entrySet()) {
						if (e.getValue() instanceof JsonArray a && a.size() == 3) {
							double[] add = {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
							districts.merge(e.getKey(), add, (have, more) -> new double[] {have[0] + more[0], have[1] + more[1], have[2] + more[2]});
						}
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			Cyberblock.LOG.warn("atlas: could not read the survey or district file in {}: {}", folder, e.toString());
		}
	}

	/** Writes each file to a temporary name and moves it into place, so a crash never leaves half a region. */
	private static void write(final Path folder, final Map<Long, byte[]> regions, final @Nullable Map<String, String> extras) {
		try {
			Files.createDirectories(folder);
			for (Map.Entry<Long, byte[]> region : regions.entrySet()) {
				replace(folder.resolve("r." + AtlasTiles.keyA(region.getKey()) + "." + AtlasTiles.keyB(region.getKey()) + ".cbat"), region.getValue());
			}

			if (extras != null) {
				for (Map.Entry<String, String> file : extras.entrySet()) {
					replace(folder.resolve(file.getKey()), file.getValue().getBytes(StandardCharsets.UTF_8));
				}
			}
		} catch (IOException e) {
			Cyberblock.LOG.warn("atlas: could not save into {}: {}", folder, e.toString());
		}
	}

	private static void replace(final Path file, final byte[] content) throws IOException {
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(temp, content);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static void warn(final String type) {
		if (bad.getAndIncrement() % 100 == 0) {
			Cyberblock.LOG.warn("atlas: bad {} dropped ({} bad atlas messages)", type, bad.get());
		}
	}
}
