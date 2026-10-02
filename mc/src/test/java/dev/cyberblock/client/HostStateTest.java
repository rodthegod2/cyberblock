package dev.cyberblock.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
}
