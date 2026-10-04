package dev.cyberblock.client.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.client.ui.UiModel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Atlas messages (protocol.md "Atlas"), the survey's done stops, and the pins' order and district labels. */
class AtlasWireTest {
	private static JsonObject json(final String text) {
		return JsonParser.parseString(text.replace('\'', '"')).getAsJsonObject();
	}

	@Test
	void terrainExampleAndLimits() {
		int[] c = AtlasWire.parseTerrain(json("{'t':'terrain','c':[-623,-407,67,0,-623,-406,12,0,-700,-380,0,1]}"));
		assertArrayEquals(new int[] {-623, -407, 67, 0, -623, -406, 12, 0, -700, -380, 0, 1}, c);
		assertNull(AtlasWire.parseTerrain(json("{'c':[1,2,3]}")));
		assertNull(AtlasWire.parseTerrain(json("{'c':[1,2,3,2]}")));
		assertNull(AtlasWire.parseTerrain(json("{'c':[1,2,20001,0]}")));
		assertNull(AtlasWire.parseTerrain(json("{'c':[15000000,2,3,0]}")));
		assertNull(AtlasWire.parseTerrain(json("{'c':[1.5,2,3,0]}")));
		assertNull(AtlasWire.parseTerrain(json("{'c':[]}")));
		assertNull(AtlasWire.parseTerrain(json("{'c':'1,2,3,0'}")));
		StringBuilder many = new StringBuilder();
		for (int i = 0; i < 65; i++) {
			many.append(i == 0 ? "" : ",").append("1,2,3,0");
		}

		assertNull(AtlasWire.parseTerrain(json("{'c':[" + many + "]}")));
		assertNotNull(AtlasWire.parseTerrain(json("{'c':[-14999999,14999999,-20000,1]}")));
	}

	@Test
	void surveyStateExampleAndLimits() {
		AtlasWire.SurveyState s = AtlasWire.parseSurveyState(json("{'t':'surveystate','state':'running','done':12,'total':980,'eta':11520,'reason':'','stop':[-6,4]}"));
		assertNotNull(s);
		assertTrue(s.running());
		assertArrayEquals(new int[] {-6, 4}, s.stop());
		assertEquals(11520.0, s.eta());
		AtlasWire.SurveyState idle = AtlasWire.parseSurveyState(json("{'state':'idle','done':0,'total':0,'eta':-1,'reason':''}"));
		assertFalse(idle.running());
		assertNull(idle.stop());
		assertNull(AtlasWire.parseSurveyState(json("{'state':'flying','done':0,'total':0,'eta':-1,'reason':''}")));
		assertNull(AtlasWire.parseSurveyState(json("{'state':'done','done':5,'total':4,'eta':-1,'reason':''}")));
		assertNull(AtlasWire.parseSurveyState(json("{'state':'done','done':-1,'total':4,'eta':-1,'reason':''}")));
		assertNull(AtlasWire.parseSurveyState(json("{'state':'done','done':1,'total':4,'eta':-2,'reason':''}")));
		assertNull(AtlasWire.parseSurveyState(json("{'state':'done','done':1,'total':4,'eta':-1,'reason':'" + "r".repeat(65) + "'}")));
		assertNull(AtlasWire.parseSurveyState(json("{'state':'running','done':1,'total':4,'eta':5,'reason':'','stop':[1]}")));
	}

	@Test
	void surveyRequestsListTheDoneStopsForStartOnly() {
		JsonObject start = json(AtlasWire.survey("start", List.of(AtlasTiles.key(-6, 4), AtlasTiles.key(-5, 4))));
		assertEquals("survey", start.get("t").getAsString());
		assertEquals("[-6,4,-5,4]", start.get("done").toString());
		assertFalse(json(AtlasWire.survey("stop", List.of(AtlasTiles.key(1, 1)))).has("done"));
		assertEquals("status", json(AtlasWire.survey("status", List.of())).get("op").getAsString());
		assertThrows(IllegalArgumentException.class, () -> AtlasWire.survey("pause", List.of()));
	}

