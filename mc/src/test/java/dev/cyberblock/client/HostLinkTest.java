package dev.cyberblock.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class HostLinkTest {
	/** Plan retest3 section 1.1: hello gains ver (the mod version the DLL checks against its own). */
	@Test
	void helloCarriesTheModVersion() {
		JsonObject hello = JsonParser.parseString(HostLink.hello("1.0.0")).getAsJsonObject();
		assertEquals("hello", hello.get("t").getAsString());
		assertEquals(1, hello.get("proto").getAsInt());
		assertEquals("cyberblock", hello.get("mod").getAsString());
		assertEquals("26.3", hello.get("mc").getAsString());
		assertEquals("1.0.0", hello.get("ver").getAsString());
	}

	@Test
	void anOddVersionStringStaysValidJson() {
		JsonObject hello = JsonParser.parseString(HostLink.hello("1.0.0+\"dev\"\n")).getAsJsonObject();
		assertEquals("1.0.0+\"dev\"\n", hello.get("ver").getAsString());
	}
}
