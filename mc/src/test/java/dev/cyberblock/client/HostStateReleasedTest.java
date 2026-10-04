package dev.cyberblock.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.cyberblock.client.net.Relay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Plan 7-H1: in the Afterlife hub Cyberpunk does not drive Steve, and the DLL's parked answer goes up to the server. */
class HostStateReleasedTest {
	@AfterEach
	void reset() {
		HostState.released = false;
	}

	@Test
	void aReleasedHostHasNoLivePose() {
		HostState.update(JsonParser.parseString("{\"p\":[1,70,2],\"r\":[0,0]}").getAsJsonObject());
		assertNotNull(HostState.live());
		HostState.released = true;
		assertNull(HostState.live(), "in the hub Steve is Minecraft's");
		assertFalse(HostState.hstate(false).active(), "and the server hears that Cyberpunk does not drive");
		HostState.released = false;
		assertNotNull(HostState.live(), "back in Night City the pose drives again");
	}

	@Test
	void parkedIsRelayedUp() {
		assertEquals(Relay.Route.UP, Relay.route("parked"));
	}
}
