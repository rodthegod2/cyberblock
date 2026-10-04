package dev.cyberblock.figure;

import java.util.Locale;
import java.util.Map;

/**
 * What guests see of one proxy, worked out on the server from the host's tags (megaplan task 5-F1) and synced as entity data: look
 * (a person's skin: its skin_map texture id or SkinMap.NEUTRAL; a vehicle's model id), slim, anim bits, colour (a vehicle's paint,
 * -1 for a person), hp (0..1) and the name tag's label ("role:&lt;vendor type&gt;", "faction:&lt;affiliation&gt;",
 * "vehicle:&lt;model&gt;" or "" for a civilian). Pure.
 */
public record FigureLook(String look, boolean slim, int anim, int colour, float hp, String label) {
	/** A proxy before any list said more: the neutral person. */
	public static final FigureLook NEUTRAL = new FigureLook(SkinMap.NEUTRAL, false, 0, -1, 1.0F, "");
	/** The scan's threat words (ScanInfo.THREATS) by level: a figure's threat is -1 until a scan has named it. */
	private static final Map<String, Integer> THREATS = Map.of("none", 0, "low", 1, "medium", 2, "high", 3, "extreme", 4);

	/** A person: the skin_map row for its tags. */
	public static FigureLook person(final SkinMap.Skin skin, final FigureTags tags) {
		String label = !tags.role().isEmpty() ? "role:" + tags.role()
			: !tags.fac().isEmpty() && !civilian(tags.fac()) ? "faction:" + tags.fac() : "";
		return new FigureLook(skin.texture(), skin.slim(), tags.anim(), -1, tags.hp(), label);
	}

	/** A vehicle: the vehicle_map row for its tags. */
	public static FigureLook vehicle(final VehicleMap.Vehicle vehicle, final FigureTags tags) {
		return new FigureLook(vehicle.model().id(), false, tags.anim(), vehicle.colour(), tags.hp(), "vehicle:" + vehicle.model().id());
	}

	/** Civilians and the unaffiliated show the plain "Civilian" tag. */
	static boolean civilian(final String fac) {
		String key = SkinMap.key(fac);
		return key.equals("civilian") || key.equals("unaffiliated") || key.equals("none");
	}

	/** A scan's threat word as a level 0..4, -1 for anything else. */
	public static int threat(final String word) {
		return THREATS.getOrDefault(word.toLowerCase(Locale.ROOT), -1);
	}
}
