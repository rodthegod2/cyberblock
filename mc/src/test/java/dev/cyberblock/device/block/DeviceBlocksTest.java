package dev.cyberblock.device.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.junit.jupiter.api.Test;

/** Megaplan 4-B1: the device blocks' states are the contract with 4A's art and 4M's placement (DeviceBlocks' list). */
class DeviceBlocksTest {
	private static Set<String> properties(final Block block) {
		Set<String> names = new TreeSet<>();
		for (Property<?> p : block.getStateDefinition().getProperties()) {
			names.add(p.getName());
		}

		return names;
	}

	@Test
	void everyDeviceBlockHasItsPropertiesAndIsNotQuestLockedByDefault() {
		assertEquals(Set.of("facing", "half", "hinge", "open", "powered", "quest_locked"), properties(DeviceTestBlocks.block("device_door")));
		assertEquals(Set.of("facing", "half", "hinge", "open", "powered", "quest_locked"), properties(DeviceTestBlocks.block("device_iron_door")));
		assertEquals(Set.of("quest_locked"), properties(DeviceTestBlocks.block("door_lock_lamp")));
		assertEquals(Set.of("color", "lit", "quest_locked"), properties(DeviceTestBlocks.block("lamp")));
		assertEquals(Set.of("facing", "mode", "quest_locked"), properties(DeviceTestBlocks.block("eye_camera")));
		assertEquals(Set.of("facing", "breached", "quest_locked"), properties(DeviceTestBlocks.block("net_port")));
		assertEquals(Set.of("facing", "broken", "quest_locked"), properties(DeviceTestBlocks.block("vending_machine")));
		assertEquals(Set.of("facing", "light", "quest_locked"), properties(DeviceTestBlocks.block("traffic_lamp")));
		assertEquals(Set.of("quest_locked"), properties(DeviceTestBlocks.block("lift_platform")));
		assertEquals(Set.of("facing", "unsupported", "quest_locked"), properties(DeviceTestBlocks.block("lift_call_button")));
		assertEquals(Set.of("facing", "mode", "quest_locked"), properties(DeviceTestBlocks.block("turret")));
		assertEquals(Set.of("axis", "quest_locked"), properties(DeviceTestBlocks.block("sliding_gate")));
		assertEquals(Set.of("readable", "quest_locked"), properties(DeviceTestBlocks.block("crate")));
		for (String name : DeviceBlocks.NAMES) {
			BlockState state = DeviceTestBlocks.block(name).defaultBlockState();
			assertFalse(DeviceHooks.questLocked(state), name);
			assertTrue(DeviceHooks.questLocked(state.setValue(DeviceBlock.QUEST_LOCKED, true)), name);
		}

		assertEquals(13, DeviceBlocks.NAMES.size());
		assertThrows(IllegalArgumentException.class, () -> DeviceBlocks.create("nope"));
	}

	@Test
	void theDoorsAreVanillaDoorsWithTheirOwnIds() {
		assertInstanceOf(DoorBlock.class, DeviceTestBlocks.block("device_door"));
		assertTrue(((DoorBlock) DeviceTestBlocks.block("device_door")).type().canOpenByHand());
		assertFalse(((DoorBlock) DeviceTestBlocks.block("device_iron_door")).type().canOpenByHand());
		assertFalse(DeviceTestBlocks.block("device_door").defaultBlockState().getValue(DoorBlock.OPEN));
	}

	@Test
	void theLampComesInEveryDyeAndLightsOnlyWhenLit() {
		BlockState lamp = DeviceTestBlocks.block("lamp").defaultBlockState();
		assertEquals(List.of(DyeColor.values()), LampBlock.COLOR.getPossibleValues());
		assertEquals(15, lamp.setValue(LampBlock.LIT, true).getLightEmission());
		assertEquals(0, lamp.setValue(LampBlock.LIT, false).getLightEmission());
	}

	@Test
	void lightsFollowTheState() {
		BlockState traffic = DeviceTestBlocks.block("traffic_lamp").defaultBlockState();
		assertEquals(0, traffic.setValue(TrafficLampBlock.LIGHT, TrafficLampBlock.Light.OFF).getLightEmission());
		assertEquals(12, traffic.setValue(TrafficLampBlock.LIGHT, TrafficLampBlock.Light.GREEN).getLightEmission());
		BlockState vending = DeviceTestBlocks.block("vending_machine").defaultBlockState();
		assertEquals(10, vending.getLightEmission());
		assertEquals(0, vending.setValue(VendingMachineBlock.BROKEN, true).getLightEmission());
		BlockState lockLamp = DeviceTestBlocks.block("door_lock_lamp").defaultBlockState();
		assertEquals(7, lockLamp.getLightEmission());
		assertEquals(0, lockLamp.setValue(DeviceBlock.QUEST_LOCKED, true).getLightEmission());
	}

	@Test
	void theCallButtonIsGreyedWhileLiftCallsAreUnproven() {
		assertFalse(LiftCallButtonBlock.CALL_SUPPORTED, "megaplan 1.9 amendment item 6: call is unproven until a Phase 0 c check");
		assertTrue(DeviceTestBlocks.block("lift_call_button").defaultBlockState().getValue(LiftCallButtonBlock.UNSUPPORTED));
	}

	@Test
	void defaultsAreTheWatchingAndWorkingStates() {
		assertEquals(EyeCameraBlock.Mode.ON, DeviceTestBlocks.block("eye_camera").defaultBlockState().getValue(EyeCameraBlock.MODE));
		assertEquals(TurretBlock.Mode.ACTIVE, DeviceTestBlocks.block("turret").defaultBlockState().getValue(TurretBlock.MODE));
		assertFalse(DeviceTestBlocks.block("crate").defaultBlockState().getValue(CrateBlock.READABLE));
		assertFalse(DeviceTestBlocks.block("net_port").defaultBlockState().getValue(NetPortBlock.BREACHED));
		assertEquals("alarm", EyeCameraBlock.Mode.ALARM.getSerializedName());
		assertEquals("friendly", TurretBlock.Mode.FRIENDLY.getSerializedName());
		assertEquals("yellow", TrafficLampBlock.Light.YELLOW.getSerializedName());
	}
}