	@Test
	void aStopIsDoneWhenReportedOrWhenItsSquareIsMapped() {
		AtlasTiles tiles = new AtlasTiles();
		// map stop (1, -1)'s square (Cyberpunk (200, -200) = Minecraft x 200, z 200) completely
		int[] r = AtlasWire.stopCells(1, -1);
		assertArrayEquals(new int[] {45, 45, 155, 155}, r);
		for (int z = r[1]; z <= r[3]; z++) {
			for (int x = r[0]; x <= r[2]; x++) {
				tiles.put(x, z, 5, false);
			}
		}

		List<Long> done = AtlasWire.doneStops(tiles, List.of(AtlasTiles.key(-20, 30)));
		assertTrue(done.contains(AtlasTiles.key(1, -1)));
		assertTrue(done.contains(AtlasTiles.key(-20, 30)));
		assertFalse(done.contains(AtlasTiles.key(0, 0))); // half of its square at most
		assertTrue(AtlasWire.doneStops(new AtlasTiles(), List.of()).isEmpty());
	}

	@Test
	void etaReadsLikeTheHud() {
		assertEquals("unknown time", AtlasWire.eta(-1));
		assertEquals("under a minute", AtlasWire.eta(20));
		assertEquals("12m", AtlasWire.eta(720));
		assertEquals("3h 12m", AtlasWire.eta(11520));
	}

	@Test
	void pinsDrawMostImportantFirstAndFiltersHideKinds() {
		UiModel.Pos p = new UiModel.Pos(0, 0, 0);
		List<UiModel.ReplyPin> pins = List.of(new UiModel.ReplyPin(p, "vendor", "Shop", -1, ""), new UiModel.ReplyPin(p, "quest", "Job", -1, ""),
			new UiModel.ReplyPin(p, "other", "?", -1, ""), new UiModel.ReplyPin(p, "fasttravel", "Metro", -1, "Watson"));
		assertEquals(List.of(1, 3, 0, 2), AtlasPins.drawOrder(pins, Set.of(AtlasPins.Filter.values()), -1));
		assertEquals(List.of(2, 1, 3), AtlasPins.drawOrder(pins, Set.of(AtlasPins.Filter.QUESTS, AtlasPins.Filter.TRAVEL), 2)); // the selected pin always shows, first
		assertEquals(AtlasPins.Filter.SHOPS, AtlasPins.Filter.of("ripperdoc"));
		assertEquals(AtlasPins.Filter.PLACES, AtlasPins.Filter.of("weird"));
		assertEquals("Fast travel point", AtlasPins.kindName("fasttravel"));
	}

	@Test
	void districtLabelsSitAmongTheirFastTravelPointsElseWhereVWent() {
		List<UiModel.ReplyPin> pins = List.of(new UiModel.ReplyPin(new UiModel.Pos(0, 0, 0), "fasttravel", "A", -1, "Watson"),
			new UiModel.ReplyPin(new UiModel.Pos(100, 0, 50), "fasttravel", "B", -1, "Watson"), new UiModel.ReplyPin(new UiModel.Pos(9, 9, 9), "vendor", "C", -1, ""));
		Map<String, double[]> centres = AtlasPins.districtCentres(pins, Map.of("Watson", new double[] {999, 999}, "Pacifica", new double[] {-5, 7}));
		assertArrayEquals(new double[] {50, 25}, centres.get("Watson"), 1e-9);
		assertArrayEquals(new double[] {-5, 7}, centres.get("Pacifica"), 1e-9);
		assertEquals(2, centres.size());
	}

	@Test
	void regionTexturesPickTheBlockCentreElseAnyKnownCell() {
		short[] cells = new short[16 * 16];
		cells[4 * 16 + 4] = AtlasTiles.encode(30, false);   // block (0, 0): its centre
		cells[8 * 16 + 9] = AtlasTiles.encode(7, true);     // block (1, 1): not its centre
		short[] small = AtlasColours.downsample(cells, 16, 8);
		assertEquals(30, AtlasTiles.height(small[0]));
		assertEquals(0, small[1]);
		assertTrue(AtlasTiles.water(small[3]));
	}
}
