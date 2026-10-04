package dev.cyberblock.hack.breach;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.device.Device;
import dev.cyberblock.hack.breach.BreachContent.RankedTier;
import dev.cyberblock.hack.breach.BreachContent.Step;
import dev.cyberblock.hack.breach.BreachPuzzle.DaemonDef;
import dev.cyberblock.hack.breach.BreachPuzzle.Tier;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** Plan 6-B1 with section 1.7: 6A's daemon and breach_tier files, the built-ins, security levels and the Firewall raise. */
class BreachContentTest {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("cyberblock", "x");
	/** 6A's open_door.json. */
	static final String OPEN_DOOR = "{\"schema\":1,\"title_key\":\"cyberblock.daemon.open_door\",\"text_key\":\"cyberblock.daemon.open_door.text\","
		+ "\"sequence\":[\"BD\",\"1C\"],\"kinds\":[\"door\",\"gate\",\"terminal\",\"container\"],\"action\":\"open_door\",\"tiers\":[{\"seconds\":0,\"radius\":8},"
		+ "{\"seconds\":0,\"radius\":14},{\"seconds\":0,\"radius\":24}],\"weight\":6,\"min_security\":1}";
	/** 6A's security_1.json. */
	static final String SECURITY_1 = "{\"schema\":1,\"security\":1,\"grid\":5,\"buffer\":7,\"seconds\":60,\"daemons_min\":1,\"daemons_max\":2,\"firewall\":["
		+ "{\"grid_add\":1,\"buffer_add\":0,\"seconds_add\":-5,\"daemons_add\":0},{\"grid_add\":1,\"buffer_add\":0,\"seconds_add\":-10,\"daemons_add\":0},"
		+ "{\"grid_add\":2,\"buffer_add\":-1,\"seconds_add\":-15,\"daemons_add\":1}]}";

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void sixAsDaemonFileParses() {
		BreachContent.Entry e = BreachContent.parseDaemon(ID, json(OPEN_DOOR));
		assertEquals("open_door", e.daemon().id(), "the wire id is the action");
		assertArrayEquals(new int[] {2, 0}, e.daemon().seq(), "BD 1C");
		assertEquals(6, e.daemon().weight());
		assertEquals(Set.of("door", "gate", "terminal", "container"), e.kinds());
		assertTrue(e.offeredFor("terminal", 1));
		assertFalse(e.offeredFor("camera", 3));

		BreachContent.Entry plain = BreachContent.parseDaemon(ID, json(OPEN_DOOR.replace(",\"weight\":6,\"min_security\":1", "")));
		assertEquals(1, plain.daemon().weight(), "weight defaults to 1");
		assertEquals(1, plain.minSecurity(), "min_security defaults to 1");
	}

	@Test
	void badDaemonsAreSkipped() {
		String[] bad = {OPEN_DOOR.replace("\"BD\",\"1C\"", "\"BD\""), OPEN_DOOR.replace("\"BD\",\"1C\"", "\"BD\",\"1C\",\"55\",\"7A\",\"E9\""),
			OPEN_DOOR.replace("\"BD\",\"1C\"", "\"BD\",\"1D\""), OPEN_DOOR.replace("\"door\",", "\"doors\","), OPEN_DOOR.replace("\"action\":\"open_door\"", "\"action\":\"Open\""),
			OPEN_DOOR.replace(",{\"seconds\":0,\"radius\":24}", ""), OPEN_DOOR.replace("\"radius\":8", "\"radius\":0"), OPEN_DOOR.replace("\"weight\":6", "\"weight\":11"),
			OPEN_DOOR.replace("\"min_security\":1", "\"min_security\":4"), OPEN_DOOR.replace("cyberblock.daemon.open_door.text", "Bad Key"),
			OPEN_DOOR.replace("\"kinds\":[\"door\",\"gate\",\"terminal\",\"container\"]", "\"kinds\":[]")};
		for (String s : bad) {
			assertThrows(IllegalArgumentException.class, () -> BreachContent.parseDaemon(ID, json(s)), s);
		}
	}

	@Test
	void sixAsTierFileParses() {
		RankedTier t = BreachContent.parseTier(ID, json(SECURITY_1));
		assertEquals(1, t.security());
		assertEquals(new Tier(5, 7, 1, 2, 60), t.tier());
		assertEquals(new Step(2, -1, -15, 1), t.firewall().get(2));
		String[] bad = {SECURITY_1.replace("\"grid\":5", "\"grid\":4"), SECURITY_1.replace("\"grid\":5", "\"grid\":8"), SECURITY_1.replace("\"buffer\":7", "\"buffer\":3"),
			SECURITY_1.replace("\"seconds\":60", "\"seconds\":9"), SECURITY_1.replace("\"daemons_min\":1", "\"daemons_min\":3"),
			SECURITY_1.replace("\"security\":1", "\"security\":0"), SECURITY_1.replace(",{\"grid_add\":2,\"buffer_add\":-1,\"seconds_add\":-15,\"daemons_add\":1}", ""),
			SECURITY_1.replace("\"buffer_add\":-1", "\"buffer_add\":1")};
		for (String s : bad) {
			assertThrows(IllegalArgumentException.class, () -> BreachContent.parseTier(ID, json(s)), s);
		}
	}

