package dev.cyberblock.client.net;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ClientNetTest {
	/** Section 1.3: DOWN reaches the DLL only when this client is the host and its DLL is linked. */
	@Test
	void downIsForwardedOnlyForTheLinkedHost() {
		assertTrue(ClientNet.forwardsDown(ClientNet.Role.HOST, true));
		assertFalse(ClientNet.forwardsDown(ClientNet.Role.HOST, false));
		assertFalse(ClientNet.forwardsDown(ClientNet.Role.GUEST, true));
		assertFalse(ClientNet.forwardsDown(ClientNet.Role.NONE, true));
	}

	@Test
	void theDownTypeIsReadFromTheFront() {
		assertEquals("blocks", ClientNet.type("{\"t\":\"blocks\",\"reset\":true,\"c\":[" + "1,".repeat(1000) + "1]}"));
		assertEquals("survey_cmd", ClientNet.type("{\"t\":\"survey_cmd\",\"op\":\"start\"}"));
		assertEquals("align", ClientNet.type("{\"k\":3,\"t\":\"align\"}"), "t not first: a full parse");
		assertNull(ClientNet.type("not json"));
	}

	/** Section 1.5: the session state the DLL gets after a handshake result. */
	@Test
	void sessionStateFollowsTheHandshake() {
		assertEquals("host", ClientNet.sessionState(true, "host", ""));
		assertEquals("guest", ClientNet.sessionState(true, "guest", ""));
		assertEquals("refused", ClientNet.sessionState(true, "guest", "Cyberblock: your Cyberpunk plugin is 0.9 but your mod is 1.0"));
		assertEquals("refused", ClientNet.sessionState(false, "guest", "version mismatch"));
	}

	@Test
	void sessionMessageHasTheContractShape() {
		JsonObject m = JsonParser.parseString(ClientNet.sessionMessage("host", "", 1 | 4 | 16)).getAsJsonObject();
		assertEquals("session", m.get("t").getAsString());
		assertEquals("host", m.get("state").getAsString());
		assertEquals("", m.get("reason").getAsString());
		JsonObject p = m.getAsJsonObject("perms");
		assertTrue(p.get("explosions").getAsBoolean());
		assertFalse(p.get("damage").getAsBoolean());
		assertTrue(p.get("hacks").getAsBoolean());
		assertFalse(p.get("devices").getAsBoolean());
		assertTrue(p.get("spawns").getAsBoolean());
	}

	@Test
	void theSessionReasonIsCutTo128Bytes() {
		String reason = JsonParser.parseString(ClientNet.sessionMessage("refused", "é".repeat(100), 0)).getAsJsonObject().get("reason").getAsString();
		assertEquals(64, reason.length(), "two bytes each");
		assertEquals("abc", ClientNet.truncate("abc", 128));
		assertEquals("😀", ClientNet.truncate("😀😀", 5), "never half a surrogate pair");
	}
}
