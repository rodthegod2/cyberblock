package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.OutlineRules.Decision;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BarrierBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Plan Q-B6: snap-back timer, probe re-report, KEEP, and that no outline break ever reaches Cyberpunk. */
class OutlineGuardTest {
	private static final long CELL = BlockPos.asLong(12, 63, -40);
	private static final long T = 1000L;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static long[] due(final OutlineGuard guard, final long now) {
		return guard.due(now, cell -> true, cell -> true);
	}

	@Test
	void snapBackAfter200TicksNotBefore() {
		OutlineGuard guard = new OutlineGuard();
		guard.broke(CELL, T, Decision.SNAP_BACK);
		assertArrayEquals(new long[0], due(guard, T + 199));
		assertArrayEquals(new long[] {CELL}, due(guard, T + 200));
		assertFalse(guard.tracks(CELL), "put back once");
		assertArrayEquals(new long[0], due(guard, T + 400));
	}

	@Test
	void anOccupiedCellIsNeverPutBack() {
		OutlineGuard guard = new OutlineGuard();
		guard.broke(CELL, T, Decision.SNAP_BACK);
		assertArrayEquals(new long[0], guard.due(T + 200, cell -> false, cell -> true), "a player built there");
		assertFalse(guard.tracks(CELL));
		assertArrayEquals(new long[0], due(guard, T + 400));
	}

	@Test
	void aPlayerInTheCellMakesItWait() {
		OutlineGuard guard = new OutlineGuard();
		guard.broke(CELL, T, Decision.SNAP_BACK);
		assertArrayEquals(new long[0], guard.due(T + 200, cell -> true, cell -> false));
		assertArrayEquals(new long[] {CELL}, guard.due(T + 260, cell -> true, cell -> true));
	}

	@Test
	void anUnclaimedCellIsNeverPutBack() {
		OutlineGuard guard = new OutlineGuard();
		guard.broke(CELL, T, Decision.SNAP_BACK);
		guard.unclaimed(CELL); // the probes say Night City has no ground there any more
		assertArrayEquals(new long[0], due(guard, T + 200));
	}

	@Test
	void aProbeReReportBringsItBackAtOnce() {
		OutlineGuard guard = new OutlineGuard();
		guard.broke(CELL, T, Decision.SNAP_BACK);
		assertTrue(guard.reported(CELL), "WorldBridge.solid puts the barrier back into the air now");
		assertFalse(guard.reported(CELL));
		assertArrayEquals(new long[0], due(guard, T + 200), "nothing left to time");
		assertFalse(guard.reported(BlockPos.asLong(0, 0, 0)), "a cell nobody broke");
	}

	@Test
	void keptCellsWaitForTheProbes() {
		OutlineGuard guard = new OutlineGuard();
		guard.broke(CELL, T, Decision.KEEP);
		assertArrayEquals(new long[0], due(guard, T + 200));
		assertArrayEquals(new long[0], due(guard, T + 100_000));
		assertTrue(guard.tracks(CELL));
		assertTrue(guard.reported(CELL), "V walked away and back: the probes report it again");
	}

	@Test
	void clearAndDetachForgetEverything() {
		OutlineGuard guard = new OutlineGuard();
		guard.broke(CELL, T, Decision.SNAP_BACK);
		guard.broke(BlockPos.asLong(1, 2, 3), T, Decision.KEEP);
		guard.clear();
		assertEquals(0, guard.size());
		assertArrayEquals(new long[0], due(guard, T + 200));
	}

	@Test
	void deniedBreaksAreNotTracked() {
		OutlineGuard guard = new OutlineGuard();
		guard.broke(CELL, T, Decision.DENY);
		assertEquals(0, guard.size());
	}

	/**
	 * Regression (plan Q-B6): every block change an outline break and its return make, in every game mode and setting, produces no
	 * host event: the break (barrier to air, or a waterlogged barrier to water) is the world's doing, the return is ours.
	 */
	@Test
	void noOutlineBreakEverProducesAHostEvent() {
		BlockState barrier = Blocks.BARRIER.defaultBlockState();
		List<BlockState[]> changes = new ArrayList<>();
		changes.add(new BlockState[] {barrier, Blocks.AIR.defaultBlockState()});
		changes.add(new BlockState[] {barrier.setValue(BarrierBlock.WATERLOGGED, true), Blocks.WATER.defaultBlockState()});
		for (GameType mode : GameType.values()) {
			for (boolean erases : new boolean[] {false, true}) {
				for (OutlineRules.Kind kind : OutlineRules.Kind.values()) {
					Decision d = OutlineRules.decide(mode, erases, kind);
					if (d == Decision.DENY) {
						continue; // nothing changes
					}

					for (BlockState[] c : changes) {
						assertNull(BlockEvents.change(c[0], c[1], BlockEvents.Cause.WORLD), mode + " " + erases + " " + kind + ": the break");
						// the snap-back or the probe report puts it back while placing the host's ground; a re-report into water too
						assertNull(BlockEvents.change(c[1], c[0], BlockEvents.Cause.HOST_GROUND), mode + " " + erases + " " + kind + ": the return");
						assertNull(BlockEvents.change(c[1], c[0], BlockEvents.Cause.WORLD), "a barrier is never news to the host");
					}
				}
			}
		}
	}
}
