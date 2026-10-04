package dev.cyberblock.figure;

import com.google.gson.JsonObject;
import dev.cyberblock.content.Content;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;

/**
 * The {@code vehicle_map} content kind (megaplan sections 1.7 and 1.12, task 5-F1): which of the 12 vehicle models a Night City
 * vehicle is, and its paint when the host sends no {@code col}. One row per file:
 * <pre>{"schema":1, "model":"police", "class":["Car/Alvarado", "Car"], "faction":"NCPD", "colour":"#1D2633"}</pre>
 * {@code model} is a VehicleModel id; {@code class} (a string or a list) holds the host's {@code vc} values ("&lt;type&gt;/&lt;model&gt;",
 * or a bare type such as "Bike" for every model of it) and {@code faction} the vehicle's {@code fac}, both matched without case or
 * punctuation. The most specific matching row wins (exact class 4, faction 2, type 1; then the row id). With no matching row the class
 * default applies: a bike for type Bike, a police car for NCPD, a Trauma Team aerodyne for TraumaTeam, else a sedan. The paint is the
 * host's {@code col}, else the row's {@code colour}, else the model's own. Pure apart from the content snapshot.
 */
public final class VehicleMap {
	/** One row: classes are normalised "type/model" (or a bare "type"), colour 0xRRGGBB or -1. */
	public record Row(VehicleModel model, Set<String> classes, Set<String> factions, int colour) {
		int score(final String vc, final String fac) {
			if (this.classes.isEmpty() && this.factions.isEmpty()) {
				return -1;
			}

			String full = vehicleClass(vc), type = type(full);
			int classScore = this.classes.isEmpty() ? 0 : this.classes.contains(full) && full.contains("/") ? 4 : this.classes.contains(type) ? 1 : -1;
			int factionScore = this.factions.isEmpty() ? 0 : this.factions.contains(SkinMap.key(fac)) ? 2 : -1;
			return classScore < 0 || factionScore < 0 ? -1 : classScore + factionScore;
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

		Set<String> classes = new TreeSet<>();
		// SkinMap.keys would strip the slash: read the strings, then normalise each half
		for (String raw : SkinMap.strings(json, "class")) {
			if (SkinMap.key(raw).isEmpty()) {
				throw new IllegalArgumentException("class needs letters or digits");
			}

			classes.add(vehicleClass(raw));
		}

		Set<String> factions = SkinMap.keys(json, "faction");
		if (classes.isEmpty() && factions.isEmpty()) {
			throw new IllegalArgumentException("a row needs a class or a faction");
		}

		return new Row(model, classes, factions, json.has("colour") ? Content.colour(json, "colour") : -1);
	}

	/** The vehicle with these tags among the rows (in id order). */
	public static Vehicle resolve(final Collection<Row> rows, final FigureTags tags) {
		Row best = null;
		int bestScore = -1;
		for (Row row : rows) {
			int score = row.score(tags.vc(), tags.fac());
			if (score > bestScore) {
				best = row;
				bestScore = score;
			}
		}

		VehicleModel model = best != null ? best.model() : classDefault(tags);
		int colour = tags.col() >= 0 ? tags.col() : best != null && best.colour() >= 0 ? best.colour() : model.defaultColour();
		return new Vehicle(model, colour);
	}

	/** The model when no row matches. */
	static VehicleModel classDefault(final FigureTags tags) {
		String fac = SkinMap.key(tags.fac());
		if (type(vehicleClass(tags.vc())).equals("bike")) {
			return VehicleModel.BIKE;
		}

		return switch (fac) {
			case "ncpd" -> VehicleModel.POLICE;
			case "traumateam" -> VehicleModel.TRAUMA_AV;
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
