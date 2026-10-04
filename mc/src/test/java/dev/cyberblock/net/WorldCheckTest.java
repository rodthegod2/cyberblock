package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** The dedicated server's world type check: the preset test, the operator's override and the warning text. */
class WorldCheckTest {
	@Test
	void onlyTheNightCityDimensionTypePasses() {
		assertTrue(WorldCheck.isNightCity(Identifier.fromNamespaceAndPath("cyberblock", "nightcity")));
		assertFalse(WorldCheck.isNightCity(Identifier.withDefaultNamespace("overworld")), "a normal or flat world");
		assertFalse(WorldCheck.isNightCity(Identifier.fromNamespaceAndPath("other", "nightcity")));
		assertFalse(WorldCheck.isNightCity(null), "an unnamed dimension type");
	}

	@Test
	void hostsAreRefusedOnlyOnADedicatedServerWithTheWrongWorldTypeAndNoOverride() {
		assertTrue(WorldCheck.refuses(true, false, false));
		assertFalse(WorldCheck.refuses(true, false, true), "an operator turned the check off");
		assertFalse(WorldCheck.refuses(true, true, false), "Night City");
		assertFalse(WorldCheck.refuses(false, false, false), "singleplayer and LAN are never checked");
		assertFalse(WorldCheck.refuses(false, false, true));
		assertFalse(WorldCheck.refusesHosts(), "no server running");
	}

	@Test
	void theWarningSaysWhatToDo() {
		assertEquals("worldcheck: overworld is minecraft:overworld, not cyberblock:nightcity; hosts refused (op: /cyberblock worldcheck off)",
			WorldCheck.warning("minecraft:overworld"));
		assertTrue(WorldCheck.REFUSED.contains("/cyberblock worldcheck off"));
		assertTrue(WorldCheck.REFUSED.length() <= Payloads.REASON_CAP, "fits hs_ack.reason");
	}

	@Test
	void theOverrideIsSavedWithTheWorld() {
		var saved = WorldCheck.Settings.CODEC.encodeStart(JsonOps.INSTANCE, new WorldCheck.Settings(true)).getOrThrow();
		assertEquals("{\"off\":true}", saved.toString());
		assertTrue(WorldCheck.Settings.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow().off());
		assertFalse(WorldCheck.Settings.CODEC.parse(JsonOps.INSTANCE, new JsonObject()).getOrThrow().off(), "the check is on by default");
	}
}
