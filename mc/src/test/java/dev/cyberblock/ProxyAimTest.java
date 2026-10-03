package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Plan retest2 M3: the scan pick from the proxies' server positions, forgiving by a margin around each box. */
class ProxyAimTest {
	private static final Vec3 EYE = new Vec3(0.0, 1.62, 0.0);
	private static final Vec3 FORWARD = new Vec3(0.0, 0.0, 1.0);

	/** A pedestrian's proxy (0.6 x 1.8) standing with its feet at (x, 0, z). */
	private static AABB human(final double x, final double z) {
		return new AABB(x - 0.3, 0.0, z - 0.3, x + 0.3, 1.8, z + 0.3);
	}

	private static Vec3 toward(final double x, final double y, final double z) {
		return new Vec3(x, y, z).subtract(EYE).normalize();
	}

	@Test
	void aimedAtTheCentre() {
		assertEquals(0.0, ProxyAim.score(EYE, toward(0.0, 0.9, 6.0), 48.0, human(0.0, 6.0), ProxyAim.HUMAN_MARGIN), 1e-6);
		// straight ahead through the box, a little above its centre
		double score = ProxyAim.score(EYE, FORWARD, 48.0, human(0.0, 6.0), ProxyAim.HUMAN_MARGIN);
		assertEquals(Math.toDegrees(Math.atan2(0.72, 6.0)), score, 1e-6);
	}

	/** In-game 2026-10-03: the exact camera ray had to cross a 0.6 m box that trails the real NPC. */
	@Test
	void aQuarterMetreBesideTheBoxStillPicksIt() {
		// the ray passes 0.25 m to the right of the box's edge (x 0.3) where it stands
		double score = ProxyAim.score(EYE, toward(0.55, 0.9, 6.0), 48.0, human(0.0, 6.0), ProxyAim.HUMAN_MARGIN);
		assertTrue(Double.isFinite(score), "picked");
		assertTrue(score > 0.0);
	}

	@Test
	void aMissIsNaN() {
		assertTrue(Double.isNaN(ProxyAim.score(EYE, toward(1.2, 0.9, 6.0), 48.0, human(0.0, 6.0), ProxyAim.HUMAN_MARGIN)));
		assertTrue(Double.isNaN(ProxyAim.score(EYE, FORWARD.scale(-1.0), 48.0, human(0.0, 6.0), ProxyAim.HUMAN_MARGIN)), "behind");
	}

	@Test
	void beyondReachIsNaN() {
		assertTrue(Double.isNaN(ProxyAim.score(EYE, FORWARD, 5.0, human(0.0, 6.0), ProxyAim.HUMAN_MARGIN)));
		assertTrue(Double.isFinite(ProxyAim.score(EYE, FORWARD, 5.5, human(0.0, 6.0), ProxyAim.HUMAN_MARGIN)), "the margin counts");
	}

	@Test
	void anEyeInsideTheBoxIsZero() {
		assertEquals(0.0, ProxyAim.score(new Vec3(0.1, 1.0, 6.0), FORWARD.scale(-1.0), 48.0, human(0.0, 6.0), ProxyAim.HUMAN_MARGIN));
	}

	@Test
	void badRaysAreNaN() {
		assertTrue(Double.isNaN(ProxyAim.score(EYE, Vec3.ZERO, 48.0, human(0.0, 6.0), ProxyAim.HUMAN_MARGIN)));
		assertTrue(Double.isNaN(ProxyAim.score(EYE, new Vec3(Double.NaN, 0.0, 1.0), 48.0, human(0.0, 6.0), ProxyAim.HUMAN_MARGIN)));
		assertTrue(Double.isNaN(ProxyAim.score(EYE, FORWARD, Double.NaN, human(0.0, 6.0), ProxyAim.HUMAN_MARGIN)));
		assertTrue(Double.isNaN(ProxyAim.score(EYE, FORWARD, 0.0, human(0.0, 6.0), ProxyAim.HUMAN_MARGIN)));
	}

	/** An unnormalized direction is the same ray. */
	@Test
	void theDirectionsLengthDoesNotMatter() {
		assertEquals(ProxyAim.score(EYE, FORWARD, 48.0, human(0.2, 6.0), ProxyAim.HUMAN_MARGIN),
			ProxyAim.score(EYE, FORWARD.scale(7.0), 48.0, human(0.2, 6.0), ProxyAim.HUMAN_MARGIN), 1e-9);
	}

	private record Proxy(String name, AABB box) {
	}

	private static String best(final Vec3 dir, final List<Proxy> proxies) {
		Proxy p = ProxyAim.best(EYE, dir, 48.0, proxies, Proxy::box, x -> ProxyAim.HUMAN_MARGIN);
		return p == null ? null : p.name();
	}

	@Test
	void theSmallerAngleWins() {
		// both within the margin of the ray; b is further but nearer the line of sight
		Proxy a = new Proxy("a", human(0.6, 4.0));
		Proxy b = new Proxy("b", human(0.1, 10.0));
		assertEquals("b", best(FORWARD, List.of(a, b)));
		assertEquals("b", best(FORWARD, List.of(b, a)));
	}

	@Test
	void anEqualAngleGoesToTheNearer() {
		// two boxes centred on the line of sight, one behind the other: both score 0
		Proxy far = new Proxy("far", new AABB(-0.3, 1.32, 11.7, 0.3, 1.92, 12.3));
		Proxy near = new Proxy("near", new AABB(-0.3, 1.32, 5.7, 0.3, 1.92, 6.3));
		assertEquals("near", best(FORWARD, List.of(far, near)));
		assertEquals("near", best(FORWARD, List.of(near, far)));
	}

	@Test
	void nothingInSightIsNull() {
		assertNull(best(FORWARD, List.of(new Proxy("aside", human(5.0, 6.0)))));
		assertNull(best(FORWARD, List.of()));
	}

	@Test
	void vehiclesGetAWiderMargin() {
		assertTrue(ProxyAim.VEHICLE_MARGIN > ProxyAim.HUMAN_MARGIN);
	}
}
