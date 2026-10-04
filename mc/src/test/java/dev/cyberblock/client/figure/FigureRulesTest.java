package dev.cyberblock.client.figure;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.figure.VehicleModel;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** Plan 2026-10-04 tasks 3-F1 and 5-F1: what guests see, and that an active host sees nothing. */
class FigureRulesTest {
	@Test
	void anActiveHostDrawsNoFigure() {
		assertFalse(FigureRules.drawn(true));
		assertTrue(FigureRules.drawn(false));
	}

	@Test
	void tagsShowNearAndNeverOnTheDead() {
		assertTrue(FigureRules.showTag(false, 11.9 * 11.9));
		assertFalse(FigureRules.showTag(false, 12.1 * 12.1));
		assertFalse(FigureRules.showTag(true, 1.0));
	}

	@Test
	void theTagIsInTheThreatColour() {
		assertNull(FigureRules.tagColour(-1, false), "an unscanned civilian has a plain tag");
		assertEquals(0xFFAAAAAA, FigureRules.tagColour(0, false));
		assertEquals(0xFF55FF55, FigureRules.tagColour(1, false));
		assertEquals(0xFFFFFF55, FigureRules.tagColour(2, false));
		assertEquals(0xFFFFAA00, FigureRules.tagColour(3, false));
		assertEquals(0xFFFF5555, FigureRules.tagColour(4, false));
		assertEquals(FigureRules.HOSTILE, FigureRules.tagColour(-1, true), "an unscanned hostile is red");
		assertEquals(0xFFFFAA00, FigureRules.tagColour(1, true), "a hostile never looks less than high");
		assertEquals(0xFFFF5555, FigureRules.tagColour(4, true));
		assertEquals(TextColor.fromRgb(0xFF5555), FigureRules.tag("faction:Maelstrom", -1, true, false).getStyle().getColor());
		assertNull(FigureRules.tag("", -1, false, false).getStyle().getColor());
	}

	@Test
	void theLabelNamesRoleFactionOrModel() {
		assertEquals("Ripper Doc", FigureRules.tag("role:RipperDoc", -1, false, false).getString());
		assertEquals("Tyger Claws", FigureRules.tag("faction:TygerClaws", -1, false, false).getString());
		assertEquals("NCPD", FigureRules.tag("faction:NCPD", -1, false, false).getString());
		assertEquals("Armored Van", FigureRules.tag("vehicle:armored_van", -1, false, true).getString());
		assertEquals("Civilian", FigureRules.tag("", -1, false, false).getString());
		assertEquals("Vehicle", FigureRules.tag("", -1, false, true).getString());
		assertEquals("Civilian", FigureRules.tag("bogus:thing", -1, false, false).getString(), "an unknown label kind is no label");
	}

	@Test
	void skinsAreTheirTextures() {
		assertEquals(Identifier.fromNamespaceAndPath("cyberblock", "textures/entity/npc/tyger_claws_f.png"),
			FigureRules.skinTexture("cyberblock:textures/entity/npc/tyger_claws_f.png"));
		assertEquals(Identifier.fromNamespaceAndPath("friend", "textures/entity/npc/punk.png"), FigureRules.skinTexture("friend:textures/entity/npc/punk.png"));
		assertEquals(FigureRules.NEUTRAL, FigureRules.skinTexture("neutral"));
		assertEquals(FigureRules.NEUTRAL, FigureRules.skinTexture(""));
		assertEquals(FigureRules.NEUTRAL, FigureRules.skinTexture("Bad Name!"), "an id no texture can have is the neutral skin");
	}

	@Test
	void unknownVehicleModelsAreSedans() {
		assertEquals(VehicleModel.POLICE_CRUISER, FigureRules.vehicle("police_cruiser"));
		assertEquals(VehicleModel.SEDAN, FigureRules.vehicle("hovercraft"));
	}

	@Test
	void hostilesAreOutlinedThroughWallsOncePerClient() {
		assertTrue(FigureRules.outlined(true, false, 40 * 40));
		assertTrue(FigureRules.outlined(true, false, 20 * 20), "near ones too: the Kiroshi box does not show through walls");
		assertFalse(FigureRules.outlined(true, false, 49 * 49), "beyond the host's 48 m list");
		assertFalse(FigureRules.outlined(false, false, 4), "only hostiles");
		assertFalse(FigureRules.outlined(true, true, 4), "never a body");
	}
}
