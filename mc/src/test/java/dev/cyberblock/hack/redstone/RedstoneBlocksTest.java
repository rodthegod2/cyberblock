package dev.cyberblock.hack.redstone;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.cyber.Hack;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Megaplan 6-R1: the six blocks are what the ids say (6A's art follows these ids and block states), the sensors are signal sources
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
	void triggersStartUntriggeredAndSensorsOutputTheirPower() {
		BlockState relay = make("device_relay").defaultBlockState();
		assertFalse(relay.getValue(TriggerBlock.TRIGGERED));
		BlockState sensor = make("proximity_sensor").defaultBlockState();
		assertEquals(0, sensor.getValue(SensorBlock.POWER));
		BlockState on = sensor.setValue(SensorBlock.POWER, 12);
		assertTrue(on.isSignalSource());
		assertEquals(12, on.getSignal(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.NORTH));
		assertFalse(relay.isSignalSource(), "a relay only listens");
		assertTrue(make("cyberdeck_block").defaultBlockState().hasAnalogOutputSignal(), "a comparator reads the RAM");
	}

	@Test
	void theBreachPortOffersTheNinePeopleHacks() {
		assertEquals(9, HackMenu.HACKS.size(), "one row of the screen");
		assertFalse(HackMenu.HACKS.contains(Hack.DEVICE), "the Redstone Lever works on devices only");
	}
}
