package dev.cyberblock.hack.redstone;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.cyber.Hack;
import dev.cyberblock.device.Device;
import dev.cyberblock.hack.redstone.RedstoneRules.Deck;
import dev.cyberblock.hack.redstone.RedstoneRules.Refusal;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Megaplan 6-R1: what the blocks ask Night City for, their permission refusals, the sensors and the Cyberdeck block's RAM. */
class RedstoneRulesTest {
	private static Device door(final int st, final int fl) {
		return new Device(1, true, "Door", DeviceType.DOOR, 0, 64, 0, 0, st, fl);
	}

	@Test
	void aRelayOpensAndClosesDoorsAndTogglesTheRest() {
		assertEquals(List.of("open"), RedstoneRules.relayActs(door(Device.DOOR_CLOSED, 0)));
		assertEquals(List.of("close"), RedstoneRules.relayActs(door(Device.DOOR_OPENED, 0)));
		assertEquals(List.of("unlock", "open"), RedstoneRules.relayActs(door(Device.DOOR_LOCKED, Device.LOCKED)), "a relay may open a locked door");
		assertEquals(List.of("toggle"), RedstoneRules.relayActs(new Device(2, true, "ElectricLight", DeviceType.LIGHT, 0, 64, 0, 0, Device.ST_ON, 0)));
		assertEquals(List.of("toggle"), RedstoneRules.relayActs(new Device(3, true, "SecurityGate", DeviceType.GATE, 0, 64, 0, 0, Device.ST_OFF, 0)));
		for (String act : List.of("open", "close", "unlock", "toggle")) {
			assertTrue(dev.cyberblock.device.DeviceWire.NIGHT_CITY_ACTS.contains(act), act + " is an act Night City performs");
		}
	}

	@Test
	void guestsNeedTheOwnersToggles() {
		Permissions all = Permissions.DEFAULT;
		Permissions noDoors = all.with(DeviceType.DOOR, false);
		Permissions noDevices = all.with(Permissions.Toggle.DEVICES, false);
		assertTrue(RedstoneRules.allowedDevice(all, false, DeviceType.DOOR));
		assertFalse(RedstoneRules.allowedDevice(noDoors, false, DeviceType.DOOR), "the door bit is off");
		assertTrue(RedstoneRules.allowedDevice(noDoors, false, DeviceType.LIGHT), "other types stay on");
		assertFalse(RedstoneRules.allowedDevice(noDevices, false, DeviceType.LIGHT), "the devices toggle gates every type");
		assertTrue(RedstoneRules.allowedDevice(noDevices, true, DeviceType.DOOR), "the owner, another host or the world always may");
		Permissions noHacks = all.with(Permissions.Toggle.HACKS, false), noBlasts = all.with(Permissions.Toggle.EXPLOSIONS, false);
		assertFalse(RedstoneRules.allowed(noHacks, false, Permissions.Toggle.HACKS), "a guest's Breach Port");
		assertTrue(RedstoneRules.allowed(noHacks, true, Permissions.Toggle.HACKS), "the host's own Breach Port");
		assertFalse(RedstoneRules.allowed(noBlasts, false, Permissions.Toggle.EXPLOSIONS), "a guest's Charge");
		assertTrue(RedstoneRules.allowed(all, false, Permissions.Toggle.EXPLOSIONS));
	}

	@Test
	void aRelaysRefusalsInOrder() {
		Device open = door(Device.DOOR_OPENED, 0), quest = door(Device.DOOR_SEALED, Device.QUEST_LOCKED);
		assertEquals(Refusal.NO_LINK, RedstoneRules.relay(false, null, false, true, true));
		assertEquals(Refusal.UNRESOLVED, RedstoneRules.relay(true, null, false, true, true));
		assertEquals(Refusal.FROZEN, RedstoneRules.relay(true, open, true, false, false), "a loop freeze comes before the rest");
		assertEquals(Refusal.BLOCKED, RedstoneRules.relay(true, open, false, false, true));
		assertEquals(Refusal.QUEST_LOCKED, RedstoneRules.relay(true, quest, false, true, true));
		assertEquals(Refusal.OUT_OF_RANGE, RedstoneRules.relay(true, open, false, true, false));
		assertEquals(Refusal.NONE, RedstoneRules.relay(true, open, false, true, true));
		assertEquals(Refusal.BLOCKED, RedstoneRules.hack(false, true));
		assertEquals(Refusal.OUT_OF_RANGE, RedstoneRules.hack(true, false));
		assertEquals(Refusal.NONE, RedstoneRules.hack(true, true));
		for (Refusal r : Refusal.values()) {
			assertTrue(r == Refusal.NONE || r.key().startsWith("cyberblock.") && !r.fallback().isEmpty(), r.name());
		}
	}

	@Test
	void theHeatSensorIsWantedTimesThree() {
		assertEquals(0, RedstoneRules.heatSignal(0));
		assertEquals(9, RedstoneRules.heatSignal(3));
		assertEquals(15, RedstoneRules.heatSignal(5));
		assertEquals(15, RedstoneRules.heatSignal(9));
		assertEquals(0, RedstoneRules.heatSignal(-1));
	}

