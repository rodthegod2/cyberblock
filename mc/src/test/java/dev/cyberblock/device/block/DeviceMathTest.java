package dev.cyberblock.device.block;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.Test;

/** Megaplan 4-B1: the turret's aim, the gate and lift steps, the lamp colour, the screens' pictures. Pure. */
class DeviceMathTest {
	@Test
	void theTurretLooksTheEntityWay() {
		assertEquals(0.0F, TurretBlock.yawToward(0.0, 5.0), 1e-4F, "south");
		assertEquals(90.0F, TurretBlock.yawToward(-5.0, 0.0), 1e-4F, "west");
		assertEquals(180.0F, Math.abs(TurretBlock.yawToward(0.0, -5.0)), 1e-4F, "north");
		assertEquals(-90.0F, TurretBlock.yawToward(5.0, 0.0), 1e-4F, "east");
		assertEquals(45.0F, TurretBlock.pitchToward(3.0, -3.0), 1e-4F, "down is positive");
		assertEquals(-45.0F, TurretBlock.pitchToward(3.0, 3.0), 1e-4F);
	}

	@Test
	void theHeadTurnsTheShortWayAndNeverOvershoots() {
		assertEquals(9.0F, TurretBlock.approach(0.0F, 90.0F, 9.0F), 1e-4F);
		assertEquals(-9.0F, TurretBlock.approach(0.0F, 270.0F, 9.0F), 1e-4F, "270 is 90 the other way");
		assertEquals(185.0F, TurretBlock.approach(170.0F, -170.0F, 15.0F), 1e-4F, "across the wrap");
		assertEquals(42.0F, TurretBlock.approach(40.0F, 42.0F, 9.0F), 1e-4F);
	}

	@Test
	void theSweepStaysWithin40DegreesOfTheFront() {
		for (int t = 0; t < 400; t++) {
			float yaw = TurretBlock.sweep(Direction.EAST, t);
			assertTrue(Math.abs(yaw - Direction.EAST.toYRot()) <= 40.0F + 1e-3F, "t=" + t);
		}

		assertEquals(Direction.EAST.toYRot(), TurretBlock.sweep(Direction.EAST, 0), 1e-4F);
	}

	@Test
	void gatesAndLiftsMoveOneStepAtATime() {
		// a gate opens by quarter blocks (0 closed .. 4 x width open) and its far column empties first
		assertEquals(1, Gates.next(0, 4, true));
		assertEquals(16, Gates.next(16, 4, true));
		assertEquals(4, Gates.next(5, 4, false));
		assertEquals(0, Gates.next(0, 4, false));
		assertEquals(1, Gates.stepOf(1, 4, 3));
		assertEquals(0, Gates.stepOf(1, 4, 2));
		assertEquals(4, Gates.stepOf(5, 4, 3));
		assertEquals(1, Gates.stepOf(5, 4, 2));
		assertEquals(0, Gates.stepOf(5, 4, 0));
		assertEquals(4, Gates.stepOf(16, 4, 0));
		assertEquals(65, Lifts.next(64, 70));
		assertEquals(63, Lifts.next(64, 50));
		assertEquals(64, Lifts.next(64, 64));
		assertThrows(IllegalArgumentException.class, () -> new Gates.Gate(BlockPos.ZERO, Direction.UP, 2, 2));
		assertThrows(IllegalArgumentException.class, () -> new Gates.Gate(BlockPos.ZERO, Direction.EAST, 0, 2));
		assertThrows(IllegalArgumentException.class, () -> new Gates.Gate(BlockPos.ZERO, Direction.EAST, 2, Gates.MAX_SIZE + 1));
		assertThrows(IllegalArgumentException.class, () -> new Lifts.Shaft(0, 0, 2, 2, 70, 60));
		assertThrows(IllegalArgumentException.class, () -> new Lifts.Shaft(0, 0, Lifts.MAX_SIZE + 1, 2, 60, 70));
		// facing north, a gate spans east (clockwise) from its origin
		assertEquals(new BlockPos(3, 6, 0), new Gates.Gate(new BlockPos(1, 5, 0), Direction.NORTH, 4, 3).cell(2, 1));
		assertEquals(new BlockPos(1, 5, 2), new Gates.Gate(new BlockPos(1, 5, 0), Direction.EAST, 4, 3).cell(2, 0));
	}

