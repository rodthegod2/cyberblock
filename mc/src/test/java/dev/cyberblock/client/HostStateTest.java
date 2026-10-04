package dev.cyberblock.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Bridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostStateTest {
	/** Gson's parseString is lenient, like the host link's: NaN, Infinity and single quotes all parse. */
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void keepsAGoodPose() {
		HostState.update(json("{'f':7,'p':[1.5,70,-3],'r':[10,20,5],'fov':60,'fp':false,'pl':[1,69,-3],'h':15}"));
		HostState.Pose p = HostState.live();
		assertEquals(7L, p.hostFrame());
		assertEquals(1.5, p.x());
		assertEquals(70.0, p.y());
		assertEquals(-3.0, p.z());
		assertEquals(10.0F, p.yaw());
		assertEquals(20.0F, p.pitch());
		assertEquals(5.0F, p.roll());
		assertEquals(60.0F, p.fov());
		assertFalse(p.firstPerson());
		assertEquals(69.0, p.py());
		assertEquals(15.0F, p.bodyYaw());
	}

	@Test
	void fillsInDefaults() {
		HostState.update(json("{'p':[1,2,3],'r':[30,0]}"));
		HostState.Pose p = HostState.live();
		assertEquals(70.0F, p.fov());
		assertTrue(p.firstPerson());
		assertEquals(0.0F, p.roll());
		assertEquals(30.0F, p.bodyYaw());
		assertEquals(1.0, p.px());
		assertEquals(30.0F, p.lookYaw());
	}

	@Test
	void acceptsTheEdgesOfTheRanges() {
		HostState.update(json("{'p':[2.9e7,-20000,-2.9e7],'r':[-720,-90,0],'fov':1}"));
		HostState.update(json("{'p':[0,20000,0],'r':[720,90,0],'fov':179}"));
		assertEquals(179.0F, HostState.live().fov());
	}

	/** The host's asin(-dy) can round to just past +-90 degrees. */
	@Test
	void clampsPitch() {
		HostState.update(json("{'p':[0,0,0],'r':[0,91.3,0],'look':[0,-120]}"));
		assertEquals(90.0F, HostState.live().pitch());
		assertEquals(-90.0F, HostState.live().lookPitch());
	}

	/** Each of these used to reach player.setPos (NaN gets the player kicked by the server) or the projection (a zero fov). */
	@ParameterizedTest
	@ValueSource(strings = {
		"{'p':[NaN,0,0],'r':[0,0,0]}",
		"{'p':['NaN',0,0],'r':[0,0,0]}",
		"{'p':[0,Infinity,0],'r':[0,0,0]}",
		"{'p':[0,0,-Infinity],'r':[0,0,0]}",
		"{'p':[3e7,0,0],'r':[0,0,0]}",
		"{'p':[1e12,0,0],'r':[0,0,0]}",
		"{'p':[0,0,-1e12],'r':[0,0,0]}",
		"{'p':[0,20001,0],'r':[0,0,0]}",
		"{'p':[0,-1e9,0],'r':[0,0,0]}",
		"{'p':[0,0,0],'r':[NaN,0,0]}",
		"{'p':[0,0,0],'r':[0,NaN,0]}",
		"{'p':[0,0,0],'r':[0,0,NaN]}",
		"{'p':[0,0,0],'r':[Infinity,0,0]}",
		"{'p':[0,0,0],'r':[0,-Infinity,0]}",
		"{'p':[0,0,0],'r':[1e300,0,0]}",
		"{'p':[0,0,0],'r':[0,0,0],'fov':NaN}",
		"{'p':[0,0,0],'r':[0,0,0],'fov':Infinity}",
		"{'p':[0,0,0],'r':[0,0,0],'fov':0}",
		"{'p':[0,0,0],'r':[0,0,0],'fov':0.99}",
		"{'p':[0,0,0],'r':[0,0,0],'fov':180}",
		"{'p':[0,0,0],'r':[0,0,0],'pl':[NaN,0,0]}",
		"{'p':[0,0,0],'r':[0,0,0],'pl':[0,-Infinity,0]}",
		"{'p':[0,0,0],'r':[0,0,0],'pl':[0,0,5e7]}",
		"{'p':[0,0,0],'r':[0,0,0],'h':NaN}",
		"{'p':[0,0,0],'r':[0,0,0],'h':Infinity}",
		"{'p':[0,0,0],'r':[0,0,0],'look':[NaN,0]}",
		"{'p':[0,0,0],'r':[0,0,0],'look':[0,Infinity]}",
	})
	void rejectsBadValuesAndKeepsThePreviousPose(final String bad) {
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0]}"));
		assertThrows(IllegalArgumentException.class, () -> HostState.update(json(bad)));
		assertEquals(1.0, HostState.live().x());
	}

	@Test
	void crouchFollowsCamCr() {
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'cr':true}"));
		assertTrue(Bridge.hostCrouching);
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0]}"));
		assertFalse(Bridge.hostCrouching); // a host without layer 3 never sends it
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'cr':true}"));
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'cr':'yes'}"));
		assertFalse(Bridge.hostCrouching);
	}

	@Test
	void aRejectedCamKeepsTheCrouch() {
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'cr':true}"));
		assertThrows(IllegalArgumentException.class, () -> HostState.update(json("{'p':[NaN,2,3],'r':[0,0,0],'cr':false}")));
		assertTrue(Bridge.hostCrouching);
		HostState.resetKeys();
		assertFalse(Bridge.hostCrouching);
	}

	@Test
	void flightFlagsFollowTheCam() {
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'fly':true,'menu':true,'air':true,'held':true}"));
		HostState.Pose p = HostState.live();
		assertTrue(p.fly());
		assertTrue(p.menu());
		assertTrue(p.air());
		assertTrue(p.held());
		assertTrue(Bridge.hostHeld);
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'fly':false,'air':true}"));
		p = HostState.live();
		assertFalse(p.fly());
		assertFalse(p.menu(), "absent: an older host never sends it");
		assertTrue(p.air());
		assertFalse(p.held());
		assertFalse(Bridge.hostHeld);
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'fly':'yes','menu':1,'air':null,'held':[true]}"));
		p = HostState.live();
		assertFalse(p.fly() || p.menu() || p.air() || p.held(), "only a JSON boolean counts");
	}

	/** Plan retest3 section 1.1: cam.seat (V sits in a vehicle, pl is the hips); false when absent or not a boolean. */
	@Test
	void seatFollowsTheCam() {
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'fp':false,'pl':[1,1.2,3],'seat':true}"));
		HostState.Pose p = HostState.live();
		assertTrue(p.seat());
		assertFalse(p.firstPerson());
		assertEquals(1.2, p.py());
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0]}"));
		assertFalse(HostState.live().seat(), "absent: an older host never sends it");
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'seat':'yes'}"));
		assertFalse(HostState.live().seat(), "only a JSON boolean counts");
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'seat':false}"));
		assertFalse(HostState.live().seat());
	}

	@Test
	void theServerHearsWhenVIsInAVehicle() {
		HostState.setVehicle(true);
		assertTrue(HostState.vehicle());
		assertTrue(Bridge.hostInVehicle);
		HostState.setVehicle(false);
		assertFalse(Bridge.hostInVehicle);
	}

	@Test
	void theHostGoingAwayReleasesV() {
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'held':true}"));
		assertTrue(Bridge.hostHeld);
		HostState.resetKeys();
		assertFalse(Bridge.hostHeld);
	}

	@Test
	void eachJumpPressIsTakenOnce() {
		HostState.resetKeys();
		HostState.setKey("jump", true);
		HostState.setKey("jump", true); // key repeat is not a new press
		assertTrue(HostState.jumpDown());
		assertTrue(HostState.takeJumpPress());
		assertFalse(HostState.takeJumpPress());
		HostState.setKey("jump", false);
		assertFalse(HostState.jumpDown());
		assertFalse(HostState.takeJumpPress());
		HostState.setKey("sprint", true);
		assertTrue(HostState.sprintDown());
		HostState.resetKeys();
		assertFalse(HostState.sprintDown());
	}

	/** Plan retest2 section 1.2: cam.lv is the k the host converted with; y is corrected to this world's k until the host has the new one. */
	@Test
	void camLevelIsCorrectedToTheWorldsAlignment() {
		Bridge.alignK = 57;
		try {
			// the host still converts with k 0: its y is 57/64 m too high for this world
			HostState.update(json("{'p':[1,70,3],'r':[0,0,0],'pl':[1,68.5,3],'lv':0}"));
			HostState.Pose p = HostState.live();
			assertEquals(70.0 - 57.0 / 64.0, p.y(), 1e-12);
			assertEquals(68.5 - 57.0 / 64.0, p.py(), 1e-12);
			assertEquals(1.0, p.px());
			assertEquals(3.0, p.z());
			assertEquals(68.5, Bridge.hostFeetCpZ, 1e-12, "V's feet in Cyberpunk z: pl.y + lv/64");
			// the host has the new k: nothing to correct
			HostState.update(json("{'p':[1,70,3],'r':[0,0,0],'pl':[1,68.5,3],'lv':57}"));
			p = HostState.live();
			assertEquals(70.0, p.y());
			assertEquals(68.5, p.py());
			assertEquals(68.5 + 57.0 / 64.0, Bridge.hostFeetCpZ, 1e-12);
			// absent (fakehost, an older host): taken to be this world's k
			HostState.update(json("{'p':[1,70,3],'r':[0,0,0],'pl':[1,68.5,3]}"));
			assertEquals(68.5, HostState.live().py());
			assertEquals(68.5 + 57.0 / 64.0, Bridge.hostFeetCpZ, 1e-12);
		} finally {
			Bridge.alignK = 0;
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"'lv':64", "'lv':-1", "'lv':1.5", "'lv':NaN", "'lv':'x'", "'lv':Infinity"})
	void aBadCamLevelRejectsThePose(final String bad) {
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0]}"));
		assertThrows(RuntimeException.class, () -> HostState.update(json("{'p':[9,2,3],'r':[0,0,0]," + bad + "}")));
		assertEquals(1.0, HostState.live().x());
	}

	@Test
	void standingAndAirborneFollowTheCam() {
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0]}"));
		assertTrue(Bridge.hostStands);
		assertFalse(Bridge.hostAirborne);
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'air':true}"));
		assertFalse(Bridge.hostStands);
		assertTrue(Bridge.hostAirborne);
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'held':true}"));
		assertFalse(Bridge.hostStands, "held by a scene");
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0],'fly':true}"));
		assertFalse(Bridge.hostStands, "following a flight");
		assertFalse(Bridge.hostAirborne);
		HostState.update(json("{'p':[1,2,3],'r':[0,0,0]}"));
		HostState.resetKeys();
		assertFalse(Bridge.hostStands, "the host went away: nothing is known of V");
		assertTrue(Double.isNaN(Bridge.hostFeetCpZ));
	}

	@Test
	void everyJumpKeyDownCountsOnce() {
		HostState.resetKeys();
		int before = Bridge.hostJumpPresses.get();
		HostState.setKey("jump", true);
		HostState.setKey("jump", true); // key repeat
		assertEquals(before + 1, Bridge.hostJumpPresses.get());
		HostState.takeJumpPress(); // taking the press does not consume the counter
		assertEquals(before + 1, Bridge.hostJumpPresses.get());
		HostState.setKey("jump", false);
		assertEquals(before + 1, Bridge.hostJumpPresses.get());
		HostState.setKey("jump", true);
		assertEquals(before + 2, Bridge.hostJumpPresses.get());
		HostState.setKey("sprint", true);
		assertEquals(before + 2, Bridge.hostJumpPresses.get());
		HostState.resetKeys();
	}

	private static final long MS = 1_000_000L;
	private static final double EPS = 1e-9;

	private static void aim(final double d, final long now) {
		HostState.updateAim(json("{'t':'aim','d':" + d + "}"), now);
	}

	/** A fresh host: the first distance is believed at once (a rise from nothing would otherwise take 300 ms). */
	private static void freshAim(final double d) {
		HostState.resetAim();
		aim(d, 0L);
		aim(d, 0L);
		aim(d, 0L);
	}

	@Test
	void aimLimitsReachWithAMargin() {
		freshAim(2.5);
		assertEquals(2.5 + HostState.AIM_MARGIN, HostState.aimLimit(4.5, 0L), EPS);
		assertEquals(1.0, HostState.aimLimit(1.0, 0L)); // a shorter reach stays as it is
	}

	@Test
	void noSurfaceMeansFullReach() {
		freshAim(2.5);
		aim(-1, 0L);
		assertEquals(4.5, HostState.aimLimit(4.5, 0L), "no surface rises at once");
		assertEquals(-1.0, HostState.aimRaw());
	}

	@ParameterizedTest
	@ValueSource(strings = {"{'d':'NaN'}", "{'d':'Infinity'}", "{'d':1e9}", "{}"})
	void badAimIsRejectedAndKeepsTheLastOne(final String bad) {
		freshAim(3.0);
		assertThrows(RuntimeException.class, () -> HostState.updateAim(json(bad)));
		assertEquals(3.0 + HostState.AIM_MARGIN, HostState.aimLimit(4.5, 0L), EPS);
	}

	/** Plan Q-B1: the host's ray ends on the collision box at d; the block's own face is 2 mm further: inside the limit. */
	@Test
	void theBlockFaceJustBehindTheBoxFaceIsInsideTheLimit() {
		freshAim(2.437);
		Vec3 eye = new Vec3(0.5, 65.62, 0.5);
		Vec3 dir = new Vec3(0, 0, 1);
		double face = 2.437 + 0.002;
		AABB block = new AABB(0, 64, eye.z + face, 1, 65.99, eye.z + face + 1);
		Vec3 end = eye.add(dir.scale(HostState.aimLimit(4.5, 0L)));
		assertTrue(block.clip(eye, end).isPresent(), "the pick reaches the block");
	}

	/** Q-B4: a creative player aims at an outline block whose top face is exactly where the host's ray stops (a street floor). */
	@Test
	void anOutlineBlockAtTheAimDistanceItselfIsTargeted() {
		freshAim(1.62);
		Vec3 eye = new Vec3(0.5, 65.62, 0.5);
		Vec3 down = new Vec3(0, -1, 0);
		AABB street = new AABB(0, 63, 0, 1, 64, 1); // the barrier under V's feet, its top at y 64
		Vec3 end = eye.add(down.scale(HostState.aimLimit(4.5, 0L)));
		assertTrue(street.clip(eye, end).isPresent());
	}

	@Test
	void aRealWallInFrontStillBlocksTheBlockBehindIt() {
		freshAim(1.5); // the wall
		Vec3 eye = new Vec3(0.5, 65.62, 0.5);
		AABB behind = new AABB(0, 65, eye.z + 2.5, 1, 66, eye.z + 3.5); // 1 m behind the wall face
		Vec3 end = eye.add(new Vec3(0, 0, 1).scale(HostState.aimLimit(4.5, 0L)));
		assertFalse(behind.clip(eye, end).isPresent());
	}

	@Test
	void oneShortSampleDoesNotChangeTheLimit() {
		freshAim(3.0);
		aim(1.0, 50 * MS); // a single dip (a hand, a passing NPC, a stray ray)
		assertEquals(3.0 + HostState.AIM_MARGIN, HostState.aimLimit(4.5, 60 * MS), EPS);
		aim(3.0, 100 * MS);
		assertEquals(3.0 + HostState.AIM_MARGIN, HostState.aimLimit(4.5, 500 * MS), EPS, "the dip is over");
	}

	@Test
	void theLimitFallsAfterThreeShorterSamples() {
		freshAim(3.0);
		aim(2.0, 100 * MS);
		aim(2.0, 110 * MS);
		assertEquals(3.0 + HostState.AIM_MARGIN, HostState.aimLimit(4.5, 120 * MS), EPS);
		aim(1.9, 130 * MS);
		assertEquals(1.9 + HostState.AIM_MARGIN, HostState.aimLimit(4.5, 130 * MS), EPS);
	}

	/** The host sends aim on change only: one shorter value that holds is believed after 300 ms with no further sample. */
	@Test
	void theLimitFallsAfter300MsOfAShorterValue() {
		freshAim(3.0);
		aim(2.0, 1000 * MS);
		assertEquals(3.0 + HostState.AIM_MARGIN, HostState.aimLimit(4.5, 1299 * MS), EPS);
		assertEquals(2.0 + HostState.AIM_MARGIN, HostState.aimLimit(4.5, 1300 * MS), EPS);
	}

	@Test
	void theLimitRisesAtOnce() {
		freshAim(1.0);
		aim(3.0, 10 * MS);
		assertEquals(3.0 + HostState.AIM_MARGIN, HostState.aimLimit(4.5, 10 * MS), EPS);
	}

	private static final BlockPos STONE = new BlockPos(3, 65, 7);

	@Test
	void stickinessKeepsTheBlockBeingBrokenFor500Ms() {
		HostState.resetAim();
		HostState.picked(STONE, true, 0L);
		assertEquals(STONE, HostState.stickyTarget(true, p -> true, 499 * MS));
		assertEquals(STONE, HostState.stickyTarget(true, p -> true, 500 * MS));
		assertNull(HostState.stickyTarget(true, p -> true, 501 * MS));
		assertNull(HostState.stickyTarget(true, p -> true, 0L), "once over, it stays over");
	}

	@Test
	void stickinessEndsOnRelease() {
		HostState.resetAim();
		HostState.picked(STONE, true, 0L);
		assertNull(HostState.stickyTarget(false, p -> true, 10 * MS));
		assertNull(HostState.stickyTarget(true, p -> true, 20 * MS));
	}

	@Test
	void stickinessEndsWhenTheBlockIsGone() {
		HostState.resetAim();
		HostState.picked(STONE, true, 0L);
		assertNull(HostState.stickyTarget(true, p -> false, 10 * MS));
		assertNull(HostState.stickyTarget(true, p -> true, 20 * MS));
	}

	@Test
	void stickinessFollowsTheLatestBlockAndNeedsAttack() {
		HostState.resetAim();
		HostState.picked(STONE, false, 0L);
		assertNull(HostState.stickyTarget(true, p -> true, 0L), "picked without attack: nothing to keep");
		HostState.picked(STONE, true, 0L);
		HostState.picked(null, true, 400 * MS); // the limit dipped: the pick found nothing, the old block stays
		assertEquals(STONE, HostState.stickyTarget(true, p -> true, 450 * MS));
		HostState.picked(STONE.above(), true, 460 * MS);
		assertEquals(STONE.above(), HostState.stickyTarget(true, p -> true, 900 * MS));
	}
}
