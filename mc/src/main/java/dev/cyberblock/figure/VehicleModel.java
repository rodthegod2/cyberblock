package dev.cyberblock.figure;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The 12 vehicle models a guest sees (megaplan task 5-F1, section 1.12): the id {@code vehicle_map} names, the paint when neither the
 * host's {@code col} nor the map gives one, and whether it carries a police light bar (flashing while the car is hostile: a chase).
 * The shapes are Java model code on the client (client/figure/VehicleModels).
 */
public enum VehicleModel {
	SEDAN(0x8C96A3, false),
	SPORTS(0xC8262E, false),
	TRUCK(0x4A5A6B, false),
	VAN(0xB9BDC2, false),
	BIKE(0x23252B, false),
	TAXI(0xF2C230, false),
	POLICE(0x1D2633, true),
	ARMORED_VAN(0x3B4032, false),
	TRAUMA_AV(0xE8ECEF, false),
	NCPD_AV(0x1F3E73, true),
	DELIVERY(0xD8D3C4, false),
	PICKUP(0x7A4B2C, false);

	private final int defaultColour;
	private final boolean siren;

	VehicleModel(final int defaultColour, final boolean siren) {
		this.defaultColour = defaultColour;
		this.siren = siren;
	}

	/** The id in data files and on the wire: the name in lower case (sedan, armored_van, trauma_av, ...). */
	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	/** 0xRRGGBB. */
	public int defaultColour() {
		return this.defaultColour;
	}

	public boolean siren() {
		return this.siren;
	}

	/** An aerodyne hovers: no wheels, thrusters. */
	public boolean flies() {
		return this == TRAUMA_AV || this == NCPD_AV;
	}

	/** The model with this id, or null. */
	public static @Nullable VehicleModel byId(final String id) {
		for (VehicleModel m : values()) {
			if (m.id().equals(id)) {
				return m;
			}
		}

		return null;
	}
}
