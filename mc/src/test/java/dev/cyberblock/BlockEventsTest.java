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

	/**
	 * Plan retest3 M6 (F1): the Cobweb Daemon puts real cobwebs round a hacked NPC and takes them away after 6 s. A cobweb has no
	 * collision shape, so neither sends a {"t":"block"}: no invisible 1 m box traps V or the NPC in Night City.
	 */
	@Test
	void cobwebsFromTheCobwebDaemonSendNoBlockEvent() {
		BlockState web = Blocks.COBWEB.defaultBlockState();
		assertFalse(BlockEvents.shouldReport(web, Cause.WORLD));
		assertNull(BlockEvents.change(air(), web, Cause.WORLD), "placed into air");
		assertNull(BlockEvents.change(web, air(), Cause.WORLD), "removed after 6 s");
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

	/**
	 * Plan Q-B4: a player breaking an outline block removes only the Minecraft block; Cyberpunk hears nothing, whoever broke it. A
	 * waterlogged barrier leaves water behind.
	 */
	@Test
	void breakingAnOutlineBlockSendsNothing() {
		BlockState barrier = Blocks.BARRIER.defaultBlockState();
		BlockState wet = barrier.setValue(net.minecraft.world.level.block.BarrierBlock.WATERLOGGED, true);
		for (Cause cause : Cause.values()) {
			assertNull(BlockEvents.change(barrier, air(), cause), "barrier to air, " + cause);
			assertNull(BlockEvents.change(wet, Blocks.WATER.defaultBlockState(), cause), "waterlogged barrier to water, " + cause);
		}
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

	/** Events.reds drains up to 64 events per Tick and CyberblockSystem ticks it every 100 ms: the host's real consumer rate. */
	private static final int HOST_DRAIN_PER_CALL = 64;
	private static final int HOST_CALLS_PER_SECOND = 10;

	@Test
	void blocksLeaveAtHalfTheRateTheHostDrains() {
		int hostPerSecond = HOST_DRAIN_PER_CALL * HOST_CALLS_PER_SECOND;
		assertEquals(hostPerSecond / 2, BlockEvents.PER_TICK * 20);
	}

	@Test
	void blockMessageIsOneJsonLine() {
		String m = Wire.block(Op.ADD, 10, -4, -21);
		assertEquals("{\"t\":\"block\",\"op\":\"add\",\"pos\":[10,-4,-21]}", m);
		assertEquals("{\"t\":\"block\",\"op\":\"remove\",\"pos\":[0,0,0]}", Wire.block(Op.REMOVE, 0, 0, 0));
	}
}