	@Test
	void theProximitySensorWatchesWhatItsScreenPicked() {
		assertEquals(15, RedstoneRules.proximitySignal(RedstoneRules.ALL_TARGETS, 1, 0, 0));
		assertEquals(0, RedstoneRules.proximitySignal(RedstoneRules.CAR | RedstoneRules.V, 3, 0, 0), "people are not watched");
		assertEquals(15, RedstoneRules.proximitySignal(RedstoneRules.CAR, 0, 1, 0));
		assertEquals(15, RedstoneRules.proximitySignal(RedstoneRules.V, 0, 0, 1));
		assertEquals(0, RedstoneRules.proximitySignal(RedstoneRules.ALL_TARGETS, 0, 0, 0));
		assertEquals(1, RedstoneRules.range(0));
		assertEquals(16, RedstoneRules.range(40));
	}

	@Test
	void theCyberdeckBlockPaysAndRefillsSlowly() {
		Deck deck = Deck.full(0);
		assertEquals(RedstoneRules.DECK_MAX_RAM, deck.ram(0));
		assertEquals(15, deck.signal(0));
		Deck paid = deck.take(10, 0);
		assertNotNull(paid);
		assertEquals(6, paid.ram(0));
		assertNull(paid.take(7, 0), "short of RAM");
		assertEquals(7, paid.ram(RedstoneRules.DECK_REFILL_TICKS), "one point every 5 s");
		Deck again = paid.take(1, RedstoneRules.DECK_REFILL_TICKS + 50);
		assertEquals(6, again.ram(RedstoneRules.DECK_REFILL_TICKS + 50));
		assertEquals(7, again.ram(2 * RedstoneRules.DECK_REFILL_TICKS), "the refill under way kept its progress");
		assertEquals(RedstoneRules.DECK_MAX_RAM, again.ram(100_000), "never past the maximum");
		assertEquals(RedstoneRules.DECK_MAX_RAM, paid.give(100, 0).ram(0), "a refund is capped");
	}

	@Test
	void breakingAndReplacingTheCyberdeckBlockDoesNotRefillIt() {
		long now = 1000;
		Deck used = Deck.full(0).take(13, now);
		assertNotNull(used);
		assertEquals(3, used.ram(now));
		Deck replaced = Deck.placed(now + 40); // two seconds of mining later
		assertEquals(0, replaced.ram(now + 40), "a placed block starts empty");
		assertTrue(replaced.ram(now + 40) <= used.ram(now + 40), "never more RAM than the block it replaced");
		assertNull(replaced.take(Hack.CYBERPSYCHOSIS.ram(), now + 40), "no free top-tier hack");
		assertEquals(1, replaced.ram(now + 40 + RedstoneRules.DECK_REFILL_TICKS), "it refills at the documented 1 RAM per 5 s");
		assertEquals(RedstoneRules.DECK_MAX_RAM, replaced.ram(now + 40 + RedstoneRules.DECK_MAX_RAM * RedstoneRules.DECK_REFILL_TICKS), "full after 80 s");
	}

	@Test
	void theCyberdeckLightsFollowTheRamAndTheNextRefillIsKnown() {
		assertEquals(Deck.LIGHTS, Deck.full(0).lights(0));
		assertEquals(0, new Deck(0, 0).lights(0), "dark only when empty");
		assertEquals(1, new Deck(1, 0).lights(0));
		assertEquals(1, new Deck(4, 0).lights(0));
		assertEquals(2, new Deck(5, 0).lights(0));
		assertEquals(3, new Deck(12, 0).lights(0));
		assertEquals(4, new Deck(13, 0).lights(0));
		assertEquals(0L, Deck.full(0).untilRefill(500), "full: nothing to wait for");
		assertEquals(RedstoneRules.DECK_REFILL_TICKS, new Deck(3, 0).untilRefill(0));
		assertEquals(RedstoneRules.DECK_REFILL_TICKS - 30, new Deck(3, 0).untilRefill(30));
		assertEquals(RedstoneRules.DECK_REFILL_TICKS - 30, new Deck(3, 0).untilRefill(RedstoneRules.DECK_REFILL_TICKS + 30), "after one point came back");
		assertEquals(0L, new Deck(15, 0).untilRefill(RedstoneRules.DECK_REFILL_TICKS), "the last point made it full");
	}

	@Test
	void theDeviceTableForgetsGoneDevicesAndEndedOwners() {
		DeviceTable table = new DeviceTable();
		UUID owner = new UUID(1, 1);
		table.update(owner, List.of(door(Device.DOOR_CLOSED, 0)), 0);
		assertEquals(1, table.of(owner, 10).size());
		table.state(owner, 1, Device.DOOR_OPENED, 0);
		assertTrue(table.get(owner, 1, 10).open(), "a devstate updates it");
		assertTrue(table.of(owner, DeviceTable.GONE_TICKS).isEmpty(), "no list named it for 10 s");
		table.update(owner, List.of(door(Device.DOOR_CLOSED, 0)), 300);
		table.end(owner);
		assertTrue(table.of(owner, 300).isEmpty(), "the session ended");
		assertTrue(table.of(new UUID(2, 2), 0).isEmpty());
	}
}
