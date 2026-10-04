package dev.cyberblock.replica;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.BlockEvents;
import dev.cyberblock.OutlineRules;
import dev.cyberblock.replica.ReplicaRules.Ground;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Megaplan 3-R1: air-only placement, the seen rule, the radius keep, the Q5 table for REPLICA, no events, explosions. */
class ReplicaRulesTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s.replace('\'', '"')).getAsJsonObject();
	}

	@Test
	void groundGoesIntoAirOnly() {
		assertEquals(Ground.PLACE, ReplicaRules.onGround(false, true, false));
		assertEquals(Ground.CLAIM, ReplicaRules.onGround(false, false, false), "never over a player's block (or a replica block)");
		assertEquals(Ground.PROMOTE, ReplicaRules.onGround(false, false, true), "a projectile's barrier becomes Night City ground");
		assertEquals(Ground.SKIP, ReplicaRules.onGround(true, true, false), "a door or device cell");
		assertEquals(Ground.SKIP, ReplicaRules.onGround(true, false, true));
	}

	@Test
	void theExcludedHookIsEmptyUntilPhase4FillsIt() {
		assertFalse(ReplicaRules.excluded(123L));
		var before = ReplicaRules.excludedCells;
		try {
			ReplicaRules.excludedCells = cell -> cell == 123L;
			assertTrue(ReplicaRules.excluded(123L));
			assertFalse(ReplicaRules.excluded(124L));
		} finally {
			ReplicaRules.excludedCells = before;
		}
	}

	@Test
	void aProbeMaterialUpgradesAReplicaBlockAndAHeuristicNever() {
		int none = ReplicaBlocks.UNKNOWN;
		assertEquals(ReplicaBlocks.BRICK, ReplicaRules.regrade(ReplicaBlocks.CONCRETE_LIGHT, ReplicaBlocks.BRICK), "the probe's real material wins");
		assertEquals(none, ReplicaRules.regrade(ReplicaBlocks.BRICK, ReplicaBlocks.BRICK), "already right: no setBlock");
		assertEquals(none, ReplicaRules.regrade(ReplicaBlocks.CONCRETE_LIGHT, none), "a heuristic-only report never replaces a block");
		assertEquals(none, ReplicaRules.regrade(none, ReplicaBlocks.BRICK), "a player's block is never replaced");
		// end to end through the default palette: code 7 (brick) over a heuristic wall block
		assertEquals(ReplicaBlocks.BRICK, ReplicaRules.regrade(ReplicaBlocks.material(ReplicaTestBlocks.state(ReplicaBlocks.CONCRETE_LIGHT)),
			Palette.fromProbe(ReplicaBlocks.BRICK)));
		assertEquals(none, ReplicaRules.regrade(ReplicaBlocks.material(Blocks.STONE.defaultBlockState()), Palette.fromProbe(ReplicaBlocks.BRICK)));
	}

	@Test
	void ungroundRemovesOnlyCellsSeenEmpty() {
		assertTrue(ReplicaRules.ungroundRemoves(true, true, false));
		assertFalse(ReplicaRules.ungroundRemoves(false, true, false), "left the probe radius: the map keeps it");
		assertFalse(ReplicaRules.ungroundRemoves(true, false, false), "a player's block is never taken out");
		assertFalse(ReplicaRules.ungroundRemoves(true, true, true), "another host still reports it as ground");
	}

	@Test
	void seenIsOnlyALiteralTrue() {
		assertTrue(ReplicaRules.seen(json("{'t':'unground','c':[0,0,63,63],'seen':true}")));
		assertFalse(ReplicaRules.seen(json("{'t':'unground','c':[0,0,63,63]}")));
		assertFalse(ReplicaRules.seen(json("{'t':'unground','c':[0,0,63,63],'seen':false}")));
		assertFalse(ReplicaRules.seen(json("{'t':'unground','c':[0,0,63,63],'seen':'true'}")));
		assertFalse(ReplicaRules.seen(json("{'t':'unground','c':[0,0,63,63],'seen':1}")));
	}

	@Test
	void materialCodesPerQuadruple() {
		assertArrayEquals(new int[] {3, 0, 26}, ReplicaRules.codes(json("{'c':[0,0,1,1,1,0,1,1,2,0,1,1],'m':[3,0,26]}"), 3));
		assertArrayEquals(new int[] {0, 0, 5}, ReplicaRules.codes(json("{'m':[27,-1,5]}"), 3), "unknown codes read as 0");
		assertArrayEquals(new int[] {0}, ReplicaRules.codes(json("{'m':[2.5]}"), 1));
		assertNull(ReplicaRules.codes(json("{'c':[0,0,1,1]}"), 1), "no m: heuristic");
		assertNull(ReplicaRules.codes(json("{'m':[1,2]}"), 1), "wrong length: heuristic");
		assertNull(ReplicaRules.codes(json("{'m':['a']}"), 1));
		assertNull(ReplicaRules.codes(json("{'m':3}"), 1));
	}

	@Test
	void theMaterialPathIsProbesOnlyWithAKnownCode() {
		assertEquals("heuristic", ReplicaRules.materialPath(null));
		assertEquals("heuristic", ReplicaRules.materialPath(new int[] {0, 0}));
		assertEquals("probes", ReplicaRules.materialPath(new int[] {0, 4}));
	}

	@Test
	void creativeFollowsTheQ5TableForReplicaBlocks() {
		for (boolean erases : new boolean[] {false, true}) {
			assertEquals(OutlineRules.Decision.DENY, OutlineRules.decide(GameType.SURVIVAL, erases, OutlineRules.Kind.REPLICA));
			assertEquals(OutlineRules.Decision.DENY, OutlineRules.decide(GameType.ADVENTURE, erases, OutlineRules.Kind.REPLICA));
			assertEquals(OutlineRules.Decision.DENY, OutlineRules.decide(GameType.SPECTATOR, erases, OutlineRules.Kind.REPLICA));
			assertEquals(erases ? OutlineRules.Decision.KEEP : OutlineRules.Decision.SNAP_BACK,
				OutlineRules.decide(GameType.CREATIVE, erases, OutlineRules.Kind.REPLICA));
		}
	}

	@Test
	void replicaChangesAreNeverNewsToAHost() {
		BlockState air = Blocks.AIR.defaultBlockState(), stone = Blocks.STONE.defaultBlockState();
		for (int code = 1; code < ReplicaBlocks.CODES; code++) {
			BlockState replica = ReplicaTestBlocks.state(code);
			assertTrue(ReplicaBlocks.is(replica));
			assertEquals(code, ReplicaBlocks.material(replica));
			assertFalse(BlockEvents.shouldReport(replica, BlockEvents.Cause.WORLD), "never a player's block");
			assertNull(BlockEvents.change(air, replica, BlockEvents.Cause.HOST_GROUND), "placed from the probes");
			assertNull(BlockEvents.change(replica, air, BlockEvents.Cause.WORLD), "a creative player broke it: Cyberpunk hears nothing");
			assertNull(BlockEvents.change(replica, air, BlockEvents.Cause.HOST_GROUND), "an unground took it out");
			// a player's block where a replica block was (KEEP, then built over) is news, as any block
			assertEquals(BlockEvents.Op.ADD, BlockEvents.change(air, stone, BlockEvents.Cause.WORLD));
		}

		assertFalse(ReplicaBlocks.is(stone));
		assertEquals(ReplicaBlocks.UNKNOWN, ReplicaBlocks.material(stone));
	}

	@Test
	void explosionsNeverRemoveReplicaBlocks() {
		for (int code = 1; code < ReplicaBlocks.CODES; code++) {
			BlockState replica = ReplicaTestBlocks.state(code);
			assertTrue(OutlineRules.sparedByExplosions(replica), ReplicaBlocks.name(code));
			assertTrue(replica.getBlock().getExplosionResistance() >= 3_600_000F);
			assertEquals(-1.0F, replica.getBlock().defaultDestroyTime(), "unbreakable in survival");
		}

		assertFalse(OutlineRules.sparedByExplosions(Blocks.STONE.defaultBlockState()));
	}

	@Test
	void replicaBlocksAreSolidFullBlocksAndNeonGlows() {
		for (int code = 1; code < ReplicaBlocks.CODES; code++) {
			BlockState replica = ReplicaTestBlocks.state(code);
			assertTrue(replica.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO),
				ReplicaBlocks.name(code) + " is the host's collision");
			assertEquals(code >= ReplicaBlocks.NEON_FIRST ? 15 : 0, replica.getLightEmission(), ReplicaBlocks.name(code));
		}
	}

	@Test
	void theTwentySixIdsAndTheirCodes() {
		List<String> names = ReplicaBlocks.names();
		assertEquals(26, names.size());
		assertEquals(List.of("nc_concrete_light", "nc_concrete_dark", "nc_asphalt", "nc_sidewalk", "nc_glass", "nc_metal", "nc_brick", "nc_tile",
			"nc_ground", "nc_water"), names.subList(0, 10));
		assertEquals(List.of("nc_neon_white", "nc_neon_orange", "nc_neon_magenta", "nc_neon_light_blue", "nc_neon_yellow", "nc_neon_lime",
			"nc_neon_pink", "nc_neon_gray", "nc_neon_light_gray", "nc_neon_cyan", "nc_neon_purple", "nc_neon_blue", "nc_neon_brown",
			"nc_neon_green", "nc_neon_red", "nc_neon_black"), names.subList(10, 26));
		for (int code = 1; code <= 26; code++) {
			assertEquals(code, ReplicaBlocks.code("cyberblock:" + ReplicaBlocks.name(code)));
			assertEquals(code, ReplicaBlocks.code(ReplicaBlocks.name(code)));
		}

		assertNull(ReplicaBlocks.name(0));
		assertNull(ReplicaBlocks.name(27));
		assertEquals(0, ReplicaBlocks.code("minecraft:stone"));
		assertEquals(0, ReplicaBlocks.code("cyberblock:nc_neon_teal"));
	}
}
