package dev.cyberblock.figure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;

/**
 * The {@code vehicle_map} content kind (megaplan sections 1.7 and 1.12, tasks 5-A2 and 5-F1): which of the 12 vehicle models a Night
 * City vehicle is drawn as, and its paint. One row per file, as 5A's art/gen_vehicle_art.py writes them:
 * <pre>{"schema":1, "model":"police_cruiser", "colour":"#1f2c4a", "vc":["Car/Turbo"], "type":"Car", "faction":"NCPD",
 * "keepColour":true, "priority":20}</pre>
 * A vehicle matches a row when each of its match keys holds: {@code vc} (the npcs {@code vc}, "&lt;type&gt;/&lt;model&gt;"),
 * {@code type} (the part of {@code vc} before the slash) and {@code faction} (its {@code fac}); a key is one name or a list, matched
 * without case or punctuation. The highest {@code priority} wins, then the row with more keys, then the lowest id (rows come in id
 * order). A row without keys matches every vehicle. The paint is the host's {@code col}, else the row's {@code colour}; a row with
 * {@code keepColour} (a livery) always paints its own. With no rows at all the class default applies (a bike for type Bike, a police
 * cruiser for NCPD, a Trauma Team aerodyne for TraumaTeam, else a sedan) in the model's own colour. Pure apart from the snapshot.
 */
public final class VehicleMap {
	/** One row: vcs normalised "type/model", types and factions normalised; colour 0xRRGGBB. */
	public record Row(VehicleModel model, int colour, Set<String> vcs, Set<String> types, Set<String> factions, boolean keepColour, int priority) {
		/** -1 when the row does not match, else how many match keys it has. */
		int keys(final String vc, final String fac) {
			String full = vehicleClass(vc);
			if (!this.vcs.isEmpty() && !this.vcs.contains(full) || !this.types.isEmpty() && !this.types.contains(type(full))
				|| !this.factions.isEmpty() && !this.factions.contains(SkinMap.key(fac))) {
				return -1;
			}

			return (this.vcs.isEmpty() ? 0 : 1) + (this.types.isEmpty() ? 0 : 1) + (this.factions.isEmpty() ? 0 : 1);
		}
	}

	/** What a vehicle looks like: its model and paint (0xRRGGBB). */
	public record Vehicle(VehicleModel model, int colour) {
	}

	private VehicleMap() {
	}

	/** The content parser: one file, one row. */
	public static Row parse(final Identifier id, final JsonObject json) {
		String name = Content.string(json, "model", 32);
		VehicleModel model = VehicleModel.byId(name);
		if (model == null) {
			throw new IllegalArgumentException("model must be one of the 12 vehicle models, not " + name);
		}

		Set<String> vcs = new TreeSet<>();
		// SkinMap.keys would strip the slash: read the strings, then normalise each half
		for (String raw : SkinMap.strings(json, "vc")) {
			String vc = vehicleClass(raw);
			if (!vc.matches("[a-z0-9]+/[a-z0-9]+")) {
				throw new IllegalArgumentException("vc must be <type>/<model>, not " + raw);
			}

			vcs.add(vc);
		}

		boolean keep = false;
		if (json.has("keepColour")) {
			JsonElement k = json.get("keepColour");
			if (!k.isJsonPrimitive() || !k.getAsJsonPrimitive().isBoolean()) {
				throw new IllegalArgumentException("keepColour must be true or false");
			}

			keep = k.getAsBoolean();
		}

		return new Row(model, Content.colour(json, "colour"), vcs, SkinMap.keys(json, "type"), SkinMap.keys(json, "faction"), keep,
			SkinMap.priority(json));
	}

	/** The vehicle with these tags among the rows (in id order). */
	public static Vehicle resolve(final Collection<Row> rows, final FigureTags tags) {
		Row best = null;
		int bestKeys = -1;
		for (Row row : rows) {
			int keys = row.keys(tags.vc(), tags.fac());
			if (keys >= 0 && (best == null || row.priority() > best.priority() || row.priority() == best.priority() && keys > bestKeys)) {
				best = row;
				bestKeys = keys;
			}
		}

		if (best == null) {
			VehicleModel model = classDefault(tags);
			return new Vehicle(model, tags.col() >= 0 ? tags.col() : model.defaultColour());
		}

		return new Vehicle(best.model(), tags.col() >= 0 && !best.keepColour() ? tags.col() : best.colour());
	}

	/** The model when no row matches (no vehicle_map at all). */
	static VehicleModel classDefault(final FigureTags tags) {
		if (type(vehicleClass(tags.vc())).equals("bike")) {
			return VehicleModel.BIKE;
		}

		return switch (SkinMap.key(tags.fac())) {
			case "ncpd" -> VehicleModel.POLICE_CRUISER;
			case "traumateam" -> VehicleModel.TRAUMA_AERODYNE;
			default -> VehicleModel.SEDAN;
		};
	}

	/** "Car/Type 66" as "car/type66"; a bare type as just the type. */
	static String vehicleClass(final String vc) {
		int slash = vc.indexOf('/');
		return slash < 0 ? SkinMap.key(vc) : SkinMap.key(vc.substring(0, slash)) + "/" + SkinMap.key(vc.substring(slash + 1));
	}

	private static String type(final String vehicleClass) {
		int slash = vehicleClass.indexOf('/');
		return slash < 0 ? vehicleClass : vehicleClass.substring(0, slash);
	}
}
