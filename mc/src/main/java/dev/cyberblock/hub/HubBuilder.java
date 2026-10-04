package dev.cyberblock.hub;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.cyberblock.Cyberblock;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Builds the Afterlife once (megaplan 7-H1) from the layout file, or from the built-in platform when the file is missing or bad: the
 * steps in order, then the stations (the Match Terminal and the exit portal face the arrival point; the Holo-Table is 7T's block and is
 * placed only when it exists) and the leaderboard boards with their objectives. A block id the game does not know is skipped and named
 * once in the build's log line. Server thread only.
 */
final class HubBuilder {
	/** The Holo-Table's block (package 7T); the station stays empty until it exists. */
	static final Identifier HOLO_TABLE = Identifier.fromNamespaceAndPath(Cyberblock.ID, "holo_table");

	/** A layout and where it came from (the file's id, or "built-in" for the fallback platform). */
	record Source(String from, HubLayout layout) {
	}

	private HubBuilder() {
	}

	/** The layout file of the server's data, or the built-in platform (the log says why). */
	static Source load(final MinecraftServer server) {
		Optional<Resource> file = server.getResourceManager().getResource(Afterlife.LAYOUT);
		if (file.isEmpty()) {
			Cyberblock.LOG.warn("hub: no layout {} in the server's data: the built-in platform is used", Afterlife.LAYOUT);
			return new Source("built-in", HubLayout.fallback());
		}

		try (Reader reader = file.get().openAsReader()) {
			JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
			return new Source("cyberblock:afterlife", HubLayout.parse(json));
		} catch (IOException | RuntimeException e) {
			Cyberblock.LOG.warn("hub: layout {} skipped: {}; the built-in platform is used", Afterlife.LAYOUT, e.getMessage());
			return new Source("built-in", HubLayout.fallback());
		}
	}

	/** Places the whole layout into the Afterlife and records it as built. Returns the number of cells set. */
	static int build(final ServerLevel level, final Source source, final HubData data) {
		HubLayout layout = source.layout();
		Map<String, BlockState> states = new HashMap<>();
		TreeSet<String> unknown = new TreeSet<>();
		int placed = 0;
		for (HubLayout.Step step : layout.steps()) {
			BlockState state = states.computeIfAbsent(step.block(), b -> parse(level, b));
			if (state == null) {
				unknown.add(step.block());
				continue;
			}

			HubLayout.Box box = step.box();
			for (int x = box.minX(); x <= box.maxX(); x++) {
				for (int y = box.minY(); y <= box.maxY(); y++) {
					for (int z = box.minZ(); z <= box.maxZ(); z++) {
						if (!step.hollow() || box.shell(x, y, z)) {
							placed += set(level, layout.world(new HubLayout.Cell(x, y, z)), state);
						}
					}
				}
			}
		}

		double[] spawn = layout.spawn();
		placed += station(level, layout, layout.matchTerminal(), HubBlocks.MATCH_TERMINAL, spawn);
		placed += station(level, layout, layout.exitPortal(), HubBlocks.HUB_PORTAL, spawn);
		Block holo = BuiltInRegistries.BLOCK.getOptional(HOLO_TABLE).orElse(null);
		if (holo != null) {
			placed += station(level, layout, layout.holoTable(), holo, spawn);
		} else {
			unknown.add(HOLO_TABLE.toString());
		}

		for (HubLayout.Board b : layout.boards()) {
			BlockPos pos = pos(layout.world(b.pos()));
			placed += set(level, layout.world(b.pos()), HubBlocks.LEADERBOARD.defaultBlockState().setValue(HubBlocks.FACING, Direction.byName(b.facing())));
			if (level.getBlockEntity(pos) instanceof LeaderboardBlockEntity board) {
				board.configure(b.objective(), b.titleKey());
			}
		}

		data.built(source.from(), layout);
		Cyberblock.LOG.info("hub: built the Afterlife from {}: {} cells, {} boards, {} hub figures for 7N{}", source.from(), placed, layout.boards().size(),
			layout.figures(), unknown.isEmpty() ? "" : "; unknown blocks skipped: " + String.join(", ", unknown));
		return placed;
	}

	/** A station block facing the arrival point (a block without {@code facing} as it is); kept when the steps already placed it. */
	private static int station(final ServerLevel level, final HubLayout layout, final HubLayout.Cell cell, final Block block, final double[] spawn) {
		if (level.getBlockState(pos(layout.world(cell))).is(block)) {
			return 0;
		}

		BlockState state = block.defaultBlockState();
		if (state.hasProperty(HubBlocks.FACING)) {
			state = state.setValue(HubBlocks.FACING, Direction.byName(HubRules.facingToward(cell.x() + 0.5, cell.z() + 0.5, spawn[0], spawn[2])));
		}

		return set(level, layout.world(cell), state);
	}

	private static int set(final ServerLevel level, final HubLayout.Cell cell, final BlockState state) {
		return level.setBlock(pos(cell), state, Block.UPDATE_ALL) ? 1 : 0;
	}

	private static BlockPos pos(final HubLayout.Cell cell) {
		return new BlockPos(cell.x(), cell.y(), cell.z());
	}

	/** A block with optional state ({@code minecraft:stone_bricks}, {@code minecraft:oak_stairs[facing=east]}), or null when unknown. */
	private static @Nullable BlockState parse(final ServerLevel level, final String block) {
		try {
			return BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK), block, false).blockState();
		} catch (CommandSyntaxException e) {
			return null;
		}
	}
}