	@Test
	void aScreenHangsFromTheViewersLeftToRight() {
		// a screen facing south is seen from the south: the viewer's left is west, right is east
		ScreenFrames.Screen screen = new ScreenFrames.Screen(new BlockPos(10, 64, 0), Direction.SOUTH, 3, 2);
		assertEquals(new BlockPos(12, 64, 0), screen.cell(2, 0));
		assertEquals(new BlockPos(10, 65, 0), screen.cell(0, 1));
		assertEquals(new BlockPos(12, 64, 1), screen.frame(2, 0), "the frame hangs on the body's front");
		assertThrows(IllegalArgumentException.class, () -> new ScreenFrames.Screen(BlockPos.ZERO, Direction.SOUTH, ScreenFrames.MAX_SIZE + 1, 1));
	}

	@Test
	void aDeviceColourPicksTheNearestDye() {
		for (DyeColor dye : DyeColor.values()) {
			assertEquals(dye, LampBlock.nearest(dye.getTextureDiffuseColor() & 0xFFFFFF), dye.getName());
		}

		assertEquals(DyeColor.RED, LampBlock.nearest(0xC02020));
		assertEquals(DyeColor.WHITE, LampBlock.nearest(0xFFFFFF));
	}

	@Test
	void aYawSnapsToTheBlocksFront() {
		assertEquals(Direction.SOUTH, FacingDeviceBlock.facing(10.0F));
		assertEquals(Direction.WEST, FacingDeviceBlock.facing(95.0F));
		assertEquals(Direction.NORTH, FacingDeviceBlock.facing(-170.0F));
	}

	@Test
	void screenPicturesAreDeterministicTiledAndDarkWhenOff() {
		byte[] a = ScreenImage.tile(3, 2, 2, 1, 0, true);
		assertArrayEquals(a, ScreenImage.tile(3, 2, 2, 1, 0, true));
		assertArrayEquals(a, ScreenImage.tile(3 + ScreenImage.PATTERNS, 2, 2, 1, 0, true), "patterns wrap");
		assertEquals(ScreenImage.TILE * ScreenImage.TILE, a.length);
		for (int p = 0; p < ScreenImage.PATTERNS; p++) {
			Set<Byte> inks = inks(ScreenImage.tile(p, 2, 1, 0, 0, true));
			assertTrue(inks.size() >= 4, "pattern " + p + " uses " + inks);
			assertTrue(inks.stream().allMatch(i -> i >= 0 && i < ScreenImage.INK.length));
			if (p > 0) {
				assertFalse(Arrays.equals(ScreenImage.tile(p - 1, 2, 1, 0, 0, true), ScreenImage.tile(p, 2, 1, 0, 0, true)), "pattern " + p);
			}
		}

		Set<Byte> off = inks(ScreenImage.tile(0, 3, 2, 2, 1, false));
		assertTrue(off.size() <= 2 && off.contains((byte) ScreenImage.Ink.BLACK.ordinal()), off.toString());
		assertThrows(IllegalArgumentException.class, () -> ScreenImage.tile(0, 2, 2, 2, 0, true));
		assertThrows(IllegalArgumentException.class, () -> ScreenImage.tile(0, 0, 1, 0, 0, true));
	}

	@Test
	void aScreensBorderRunsAcrossItsTiles() {
		// the neon frame is on the outer edge of the whole picture, not of each tile
		byte[] left = ScreenImage.tile(0, 2, 1, 0, 0, true);
		byte[] right = ScreenImage.tile(0, 2, 1, 1, 0, true);
		byte frame = left[0];
		assertEquals(frame, left[63 * ScreenImage.TILE]);
		assertEquals(frame, right[63 * ScreenImage.TILE + ScreenImage.TILE - 1]);
		assertNotEquals(frame, left[63 * ScreenImage.TILE + ScreenImage.TILE - 1], "no frame where the tiles meet");
	}

	private static Set<Byte> inks(final byte[] tile) {
		Set<Byte> out = new HashSet<>();
		for (byte b : tile) {
			out.add(b);
		}

		return out;
	}
}
