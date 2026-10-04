package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.OutlineRules.Decision;
import dev.cyberblock.OutlineRules.Kind;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Plan Q-B4: game mode x setting x block kind. */
class OutlineRulesTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void everyCombination() {
		for (Kind kind : Kind.values()) {
			for (boolean erases : new boolean[] {false, true}) {
				assertEquals(Decision.DENY, OutlineRules.decide(GameType.SURVIVAL, erases, kind), kind + " survival");
				assertEquals(Decision.DENY, OutlineRules.decide(GameType.ADVENTURE, erases, kind), kind + " adventure");
				assertEquals(Decision.DENY, OutlineRules.decide(GameType.SPECTATOR, erases, kind), kind + " spectator");
				assertEquals(erases ? Decision.KEEP : Decision.SNAP_BACK, OutlineRules.decide(GameType.CREATIVE, erases, kind), kind + " creative");
			}
		}
	}

	@Test
	void everyGameModeIsDecided() {
		for (GameType mode : GameType.values()) {
			assertNotNull(OutlineRules.decide(mode, false, Kind.OUTLINE_BARRIER));
		}
	}

	@Test
	void permanentEraseIsOffByDefault() {
		assertFalse(OutlineRules.creativeErases());
	}

	@Test
	void explosionsSpareOutlineBlocksOnly() {
		assertTrue(OutlineRules.sparedByExplosions(Blocks.BARRIER.defaultBlockState()));
		assertTrue(OutlineRules.sparedByExplosions(Blocks.BARRIER.defaultBlockState().setValue(net.minecraft.world.level.block.BarrierBlock.WATERLOGGED, true)));
		assertFalse(OutlineRules.sparedByExplosions(Blocks.STONE.defaultBlockState()));
		assertFalse(OutlineRules.sparedByExplosions(Blocks.OBSIDIAN.defaultBlockState()));
	}
}