	@Test
	void builtInTiersGoFiveToSeven() {
		assertEquals(5, BreachContent.tierFor(1, 0, List.of()).grid(), "an ordinary device");
		assertEquals(6, BreachContent.tierFor(2, 0, List.of()).grid(), "a secure device");
		assertEquals(7, BreachContent.tierFor(3, 0, List.of()).grid(), "a hardened device");
		assertEquals(new Tier(4, 4, 1, 1, 5), BreachContent.SHORT, "Phase 8's 4x4, 5 s");
	}

	@Test
	void theFirewallRaisesTheTierWithinItsBounds() {
		RankedTier one = BreachContent.parseTier(ID, json(SECURITY_1));
		assertEquals(new Tier(5, 7, 1, 2, 60), one.with(0));
		assertEquals(new Tier(6, 7, 1, 2, 55), one.with(1));
		assertEquals(new Tier(7, 6, 1, 3, 45), one.with(3));
		assertEquals(one.with(3), one.with(9), "ranks above 3 count as 3");
		RankedTier tight = new RankedTier(3, new Tier(7, 4, 3, 3, 12), List.of(new Step(2, -3, -120, 1), new Step(2, -3, -120, 1), new Step(2, -3, -120, 1)));
		assertEquals(new Tier(7, 4, 3, 3, 10), tight.with(1), "grid at most 7, buffer at least 4, timer at least 10 s, daemons at most 3");
	}

	@Test
	void contentTiersPickTheHighestNotAboveTheSecurity() {
		List<RankedTier> tiers = List.of(new RankedTier(2, new Tier(6, 6, 1, 3, 50), BreachContent.BUILT_IN_TIERS.get(1).firewall()),
			new RankedTier(3, new Tier(7, 5, 2, 3, 45), BreachContent.BUILT_IN_TIERS.get(2).firewall()));
		assertEquals(6, BreachContent.tierFor(1, 0, tiers).grid(), "below every tier: the lowest");
		assertEquals(6, BreachContent.tierFor(2, 0, tiers).grid());
		assertEquals(7, BreachContent.tierFor(3, 0, tiers).grid());
		assertEquals(7, BreachContent.tierFor(2, 1, tiers).grid(), "a Firewall on a secure device: 6 + 1");
	}

	@Test
	void deviceSecurity() {
		Device plain = new Device(5L, true, "Terminal", DeviceType.TERMINAL, 0, 64, 0, 0, 1, Device.POWERED);
		assertEquals(1, BreachContent.security(plain));
		assertEquals(2, BreachContent.security(plain.withState(1, Device.POWERED | Device.SECURE)));
		assertEquals(3, BreachContent.security(plain.withState(1, Device.POWERED | Device.SECURE | Device.LOCKED)));
	}

	@Test
	void daemonsComeFromContentElseTheBuiltIns() {
		assertEquals(List.of("open_door", "disable_cameras", "reveal_enemies"), ids(BreachContent.daemonsFor("terminal", 1, List.of())), "overload needs security 2");
		assertEquals(List.of("open_door", "disable_cameras", "reveal_enemies", "overload"), ids(BreachContent.daemonsFor("terminal", 2, List.of())));
		assertEquals(List.of("disable_cameras", "reveal_enemies"), ids(BreachContent.daemonsFor("camera", 1, List.of())));
		assertEquals(ids(BreachContent.daemonsFor("terminal", 1, List.of())), ids(BreachContent.daemonsFor("player", 1, List.of())),
			"a kind nothing is written for gets the terminal's");

		List<BreachContent.Entry> content = List.of(BreachContent.parseDaemon(ID, json(OPEN_DOOR)),
			new BreachContent.Entry(new DaemonDef("make_friendly", new int[] {5, 2, 3, 4}, 2), Set.of("turret", "terminal"), 3));
		assertEquals(List.of("open_door"), ids(BreachContent.daemonsFor("terminal", 2, content)));
		assertEquals(List.of("open_door", "make_friendly"), ids(BreachContent.daemonsFor("terminal", 3, content)));
		assertEquals(List.of("disable_cameras", "reveal_enemies"), ids(BreachContent.daemonsFor("camera", 1, content)), "content has none for cameras");
	}

	private static List<String> ids(final List<DaemonDef> daemons) {
		return daemons.stream().map(DaemonDef::id).toList();
	}
}
