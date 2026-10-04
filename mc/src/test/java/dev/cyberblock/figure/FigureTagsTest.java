package dev.cyberblock.figure;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Megaplan section 1.9 (Phase 5 `npcs` tags, as amended 2026-10-04) as the server reads them for the figures (task 5-F1). */
class FigureTagsTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void everyTagIsRead() {
		FigureTags t = FigureTags.of(json("{\"id\":\"5\",\"role\":\"RipperDoc\",\"fac\":\"TygerClaws\",\"g\":2,\"arch\":\"AndroidRanged\","
			+ "\"anim\":141,\"vc\":\"Car/Turbo\",\"col\":\"#1A2b3C\",\"hp\":0.25}"));
		assertEquals(new FigureTags("RipperDoc", "TygerClaws", 2, "AndroidRanged", 141, "Car/Turbo", 0x1A2B3C, 0.25F), t);
		assertTrue(t.has(FigureTags.WALK) && t.has(FigureTags.AIM) && t.has(FigureTags.FIRE) && t.has(FigureTags.COMBAT));
		assertFalse(t.has(FigureTags.RUN) || t.has(FigureTags.RAGDOLL) || t.has(FigureTags.DEAD) || t.has(FigureTags.CROUCH));
	}

	@Test
	void missingTagsAreTheFallback() {
		assertEquals(FigureTags.NONE, FigureTags.of(json("{\"id\":\"5\"}")));
	}

	@Test
	void badTagsReadAsMissingAndNeverCostTheEntry() {
		String[][] bad = {{"g", "3"}, {"g", "1.5"}, {"g", "\"1\""}, {"anim", "256"}, {"anim", "-1"}, {"anim", "2.5"}, {"col", "\"red\""},
			{"col", "\"#12345\""}, {"col", "1193046"}, {"hp", "1.01"}, {"hp", "-0.1"}, {"hp", "\"0.5\""}, {"role", "5"},
			{"fac", "\"" + "x".repeat(33) + "\""}, {"vc", "[\"Car\"]"}};
		for (String[] b : bad) {
			FigureTags t = FigureTags.of(json("{\"id\":\"5\",\"" + b[0] + "\":" + b[1] + "}"));
			assertEquals(FigureTags.NONE, t, b[0] + "=" + b[1]);
		}
	}

	@Test
	void aMessageGivesTheTagsById() {
		FigureTags.Parsed p = FigureTags.parse(json("{\"t\":\"npcs\",\"list\":["
			+ "{\"id\":\"18446744073709551615\",\"p\":[0,64,0],\"yaw\":0,\"kind\":\"human\",\"fac\":\"NCPD\",\"hp\":0.5},"
			+ "{\"id\":\"7\",\"p\":[0,64,0],\"yaw\":0,\"kind\":\"vehicle\",\"vc\":\"Car/Turbo\"},"
			+ "{\"id\":\"18446744073709551616\",\"fac\":\"Bad\"},{\"id\":7,\"fac\":\"Bad\"},\"junk\"]}"));
		assertEquals(2, p.byId().size());
		assertEquals("NCPD", p.byId().get(-1L).fac());
		assertEquals(0.5F, p.byId().get(-1L).hp());
		assertEquals("Car/Turbo", p.byId().get(7L).vc());
		assertEquals(Set.of("fac", "hp", "vc", FigureTags.VEHICLES), p.seen(), "a vehicle came, but without a fac of its own");
	}

	@Test
	void noListNoTags() {
		FigureTags.Parsed p = FigureTags.parse(json("{\"t\":\"npcs\"}"));
		assertTrue(p.byId().isEmpty() && p.seen().isEmpty());
	}
}
