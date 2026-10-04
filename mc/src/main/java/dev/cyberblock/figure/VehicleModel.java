package dev.cyberblock.figure;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The 12 vehicle models a guest sees (megaplan task 5-F1, section 1.12), with the ids of 5A's {@code vehicle_map} and textures
 * (art/gen_vehicle_art.py): the paint when neither the host's {@code col} nor the map gives one, and whether it carries an emergency
 * light bar (flashing while the vehicle is hostile: a chase). The shapes are Java model code on the client (client/figure/
 * VehicleModels, 5A's UV layout).
 */
public enum VehicleModel {
	SEDAN(0x8A8F99, false),
	SPORTS_CAR(0xC23B3B, false),
	TRUCK(0xB88A3A, false),
	VAN(0xD9D9D4, false),
	BIKE(0x3A3A44, false),
	TAXI(0xF2C230, false),
	POLICE_CRUISER(0x1F2C4A, true),
	ARMOURED_VAN(0x3B4136, false),
	TRAUMA_AERODYNE(0xE8ECEF, true),
	NCPD_AERODYNE(0x1F3E73, true),
	DELIVERY_TRUCK(0xD8D2C4, false),
	PICKUP(0x6D4F3A, false);

	private final int defaultColour;
	private final boolean siren;

	VehicleModel(final int defaultColour, final boolean siren) {
		this.defaultColour = defaultColour;
		this.siren = siren;
	}

	/** The id in data files, on the wire and in texture names: the name in lower case (sedan, police_cruiser, ...). */
	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	/** 0xRRGGBB. */
	public int defaultColour() {
		return this.defaultColour;
	}

	/** It has a light bar (5A's lights_a and lights_b textures). */
	public boolean siren() {
		return this.siren;
	}

	/** An aerodyne hovers. */
	public boolean flies() {
		return this == TRAUMA_AERODYNE || this == NCPD_AERODYNE;
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
