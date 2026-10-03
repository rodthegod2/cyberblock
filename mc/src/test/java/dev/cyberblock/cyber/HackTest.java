package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.client.cyber.HackWheel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HackTest {
	/** Plan 2026-10-03 section 1.5, copied by hand: item id, hack wire value, RAM cost. */
	private static final List<String> PLAN = List.of(
		"redstone_charge short_circuit 3",
		"blaze_chip overheat 4",
		"ink_sac_daemon reboot_optics 3",
		"cobweb_daemon cripple_movement 2",
		"spider_eye_virus contagion 4",
		"wither_skull_daemon synapse_burnout 6",
		"compass_ping ping 1",
		"bottle_forgetting memory_wipe 3",
		"creeper_brain cyberpsychosis 8",
		"redstone_lever device 1"
	);

	@Test
	void tableMatchesThePlanOneToOne() {
		List<String> table = Arrays.stream(Hack.values()).map(h -> h.item() + " " + h.wire() + " " + h.ram()).toList();
		assertEquals(PLAN, table);
	}

	@Test
	void longCooldownsOnlyForTheHeavyHacks() {
		for (Hack h : Hack.values()) {
			boolean heavy = h == Hack.SYNAPSE_BURNOUT || h == Hack.CYBERPSYCHOSIS;
			assertEquals(heavy ? 200 : 40, h.cooldownTicks(), h.name());
		}
	}

	@Test
	void onlyTheLeverNeedsADevice() {
		assertTrue(Hack.DEVICE.needsDevice());
		assertEquals(1, Arrays.stream(Hack.values()).filter(Hack::needsDevice).count());
	}

	/** The hack key's wheel steps through the chips he has like the hotbar: wheel down (-1) forward, up (+1) back, wrapping. */
	@Test
	void theWheelStepsThroughTheChipsHeHas() {
		List<Hack> chips = List.of(Hack.SHORT_CIRCUIT, Hack.PING, Hack.DEVICE);
		assertSame(Hack.PING, HackWheel.step(chips, Hack.SHORT_CIRCUIT, -1));
		assertSame(Hack.DEVICE, HackWheel.step(chips, Hack.SHORT_CIRCUIT, 1));
		assertSame(Hack.SHORT_CIRCUIT, HackWheel.step(chips, Hack.DEVICE, -1));
		assertSame(Hack.PING, HackWheel.step(chips, Hack.PING, 3));
		assertSame(Hack.PING, HackWheel.step(List.of(Hack.PING), Hack.PING, -1));
	}

	/**
	 * Every text the mod shows by a literal key (Component.translatable / keybind) has an English line, so no message or tooltip
	 * of the netrunning flow shows a raw key; the scan tooltip's five threat words too.
	 */
	@Test
	void everyShownKeyHasAnEnglishLine() throws IOException {
		JsonObject lang = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/cyberblock/lang/en_us.json"))).getAsJsonObject();
		Pattern literal = Pattern.compile("Component\\.(?:translatable|keybind)\\(\"([^\"]+)\"(?!\\s*\\+)");
		List<Path> sources;
		try (Stream<Path> files = Stream.concat(Files.walk(Path.of("src/main/java")), Files.walk(Path.of("src/client/java")))) {
			sources = files.filter(p -> p.toString().endsWith(".java")).toList();
		}

		int keys = 0;
		for (Path source : sources) {
			Matcher m = literal.matcher(Files.readString(source));
			while (m.find()) {
				assertTrue(lang.has(m.group(1)), m.group(1) + " in " + source.getFileName());
				keys++;
			}
		}

		assertTrue(keys > 30, "found the mod's keys");
		for (String threat : new String[]{"none", "low", "medium", "high", "extreme"}) {
			assertTrue(lang.has("cyberblock.scan.threat." + threat), threat);
		}
	}

	@Test
	void byWire() {
		assertSame(Hack.MEMORY_WIPE, Hack.byWire("memory_wipe"));
		assertNull(Hack.byWire("MEMORY_WIPE"));
		assertNull(Hack.byWire("blackwall"));
	}
}
