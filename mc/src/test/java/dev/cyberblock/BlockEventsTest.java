package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.BlockEvents.Cause;
import dev.cyberblock.BlockEvents.Op;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockEventsTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static BlockState stone() {
		return Blocks.STONE.defaultBlockState();
	}

	private static BlockState air() {
		return Blocks.AIR.defaultBlockState();
	}

	@Test
	void solidBlocksFromTheWorldAreReported() {
		assertTrue(BlockEvents.shouldReport(stone(), Cause.WORLD));
		assertTrue(BlockEvents.shouldReport(Blocks.GLASS.defaultBlockState(), Cause.WORLD));
		assertTrue(BlockEvents.shouldReport(Blocks.OAK_FENCE.defaultBlockState(), Cause.WORLD));
	}

	@Test
	void blocksTheHostPlacedItselfAreNeverReported() {
		assertFalse(BlockEvents.shouldReport(stone(), Cause.HOST_GROUND));
	}

	@Test
	void barriersAreNeverReported() {
		assertFalse(BlockEvents.shouldReport(Blocks.BARRIER.defaultBlockState(), Cause.WORLD));
	}

	@Test
	void airAndShapelessBlocksAreNotSolid() {
		assertFalse(BlockEvents.shouldReport(air(), Cause.WORLD));
		assertFalse(BlockEvents.shouldReport(Blocks.TORCH.defaultBlockState(), Cause.WORLD));
		assertFalse(BlockEvents.shouldReport(Blocks.WATER.defaultBlockState(), Cause.WORLD));
		assertFalse(BlockEvents.shouldReport(Blocks.SHORT_GRASS.defaultBlockState(), Cause.WORLD));
	}

	@Test
	void placingAndBreakingMapToAddAndRemove() {
		assertEquals(Op.ADD, BlockEvents.change(air(), stone(), Cause.WORLD));
		assertEquals(Op.REMOVE, BlockEvents.change(stone(), air(), Cause.WORLD));
	}

	@Test
	void solidToSolidAndAirToAirNeedNoEvent() {
		assertNull(BlockEvents.change(stone(), Blocks.DIRT.defaultBlockState(), Cause.WORLD));
		assertNull(BlockEvents.change(air(), Blocks.TORCH.defaultBlockState(), Cause.WORLD));
	}

	@Test
	void replacingABarrierWithABlockIsAnAdd() {
		assertEquals(Op.ADD, BlockEvents.change(Blocks.BARRIER.defaultBlockState(), stone(), Cause.WORLD));
		assertNull(BlockEvents.change(Blocks.BARRIER.defaultBlockState(), air(), Cause.WORLD));
	}

	@Test
	void hostGroundChangesProduceNothing() {
		assertNull(BlockEvents.change(air(), stone(), Cause.HOST_GROUND));
		assertNull(BlockEvents.change(stone(), air(), Cause.HOST_GROUND));
	}

	@Test
	void doorsAreSolidOnlyWhileClosed() {
		BlockState closed = Blocks.OAK_DOOR.defaultBlockState();
		BlockState open = closed.setValue(net.minecraft.world.level.block.DoorBlock.OPEN, true);
		assertEquals(Op.REMOVE, BlockEvents.change(closed, open, Cause.WORLD));
		assertEquals(Op.ADD, BlockEvents.change(open, closed, Cause.WORLD));
	}

	private static List<String> drain(final BlockEvents.Outbox box, final int max) {
		List<String> out = new ArrayList<>();
		box.drain(max, (pos, op) -> out.add(op + "@" + BlockPos.getX(pos) + "," + BlockPos.getY(pos) + "," + BlockPos.getZ(pos)));
		return out;
	}

	@Test
	void outboxKeepsOrderAndPacesTheEvents() {
		BlockEvents.Outbox box = new BlockEvents.Outbox();
		for (int i = 0; i < 5; i++) {
			box.put(BlockPos.asLong(i, 64, -i), Op.ADD);
		}

		assertEquals(List.of("ADD@0,64,0", "ADD@1,64,-1"), drain(box, 2));
		assertEquals(3, box.size());
		assertEquals(List.of("ADD@2,64,-2", "ADD@3,64,-3", "ADD@4,64,-4"), drain(box, 64));
		assertEquals(0, box.size());
	}

	@Test
	void outboxKeepsOnlyTheLastStateOfACell() {
		BlockEvents.Outbox box = new BlockEvents.Outbox();
		box.put(BlockPos.asLong(1, 2, 3), Op.ADD);
		box.put(BlockPos.asLong(1, 2, 3), Op.REMOVE);
		assertEquals(List.of("REMOVE@1,2,3"), drain(box, 64));
	}

	@Test
	void outboxClearForgetsEverything() {
		BlockEvents.Outbox box = new BlockEvents.Outbox();
		box.put(BlockPos.asLong(1, 2, 3), Op.ADD);
		box.clear();
		assertEquals(0, box.size());
		assertEquals(List.of(), drain(box, 64));
	}

	@Test
	void blockMessageIsOneJsonLine() {
		String m = Wire.block(Op.ADD, 10, -4, -21);
		assertEquals("{\"t\":\"block\",\"op\":\"add\",\"pos\":[10,-4,-21]}", m);
		assertEquals("{\"t\":\"block\",\"op\":\"remove\",\"pos\":[0,0,0]}", Wire.block(Op.REMOVE, 0, 0, 0));
	}
}
