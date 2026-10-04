package dev.cyberblock.device.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.Test;

/** Megaplan 4-B1: the device blocks' states are the contract with 4A's blockstates and 4M's placement (DeviceBlocks' list). */
class DeviceBlocksTest {
	private static Set<String> properties(final String name) {
		Set<String> names = new TreeSet<>();
		for (Property<?> p : DeviceTestBlocks.block(name).getStateDefinition().getProperties()) {
			names.add(p.getName());
		}

		return names;
	}

	@Test
	void everyDeviceBlockHasItsPropertiesAndIsNotQuestLockedByDefault() {
		assertEquals(Set.of("facing", "half", "hinge", "open", "powered", "quest_locked"), properties("device_door"));
		assertEquals(Set.of("facing", "half", "hinge", "open", "powered", "quest_locked"), properties("device_iron_door"));
		for (DyeColor dye : DyeColor.values()) {
			assertEquals(Set.of("lit", "quest_locked"), properties("lamp_" + dye.getSerializedName()));
			assertEquals(dye, ((LampBlock) DeviceTestBlocks.block("lamp_" + dye.getSerializedName())).colour());
		}

		assertEquals(Set.of("facing", "mode", "quest_locked"), properties("eye_camera"));
		assertEquals(Set.of("facing", "breached", "quest_locked"), properties("net_port"));
		assertEquals(Set.of("facing", "broken", "quest_locked"), properties("vending_machine"));
		assertEquals(Set.of("signal", "quest_locked"), properties("traffic_lamp"));
		assertEquals(Set.of("moving", "quest_locked"), properties("lift_platform"));
		assertEquals(Set.of("facing", "lit", "quest_locked"), properties("lift_call_button"));
		assertEquals(Set.of("facing", "mode", "quest_locked"), properties("turret"));
		assertEquals(Set.of("facing", "step", "quest_locked"), properties("sliding_gate"));
		assertEquals(Set.of("facing", "on", "quest_locked"), properties("screen_frame"));
		assertEquals(Set.of("readable", "quest_locked"), properties("crate"));
		for (String name : DeviceBlocks.NAMES) {
			BlockState state = DeviceTestBlocks.block(name).defaultBlockState();
			assertFalse(DeviceHooks.questLocked(state), name);
			assertTrue(DeviceHooks.questLocked(state.setValue(DeviceBlock.QUEST_LOCKED, true)), name);
		}

		assertEquals(28, DeviceBlocks.NAMES.size());
		assertEquals(28, Set.copyOf(DeviceBlocks.NAMES).size());
		assertThrows(IllegalArgumentException.class, () -> DeviceBlocks.create("nope"));
		assertThrows(IllegalArgumentException.class, () -> DeviceBlocks.create("lamp_teal"));
	}

	@Test
	void theDoorsAreVanillaDoorsWithTheirOwnIds() {
		assertInstanceOf(DoorBlock.class, DeviceTestBlocks.block("device_door"));
		DoorBlock wood = (DoorBlock) DeviceTestBlocks.block("device_door");
		// not a "wooden door" to vanilla: zombies never break it, villager and piglin door goals and mob pathing treat it like iron
		assertFalse(DoorBlock.isWoodenDoor(wood.defaultBlockState()));
		assertFalse(DoorBlock.isWoodenDoor(DeviceTestBlocks.block("device_iron_door").defaultBlockState()));
		assertFalse(wood.type().canOpenByWindCharge());
		// still sounds like oak
		assertEquals(BlockSetType.OAK.doorOpen(), wood.type().doorOpen());
		assertEquals(BlockSetType.OAK.soundType(), wood.type().soundType());
		assertFalse(((DoorBlock) DeviceTestBlocks.block("device_iron_door")).type().canOpenByHand());
		assertFalse(wood.defaultBlockState().getValue(DoorBlock.OPEN));
	}

