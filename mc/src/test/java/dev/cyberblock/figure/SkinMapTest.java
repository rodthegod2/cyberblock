package dev.cyberblock.figure;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * Megaplan task 5-F1: the server picks each person's skin from skin_map in 5A's row format; missing data falls back to the faction
 * row, then the default rows, then neutral.
 */
class SkinMapTest {
	private static SkinMap.Row row(final String json) {
		return SkinMap.parse(Identifier.fromNamespaceAndPath("cyberblock", "test"), JsonParser.parseString(json).getAsJsonObject());
	}

	private static String tex(final String name) {
		return "cyberblock:textures/entity/npc/" + name + ".png";
	}

	private static String row(final String name, final String rest) {
		return "{\"schema\":1,\"texture\":\"" + tex(name) + "\"" + rest + "}";
	}

	private static FigureTags tags(final String role, final String fac, final int g, final String arch) {
		return new FigureTags(role, fac, g, arch, 0, "", -1, 1.0F);
	}

	private static final List<SkinMap.Row> ROWS = List.of(
		row(row("tyger_claws_m", ",\"model\":\"classic\",\"faction\":\"TygerClaws\"")),
		row(row("tyger_claws_f", ",\"model\":\"slim\",\"faction\":\"TygerClaws\",\"gender\":\"female\"")),
		row(row("tyger_netrunner_m", ",\"model\":\"classic\",\"faction\":\"Tyger Claws\",\"archetype\":\"Netrunner\"")),
		row(row("ncpd_m", ",\"model\":\"classic\",\"faction\":[\"NCPD\",\"Police\"]")),
		row(row("ripperdoc_m", ",\"role\":\"RipperDoc\"")),
		row(row("psycho_m", ",\"model\":\"classic\",\"archetype\":\"Cyberpsycho\",\"priority\":5")),
		row("{\"schema\":1,\"texture\":\"cyberblock:textures/entity/cyber_skin/v_male.png\",\"model\":\"classic\",\"cyber\":\"male\"}"),
		row(row("default_m", ",\"model\":\"classic\"")),
		row(row("default_f", ",\"model\":\"slim\",\"gender\":\"female\"")));

	@Test
	void moreMatchKeysWin() {
		assertEquals(new SkinMap.Skin(tex("tyger_claws_f"), true), SkinMap.resolve(ROWS, tags("", "TygerClaws", 2, ""), 1L));
		assertEquals(new SkinMap.Skin(tex("tyger_claws_m"), false), SkinMap.resolve(ROWS, tags("", "TygerClaws", 1, ""), 1L));
		assertEquals(new SkinMap.Skin(tex("tyger_netrunner_m"), false), SkinMap.resolve(ROWS, tags("", "TygerClaws", 1, "Netrunner"), 1L),
			"names match without case or spaces");
		assertEquals(new SkinMap.Skin(tex("ripperdoc_m"), false), SkinMap.resolve(ROWS, tags("RipperDoc", "", 0, ""), 1L));
		assertEquals(new SkinMap.Skin(tex("ripperdoc_m"), true), SkinMap.resolve(ROWS.subList(4, 5), tags("RipperDoc", "", 2, ""), 1L),
			"without a model a woman is slim");
		assertEquals(new SkinMap.Skin(tex("ncpd_m"), false), SkinMap.resolve(ROWS, tags("", "Police", 1, ""), 1L), "a list matches any of its values");
	}

	@Test
	void priorityBeatsMatchKeys() {
		assertEquals(tex("psycho_m"), SkinMap.resolve(ROWS, tags("", "TygerClaws", 2, "Cyberpsycho"), 1L).texture());
	}

	@Test
	void missingDataFallsBackToTheFactionRowThenTheDefaultThenNeutral() {
		assertEquals(new SkinMap.Skin(tex("tyger_claws_m"), false), SkinMap.resolve(ROWS, tags("", "TygerClaws", 0, ""), 1L),
			"unknown gender: the row without a gender, classic");
		assertEquals(new SkinMap.Skin(tex("default_f"), true), SkinMap.resolve(ROWS, tags("", "Militech", 2, ""), 1L));
		assertEquals(new SkinMap.Skin(tex("default_m"), false), SkinMap.resolve(ROWS, FigureTags.NONE, 1L), "no tags at all");
		assertEquals(new SkinMap.Skin(SkinMap.NEUTRAL, false), SkinMap.resolve(List.of(), tags("RipperDoc", "NCPD", 2, ""), 1L), "no data at all");
	}

	@Test
	void cyberSkinsAreNeverAnNpcs() {
		List<SkinMap.Row> rows = List.of(row("{\"schema\":1,\"texture\":\"cyberblock:textures/entity/cyber_skin/v_male.png\",\"model\":\"classic\",\"cyber\":\"male\"}"));
		assertEquals(SkinMap.NEUTRAL, SkinMap.resolve(rows, tags("", "", 1, ""), 1L).texture());
	}

	@Test
	void aTieIsSplitByTheIdStablyAndEvenly() {
		List<SkinMap.Row> rows = List.of(row(row("street_a", ",\"faction\":\"Civilian\"")), row(row("street_b", ",\"faction\":\"Civilian\"")),
			row(row("street_c", ",\"faction\":\"Civilian\"")));
		Set<String> worn = new HashSet<>();
		for (long id = 1; id <= 30; id++) {
			String a = SkinMap.resolve(rows, tags("", "Civilian", 1, ""), id).texture();
			assertEquals(a, SkinMap.resolve(rows, tags("", "Civilian", 1, ""), id).texture(), "the same NPC always wears the same");
			worn.add(a);
		}

		assertEquals(3, worn.size(), "a crowd wears a mix");
	}

	@Test
	void badRowsAreRefused() {
		String[] bad = {"{\"schema\":1}", "{\"schema\":1,\"texture\":\"Not An Id\"}", "{\"schema\":1,\"texture\":\"cyberblock:textures/npc/a.jpg\"}",
			row("a", ",\"model\":\"wide\""), row("a", ",\"gender\":\"other\""), row("a", ",\"faction\":[]"), row("a", ",\"faction\":\"--\""),
			row("a", ",\"faction\":5"), row("a", ",\"priority\":1.5"), "{\"schema\":1,\"texture\":\"cyberblock:" + "a".repeat(130) + ".png\"}"};
		for (String b : bad) {
			assertThrows(IllegalArgumentException.class, () -> row(b), b);
		}
	}

	@Test
	void namesMatchWithoutCaseOrPunctuation() {
		assertEquals("tygerclaws", SkinMap.key("Tyger Claws"));
		assertEquals("tygerclaws", SkinMap.key("tyger_claws"));
		assertEquals("6thstreet", SkinMap.key("6th-Street"));
	}
}
