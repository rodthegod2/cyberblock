package dev.cyberblock.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Bridge;
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

	@Test
	void aimLimitsReach() {
		HostState.updateAim(json("{'t':'aim','d':2.5}"));
		assertEquals(2.5, HostState.aimLimit(4.5));
		assertEquals(1.0, HostState.aimLimit(1.0)); // a shorter reach stays as it is
	}

	@Test
	void noSurfaceMeansFullReach() {
		HostState.updateAim(json("{'t':'aim','d':-1}"));
		assertEquals(4.5, HostState.aimLimit(4.5));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{'d':'NaN'}", "{'d':'Infinity'}", "{'d':1e9}", "{}"})
	void badAimIsRejectedAndKeepsTheLastOne(final String bad) {
		HostState.updateAim(json("{'d':3.0}"));
		assertThrows(RuntimeException.class, () -> HostState.updateAim(json(bad)));
		assertEquals(3.0, HostState.aimLimit(4.5));
	}
}
