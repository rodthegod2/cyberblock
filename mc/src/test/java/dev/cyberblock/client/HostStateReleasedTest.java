package dev.cyberblock.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.cyberblock.client.hub.HubClient;
import dev.cyberblock.client.net.Relay;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Plan 7-H1: in the Afterlife hub Cyberpunk does not drive Steve, and the DLL's parked answer goes up to the server. */
class HostStateReleasedTest {
	private final BooleanSupplier linked = HostState.hostLinked;

	@AfterEach
	void reset() {
		HubClient.where(false);
		HostState.released = false;
		HostState.hostLinked = this.linked;
	}

	@Test
	void theLevelChangeReleasesSteveBeforeHisFirstTickInTheHub() {
		HostState.hostLinked = () -> true;
		cam(); // an active host sends cam every frame
		HubClient.where(true); // ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE: the respawn into cyberblock:afterlife, before any tick or frame
		assertNull(HostState.live(), "PlayerSync's first tick there never puts Steve at V's Night City position");
		HostState.beginFrame();
		assertNull(HostState.frame(), "nor does the first frame: his first position packet is the server's arrival point");
		assertFalse(HostState.hstate(false).active(), "and the server hears at once that Cyberpunk does not drive");
	}

	private static void cam() {
		HostState.update(JsonParser.parseString("{\"p\":[1,70,2],\"r\":[0,0]}").getAsJsonObject());
	}

	@Test
	void aReleasedHostHasNoLivePose() {
		HostState.hostLinked = () -> true;
		cam();
		assertNotNull(HostState.live());
		HostState.released = true;
		assertNull(HostState.live(), "in the hub Steve is Minecraft's");
		assertTrue(HostState.hostInput(), "and the DLL's keys and mouse drive him");
		assertFalse(HostState.hstate(false).active(), "and the server hears that Cyberpunk does not drive");
		HostState.beginFrame();
		assertNull(HostState.frame(), "nothing places Steve or the camera from Night City");
		assertNotNull(HostState.exportPose(), "but the frames keep going to the DLL (its full-screen hub mode)");
		HostState.released = false;
		assertFalse(HostState.hostInput(), "out of the hub the host's input is the ordinary path");
		assertNotNull(HostState.live(), "back in Night City the pose drives again");
	}

	@Test
	void withoutALinkedHostDllTheHubExportsNothing() {
		cam();
		HostState.released = true;
		HostState.hostLinked = () -> false;
		assertFalse(HostState.hostInput(), "no DLL (or a guest session): Minecraft's own window is the input");
		assertNull(HostState.exportPose(), "and Minecraft's own picture stays in its window");
	}

	@Test
	void aLinkedHostWithCyberblockOffInTheHubKeepsMinecraftsOwnInput() {
		HostState.hostLinked = () -> true; // Off keeps the link and the host role
		HostState.released = true;
		HostState.resetKeys();
		assertFalse(HostState.hostInput(), "no cam since the link came: the DLL is not showing the hub");
		cam();
		assertTrue(HostState.hostInput(), "cams coming (Active, V parked): the DLL shows the hub and forwards input");
		long off = System.nanoTime() + 6_000_000_000L;
		assertFalse(HostState.hostInput(off), "Off sends no cam: after 5 s the DLL no longer counts as showing the hub");
		assertFalse(HostState.live() != null || HostState.hostInput(off), "so the host's cursor is not the screens' cursor (HostCursor.active)");
		HostState.resetKeys(); // the cam is gone: live() is null
		HostCursor.hubInputEnded(); // what HubClient does as the hub input ends
		assertFalse(HostCursor.active(), "and its last position never stands in for the real mouse");
	}

	@Test
	void aDroppedLinkForgetsTheLastPose() {
		HostState.hostLinked = () -> true;
		cam();
		HostState.released = true;
		assertNotNull(HostState.exportPose());
		HostState.resetKeys();
		assertNull(HostState.exportPose(), "a pose from a gone DLL is never exported with");
	}

	@Test
	void parkedIsRelayedUp() {
		assertEquals(Relay.Route.UP, Relay.route("parked"));
	}
}
