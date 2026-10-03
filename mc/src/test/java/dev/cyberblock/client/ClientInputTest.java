package dev.cyberblock.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
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
		Consumer<String> was = Cyberblock.events;
		List<String> sent = new ArrayList<>();
		Cyberblock.events = sent::add;
		try {
			ClientInput.sendPerspective();
		} finally {
			Cyberblock.events = was;
		}

		assertEquals(List.of(ClientInput.perspMessage(false)), sent);
	}
}
