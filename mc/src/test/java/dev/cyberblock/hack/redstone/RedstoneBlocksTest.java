package dev.cyberblock.hack.redstone;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.cyber.Hack;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Megaplan 6-R1: the six blocks are what the ids say and have the block states 6A's art draws, the sensors are signal sources
 * whose output is their power, and the Breach Port's screen offers the nine quickhacks for people. The blocks are built the way
 * DeviceTestBlocks builds 4B's: the frozen block registry is opened for the construction only, never registered.
 */
class RedstoneBlocksTest {
	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Block make(final String name) {
		try {
			Field holders = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
			holders.setAccessible(true);
			Field frozen = MappedRegistry.class.getDeclaredField("frozen");
			frozen.setAccessible(true);
			Object was = holders.get(BuiltInRegistries.BLOCK);
			holders.set(BuiltInRegistries.BLOCK, new IdentityHashMap<>());
			frozen.set(BuiltInRegistries.BLOCK, false);
			try {
				return RedstoneBlocks.create(name);
			} finally {
				frozen.set(BuiltInRegistries.BLOCK, true);
				holders.set(BuiltInRegistries.BLOCK, was);
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void theIdsAre6AsContract() {
		assertEquals(List.of("device_relay", "proximity_sensor", "heat_sensor", "breach_port", "charge", "cyberdeck_block"), RedstoneBlocks.NAMES);
		assertEquals("cyberdeck_link", RedstoneBlocks.LINK_ITEM);
		assertThrows(IllegalArgumentException.class, () -> RedstoneBlocks.create("cyberdeck"), "the implant item keeps its id");
	}

	@Test
	void eachBlockIsItsKind() {
		assertEquals(TriggerBlock.Kind.RELAY, ((TriggerBlock) make("device_relay")).kind());
		assertEquals(TriggerBlock.Kind.BREACH_PORT, ((TriggerBlock) make("breach_port")).kind());
		assertEquals(TriggerBlock.Kind.CHARGE, ((TriggerBlock) make("charge")).kind());
		assertEquals(SensorBlock.Kind.PROXIMITY, ((SensorBlock) make("proximity_sensor")).kind());
		assertEquals(SensorBlock.Kind.HEAT, ((SensorBlock) make("heat_sensor")).kind());
		assertInstanceOf(CyberdeckBlock.class, make("cyberdeck_block"));
	}

	@Test
	void triggersStartUnpoweredAndSensorsOutputTheirState() {
		BlockState relay = make("device_relay").defaultBlockState();
		assertFalse(relay.getValue(TriggerBlock.POWERED));
		assertFalse(relay.getValue(TriggerBlock.LINKED));
		assertEquals(Direction.NORTH, make("breach_port").defaultBlockState().getValue(TriggerBlock.FACING));
		BlockState heat = make("heat_sensor").defaultBlockState();
		assertEquals(0, heat.getValue(SensorBlock.POWER));
		BlockState warm = heat.setValue(SensorBlock.POWER, 12);
		assertTrue(warm.isSignalSource());
		assertEquals(12, warm.getSignal(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.NORTH));
		BlockState proximity = make("proximity_sensor").defaultBlockState();
		assertEquals(0, proximity.getSignal(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.NORTH));
		assertEquals(15, SensorBlock.withOutput(proximity, 15).getSignal(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.NORTH));
		assertEquals(0, SensorBlock.output(SensorBlock.withOutput(proximity, 0)));
		assertFalse(relay.isSignalSource(), "a relay only listens");
		BlockState deck = make("cyberdeck_block").defaultBlockState();
		assertTrue(deck.hasAnalogOutputSignal(), "a comparator reads the RAM");
		assertEquals(RedstoneRules.Deck.LIGHTS, deck.getValue(CyberdeckBlock.RAM), "a new one is full");
	}

	/** 6A's blockstates (megaplan 6-A1) draw exactly these properties. */
	@Test
	void theBlockStatesAre6AsContract() {
		Map<String, Set<String>> expected = Map.of("device_relay", Set.of("linked", "powered"), "breach_port", Set.of("facing", "powered"), "charge",
			Set.of("powered"), "proximity_sensor", Set.of("powered"), "heat_sensor", Set.of("power"), "cyberdeck_block", Set.of("facing", "ram"));
		for (String name : RedstoneBlocks.NAMES) {
			Set<String> names = make(name).getStateDefinition().getProperties().stream().map(Property::getName).collect(Collectors.toSet());
			assertEquals(expected.get(name), names, name);
		}

		assertEquals(List.of(0, 1, 2, 3, 4), List.copyOf(CyberdeckBlock.RAM.getPossibleValues()));
	}

	/**
	 * Once 6A's art is merged: every variant of the six blockstate files names only properties and values the block has, and every
	 * state of the block has a variant (else it would show the missing model). Skipped while the files are not there.
	 */
	@Test
	void everyStateHasAVariantIn6AsBlockstates() throws IOException {
		Path dir = Path.of("src/main/resources/assets/cyberblock/blockstates");
		assumeTrue(Files.isRegularFile(dir.resolve("device_relay.json")), "6A's blockstates are not merged yet");
		for (String name : RedstoneBlocks.NAMES) {
			Block block = make(name);
			JsonObject variants = JsonParser.parseString(Files.readString(dir.resolve(name + ".json"))).getAsJsonObject().getAsJsonObject("variants");
			List<Map<String, String>> selectors = new ArrayList<>();
			for (String key : variants.keySet()) {
				Map<String, String> selector = new HashMap<>();
				for (String pair : key.isEmpty() ? new String[0] : key.split(",")) {
					String[] kv = pair.split("=", 2);
					Property<?> property = block.getStateDefinition().getProperty(kv[0]);
					assertNotNull(property, name + " has no property " + kv[0]);
					assertTrue(property.getValue(kv[1]).isPresent(), name + " " + kv[0] + " has no value " + kv[1]);
					selector.put(kv[0], kv[1]);
				}

				selectors.add(selector);
			}

			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				boolean drawn = selectors.stream().anyMatch(s -> s.entrySet().stream().allMatch(e -> {
					Property<?> p = block.getStateDefinition().getProperty(e.getKey());
					return valueName(state, p).equals(e.getValue());
				}));
				assertTrue(drawn, name + " has no variant for " + state);
			}
		}
	}

	private static <T extends Comparable<T>> String valueName(final BlockState state, final Property<T> property) {
		return property.getName(state.getValue(property));
	}

	@Test
	void theBreachPortOffersTheNinePeopleHacks() {
		assertEquals(9, HackMenu.HACKS.size(), "one row of the screen");
		assertFalse(HackMenu.HACKS.contains(Hack.DEVICE), "the Redstone Lever works on devices only");
	}
}