	@Test
	void lightsFollowTheState() {
		BlockState lamp = DeviceTestBlocks.block("lamp_cyan").defaultBlockState();
		assertEquals(15, lamp.setValue(LampBlock.LIT, true).getLightEmission());
		assertEquals(0, lamp.setValue(LampBlock.LIT, false).getLightEmission());
		BlockState traffic = DeviceTestBlocks.block("traffic_lamp").defaultBlockState();
		assertEquals(0, traffic.setValue(TrafficLampBlock.SIGNAL, TrafficLampBlock.Signal.OFF).getLightEmission());
		assertEquals(12, traffic.setValue(TrafficLampBlock.SIGNAL, TrafficLampBlock.Signal.GREEN).getLightEmission());
		BlockState vending = DeviceTestBlocks.block("vending_machine").defaultBlockState();
		assertEquals(10, vending.getLightEmission());
		assertEquals(0, vending.setValue(VendingMachineBlock.BROKEN, true).getLightEmission());
		BlockState screen = DeviceTestBlocks.block("screen_frame").defaultBlockState();
		assertEquals(8, screen.getLightEmission());
		assertEquals(0, screen.setValue(ScreenFrameBlock.ON, false).getLightEmission());
		BlockState button = DeviceTestBlocks.block("lift_call_button").defaultBlockState();
		assertEquals(0, button.getLightEmission());
		assertEquals(6, button.setValue(LiftCallButtonBlock.LIT, true).getLightEmission());
	}

	@Test
	void theCallButtonStaysDarkWhileLiftCallsAreUnproven() {
		assertFalse(LiftCallButtonBlock.CALL_SUPPORTED, "megaplan 1.9 amendment item 6: call is unproven until a Phase 0 c check");
		assertFalse(DeviceTestBlocks.block("lift_call_button").defaultBlockState().getValue(LiftCallButtonBlock.LIT));
	}

	@Test
	void defaultsAreTheWatchingAndWorkingStates() {
		assertEquals(EyeCameraBlock.Mode.ON, DeviceTestBlocks.block("eye_camera").defaultBlockState().getValue(EyeCameraBlock.MODE));
		assertEquals(TurretBlock.Mode.ACTIVE, DeviceTestBlocks.block("turret").defaultBlockState().getValue(TurretBlock.MODE));
		assertFalse(DeviceTestBlocks.block("crate").defaultBlockState().getValue(CrateBlock.READABLE));
		assertFalse(DeviceTestBlocks.block("net_port").defaultBlockState().getValue(NetPortBlock.BREACHED));
		assertEquals(0, DeviceTestBlocks.block("sliding_gate").defaultBlockState().getValue(SlidingGateBlock.STEP));
		assertFalse(DeviceTestBlocks.block("lift_platform").defaultBlockState().getValue(LiftPlatformBlock.MOVING));
		assertTrue(DeviceTestBlocks.block("screen_frame").defaultBlockState().getValue(ScreenFrameBlock.ON));
		assertEquals("alarm", EyeCameraBlock.Mode.ALARM.getSerializedName());
		assertEquals("friendly", TurretBlock.Mode.FRIENDLY.getSerializedName());
		assertEquals("amber", TrafficLampBlock.Signal.AMBER.getSerializedName());
	}

	@Test
	void shapesFollowTheArt() {
		CollisionContext empty = CollisionContext.empty();
		BlockState gate = DeviceTestBlocks.block("sliding_gate").defaultBlockState();
		// facing north: the panel spans x and slides toward the west (x = 0) as it opens
		assertEquals(new AABB(0, 0, 5 / 16.0, 1, 1, 11 / 16.0), gate.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, empty).bounds());
		List<AABB> half = gate.setValue(SlidingGateBlock.STEP, 2).getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, empty).toAabbs();
		assertTrue(half.stream().filter(b -> b.maxY > 0.5).allMatch(b -> b.minX == 0 && b.maxX <= 0.5 + 1e-9), half.toString());
		assertEquals(1 / 16.0, gate.setValue(SlidingGateBlock.STEP, SlidingGateBlock.OPEN_STEP).getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, empty)
			.bounds().maxY, 1e-9, "open: only the rail");
		// facing east: the same panel turned a quarter clockwise spans z and slides north
		assertEquals(new AABB(6 / 16.0, 1 / 16.0, 0, 10 / 16.0, 1, 0.5), SlidingGateBlock.box(Direction.EAST, 0, 1, 6, 8, 16, 10).bounds());
		assertEquals(new AABB(6 / 16.0, 1 / 16.0, 0.5, 10 / 16.0, 1, 1), SlidingGateBlock.box(Direction.WEST, 0, 1, 6, 8, 16, 10).bounds());
		assertEquals(new AABB(0.5, 1 / 16.0, 6 / 16.0, 1, 1, 10 / 16.0), SlidingGateBlock.box(Direction.SOUTH, 0, 1, 6, 8, 16, 10).bounds());
		assertEquals(0.25, DeviceTestBlocks.block("lift_platform").defaultBlockState().getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, empty)
			.bounds().maxY, 1e-9);
		assertEquals(LiftPlatformBlock.HEIGHT, 0.25, 1e-9);
	}
}
