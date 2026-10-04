package dev.cyberblock.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.client.settings.DebugFlags;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ClientInputTest {
	/** Plan retest3 section 1.1: {"t":"persp","mode":"first"|"back"}. */
	@Test
	void perspectiveMessages() {
		JsonObject back = JsonParser.parseString(ClientInput.perspMessage(true)).getAsJsonObject();
		assertEquals("persp", back.get("t").getAsString());
		assertEquals("back", back.get("mode").getAsString());
		assertEquals("first", JsonParser.parseString(ClientInput.perspMessage(false)).getAsJsonObject().get("mode").getAsString());
	}

	/** After every host hello the chosen view goes to the host again (first person until F5 is pressed). */
	@Test
	void theViewIsResentToTheHost() {
		List<String> sent = new ArrayList<>();
		Consumer<String> was = HostLink.swapOut(sent::add);
		try {
			ClientInput.sendPerspective();
		} finally {
			HostLink.swapOut(was);
		}

		assertEquals(List.of(ClientInput.perspMessage(false)), sent);
	}

	/** A KeyMapping stand-in: its down state and its click count, as vanilla reads them. */
	private static final class FakeKey implements ClientInput.Key {
		boolean down;
		int clicks;

		@Override
		public boolean isDown() {
			return this.down;
		}

		@Override
		public void setDown(final boolean d) {
			this.down = d;
		}

		@Override
		public void click() {
			this.clicks++;
		}
	}

	/** Plan Q-B2: the host's key auto-repeat sends "down" again and again; breaking needs the key held, with one click per press. */
	@Test
	void attackDownHoldsTheKeyUntilUpWithOneClick() {
		FakeKey attack = new FakeKey();
		for (int i = 0; i < 20; i++) {
			ClientInput.HeldKeys.apply(attack, true);
			assertTrue(attack.down);
		}

		assertEquals(1, attack.clicks, "no synthetic click per message");
		ClientInput.HeldKeys.apply(attack, false);
		assertFalse(attack.down);
		ClientInput.HeldKeys.apply(attack, true);
		assertEquals(2, attack.clicks, "a new press is a new click");
	}

	/** A screen opens while attack is held (vanilla releases every key), the host keeps holding: breaking goes on after it closes. */
	@Test
	void aScreenDoesNotReleaseAHeldAttackEarly() {
		ClientInput.HeldKeys held = new ClientInput.HeldKeys();
		FakeKey attack = new FakeKey();
		held.host("attack", true);
		ClientInput.HeldKeys.apply(attack, true);
		attack.down = false; // Gui.setScreen -> KeyMapping.releaseAll
		held.screenClosed("attack", attack);
		assertTrue(attack.down);
		assertEquals(1, attack.clicks, "putting it back is not a new click");
	}

	/** The host lets go while the screen is open (the "up" only reaches the screen): the key is not stuck down afterwards. */
	@Test
	void aReleaseDuringAScreenIsNotLost() {
		ClientInput.HeldKeys held = new ClientInput.HeldKeys();
		FakeKey attack = new FakeKey();
		held.host("attack", true);
		ClientInput.HeldKeys.apply(attack, true);
		held.host("attack", false); // while the screen is open
		attack.down = true; // whatever the KeyMapping says now
		held.screenClosed("attack", attack);
		assertFalse(attack.down);
	}

	@Test
	void heldKeysTrackAttackAndUseOnly() {
		ClientInput.HeldKeys held = new ClientInput.HeldKeys();
		held.host("use", true);
		held.host("pick", true);
		assertTrue(held.holds("use"));
		assertFalse(held.holds("attack"));
		assertFalse(held.holds("pick"));
		held.clear();
		assertFalse(held.holds("use"));
	}

	/** Outside a client tick the mouse is never reported grabbed (mouse look and Window.onResize keep the truth). */
	@Test
	void theMouseIsOnlyGrabbedInsideATickWhileTheHostDrives() {
		assertFalse(ClientInput.grabsMouse());
		ClientInput.tickStart();
		try {
			HostState.update(JsonParser.parseString("{'p':[0,64,0],'r':[0,0,0]}").getAsJsonObject());
			assertTrue(ClientInput.grabsMouse());
		} finally {
			ClientInput.tickOver();
		}

		assertFalse(ClientInput.grabsMouse());
	}

	/** Plan Q-B3: /cyberblock debug aim|view on|off is this client's; anything else goes to the server. */
	@Test
	void debugSwitchesAreParsedOnTheClient() {
		assertEquals(new DebugFlags.Switch("aim", true), DebugFlags.parse("cyberblock debug aim on"));
		assertEquals(new DebugFlags.Switch("view", false), DebugFlags.parse("cyberblock  debug view off "));
		assertEquals(new DebugFlags.Switch("aim", null), DebugFlags.parse("cyberblock debug aim"));
		assertNull(DebugFlags.parse("cyberblock debug fly on"));
		assertNull(DebugFlags.parse("cyberblock debug aim maybe"));
		assertNull(DebugFlags.parse("cyberblock debug aim on now"));
		assertNull(DebugFlags.parse("cyberblock level"));
		assertNull(DebugFlags.parse("say cyberblock debug aim on"));
	}

	@Test
	void debugSwitchesStartOffAndFlip() {
		assertFalse(DebugFlags.view());
		DebugFlags.set("view", true);
		assertTrue(DebugFlags.view());
		DebugFlags.set("view", false);
		assertFalse(DebugFlags.view());
		assertThrows(IllegalArgumentException.class, () -> DebugFlags.set("fly", true));
	}
}
