package dev.cyberblock.client.figure;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

/** Plan 2026-10-04 task 3-F1: what guests see, and that an active host sees nothing. */
class FigureRulesTest {
	@Test
	void anActiveHostDrawsNoFigure() {
		assertFalse(FigureRules.drawn(true));
		assertTrue(FigureRules.drawn(false));
	}

	@Test
	void hostileTagsAreRed() {
		assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), FigureRules.tag(true, false).getStyle().getColor());
		assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED), FigureRules.tag(true, true).getStyle().getColor());
		assertNull(FigureRules.tag(false, false).getStyle().getColor());
		assertNull(FigureRules.tag(false, true).getStyle().getColor());
	}

	@Test
	void tagsShowNearAndNeverOnTheDead() {
		assertTrue(FigureRules.showTag(false, 11.9 * 11.9));
		assertFalse(FigureRules.showTag(false, 12.1 * 12.1));
		assertFalse(FigureRules.showTag(true, 1.0));
	}

	@Test
	void theCarIsSizedByTheProxy() {
		List<FigureRules.Part> car = FigureRules.car(3.0, 1.6, false);
		double minX = car.stream().mapToDouble(FigureRules.Part::x0).min().orElseThrow(), maxX = car.stream().mapToDouble(FigureRules.Part::x1).max().orElseThrow();
		double minZ = car.stream().mapToDouble(FigureRules.Part::z0).min().orElseThrow(), maxZ = car.stream().mapToDouble(FigureRules.Part::z1).max().orElseThrow();
		double minY = car.stream().mapToDouble(FigureRules.Part::y0).min().orElseThrow(), maxY = car.stream().mapToDouble(FigureRules.Part::y1).max().orElseThrow();
		assertEquals(2.1, maxX - minX, 1.0E-9, "0.7 of the proxy wide");
		assertEquals(4.5, maxZ - minZ, 1.0E-9, "1.5 of the proxy long");
		assertEquals(0.0, minY, 0.0, "on the ground");
		assertEquals(1.6, maxY, 1.0E-9, "as high as the proxy");
		assertEquals(6, car.size(), "body, cabin and four tyres");
		car.forEach(p -> assertTrue(p.x1() > p.x0() && p.y1() > p.y0() && p.z1() > p.z0(), "no empty box: " + p));
	}

	@Test
	void theCabinSitsBehindTheBonnet() {
		FigureRules.Part body = FigureRules.car(3.0, 1.6, false).get(0), cabin = FigureRules.car(3.0, 1.6, false).get(1);
		assertTrue(body.z1() - cabin.z1() > 1.0, "a bonnet of more than a metre in front (+z) of the cabin");
	}

	@Test
	void aWreckIsAllDark() {
		assertTrue(FigureRules.car(3.0, 1.6, true).stream().allMatch(p -> p.colour() == FigureRules.WRECK));
		assertTrue(FigureRules.car(3.0, 1.6, false).stream().noneMatch(p -> p.colour() == FigureRules.WRECK));
	}
}
