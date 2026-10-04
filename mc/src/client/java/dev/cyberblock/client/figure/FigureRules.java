package dev.cyberblock.client.figure;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.figure.SkinMap;
import dev.cyberblock.figure.VehicleModel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * What guests see of a host's Night City people and cars (plan 2026-10-04 tasks 3-F1 and 5-F1): who draws figures, the name tag
 * (label and threat colour), the skin texture of a skin id, and which figures get the hostile outline. Pure: no game in here.
 */
public final class FigureRules {
	/** 3A's neutral figure skin: a person no skin_map row matched, or whose skin texture is missing. */
	static final Identifier NEUTRAL = Identifier.fromNamespaceAndPath(Cyberblock.ID, "textures/entity/figure/neutral.png");
	/** Name tags show within this distance (m). */
	static final double TAG_RANGE = 12.0;
	/** Hostile figures are outlined through walls within this distance (m): the host lists NPCs within 48 m. */
	static final double OUTLINE_RANGE = 48.0;
	/** Threat colours by scan level 0..4 (ScanClient's: grey none, green low, yellow medium, gold high, red extreme), ARGB. */
	private static final int[] THREAT = {0xFFAAAAAA, 0xFF55FF55, 0xFFFFFF55, 0xFFFFAA00, 0xFFFF5555};
	/** A hostile figure no scan has rated is red. */
	static final int HOSTILE = 0xFFFF5555;

	private FigureRules() {
	}

	/** An active host's own client draws no figure: its Cyberpunk game shows the real NPCs and cars (and would show them twice). */
	public static boolean drawn(final boolean activeHost) {
		return !activeHost;
	}

	/** A living figure's name tag shows near the camera; a body or a wreck has none. */
	static boolean showTag(final boolean dead, final double distanceSq) {
		return !dead && distanceSq <= TAG_RANGE * TAG_RANGE;
	}

	/**
	 * The name tag's colour (ARGB): the scan's threat level when one rated it (0..4), else red for a hostile, else none (white). A
	 * hostile is never shown below "high": a scan from before the fight does not make an attacker look harmless.
	 */
	static @Nullable Integer tagColour(final int threat, final boolean hostile) {
		boolean rated = threat >= 0 && threat < THREAT.length;
		if (hostile) {
			return rated ? THREAT[Math.max(threat, 3)] : HOSTILE;
		}

		return rated ? THREAT[threat] : null;
	}

	/** The name tag: the label (role, faction, vehicle model, else Civilian or Vehicle) in the threat colour. */
	static Component tag(final String label, final int threat, final boolean hostile, final boolean vehicle) {
		MutableComponent text = labelText(label, vehicle);
		Integer colour = tagColour(threat, hostile);
		return colour == null ? text : text.withColor(colour & 0xFFFFFF);
	}

	/**
	 * A label as text: "role:RipperDoc" shows "Ripper Doc" (key cyberblock.figure.role.ripperdoc), "faction:TygerClaws" "Tyger Claws",
	 * "vehicle:armored_van" "Armored Van"; "" Civilian or Vehicle.
	 */
	static MutableComponent labelText(final String label, final boolean vehicle) {
		int colon = label.indexOf(':');
		String kind = colon < 0 ? "" : label.substring(0, colon), value = colon < 0 ? "" : label.substring(colon + 1);
		if (value.isEmpty() || !(kind.equals("role") || kind.equals("faction") || kind.equals("vehicle"))) {
			return vehicle ? Component.translatableWithFallback("cyberblock.figure.vehicle", "Vehicle")
				: Component.translatableWithFallback("cyberblock.figure.civilian", "Civilian");
		}

		return Component.translatableWithFallback("cyberblock.figure." + kind + "." + SkinMap.key(value), words(value));
	}

	/** "TygerClaws" → "Tyger Claws", "armored_van" → "Armored Van", "NCPD" stays. */
	static String words(final String s) {
		StringBuilder out = new StringBuilder();
		char last = ' ';
		for (char c : s.toCharArray()) {
			if (c == '_' || c == '-' || c == ' ') {
				c = ' ';
			} else if (Character.isUpperCase(c) && Character.isLowerCase(last)) {
				out.append(' ');
			}

			if (c != ' ' || last != ' ') {
				out.append(out.isEmpty() || last == ' ' ? Character.toUpperCase(c) : c);
			}

			last = c;
		}

		return out.toString().trim();
	}

	/** The texture of a person's synced skin: skin_map's texture id (section 1.12), or the neutral skin for none or a bad one. */
	static Identifier skinTexture(final String skin) {
		Identifier id = skin.isEmpty() || skin.equals(SkinMap.NEUTRAL) ? null : Identifier.tryParse(skin);
		return id == null ? NEUTRAL : id;
	}

	/** A vehicle model id as the model (an unknown one is a sedan). */
	static VehicleModel vehicle(final String id) {
		VehicleModel model = VehicleModel.byId(id);
		return model == null ? VehicleModel.SEDAN : model;
	}

	/**
	 * Whether a figure gets the red outline through walls on this client: a living hostile within OUTLINE_RANGE. Kiroshi optics are no
	 * exception: their box (CyberwareClient) is depth-tested, so only this one shows through walls.
	 */
	static boolean outlined(final boolean hostile, final boolean dead, final double distanceSq) {
		return hostile && !dead && distanceSq <= OUTLINE_RANGE * OUTLINE_RANGE;
	}
}
