package dev.cyberblock.hub;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.holo.HoloBlocks;
import java.util.function.Function;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;

/**
 * The hub's blocks (megaplan 7-H1; their art, block states and names are 7A's): the hub portal ({@code cyberblock:hub_portal}, no
 * properties), placeable in the replica, takes a player to the Afterlife and, in the Afterlife, back; the Match Terminal
 * ({@code cyberblock:match_terminal}, {@code facing}) opens Phase 8's match screen; the leaderboard board ({@code cyberblock:leaderboard_board},
 * {@code facing}, a block entity) shows a scoreboard objective. Like the device blocks they are placed by the hub build or by creative
 * players and operators: unbreakable in survival, spared by explosions, no drops and no mob spawns. They sit in Cyberblock's creative tab.
 */
public final class HubBlocks {
	public static final String HUB_PORTAL_NAME = "hub_portal";
	public static final String MATCH_TERMINAL_NAME = "match_terminal";
	public static final String LEADERBOARD_NAME = "leaderboard_board";
	public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;
	public static Block HUB_PORTAL;
	public static Block MATCH_TERMINAL;
	public static Block LEADERBOARD;
	public static BlockEntityType<LeaderboardBlockEntity> LEADERBOARD_ENTITY;

	private HubBlocks() {
	}

	/** Common init, once. */
	static void register() {
		HUB_PORTAL = block(HUB_PORTAL_NAME, HubPortalBlock::new, props(HUB_PORTAL_NAME, MapColor.COLOR_PURPLE, SoundType.AMETHYST).lightLevel(state -> 12));
		MATCH_TERMINAL = block(MATCH_TERMINAL_NAME, MatchTerminalBlock::new, props(MATCH_TERMINAL_NAME, MapColor.COLOR_CYAN, SoundType.METAL).lightLevel(state -> 8));
		LEADERBOARD = block(LEADERBOARD_NAME, LeaderboardBlock::new, props(LEADERBOARD_NAME, MapColor.COLOR_BLACK, SoundType.METAL).lightLevel(state -> 6));
		LEADERBOARD_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id(LEADERBOARD_NAME),
			FabricBlockEntityTypeBuilder.create(LeaderboardBlockEntity::new, LEADERBOARD).build());
		CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("cyberblock")))
			.register(output -> {
				output.accept(HUB_PORTAL);
				output.accept(MATCH_TERMINAL);
				output.accept(LEADERBOARD);
			});
	}

	/** Is this a block with its own use in the hub, one of the hub's or 7T's Holo-Table (the hub lets a player use these whatever they hold)? */
	static boolean usable(final BlockState state) {
		return state.is(HUB_PORTAL) || state.is(MATCH_TERMINAL) || state.is(LEADERBOARD) || state.is(HoloBlocks.HOLO_TABLE);
	}

	private static Identifier id(final String name) {
		return Identifier.fromNamespaceAndPath(Cyberblock.ID, name);
	}

	private static BlockBehaviour.Properties props(final String name, final MapColor colour, final SoundType sound) {
		return BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, id(name))).mapColor(colour).sound(sound)
			.strength(-1.0F, 3600000.8F).noLootTable().isValidSpawn(Blocks::never);
	}

	private static Block block(final String name, final Function<BlockBehaviour.Properties, Block> factory, final BlockBehaviour.Properties properties) {
		Block block = Registry.register(BuiltInRegistries.BLOCK, ResourceKey.create(Registries.BLOCK, id(name)), factory.apply(properties));
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, id(name));
		Registry.register(BuiltInRegistries.ITEM, key, new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
		return block;
	}

	/** A hub block with a front ({@link #FACING}: where the front looks, toward the player who placed it). */
	abstract static class Facing extends Block {
		Facing(final BlockBehaviour.Properties properties) {
			super(properties);
			this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
		}

		@Override
		protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(FACING);
		}

		@Override
		public BlockState getStateForPlacement(final BlockPlaceContext context) {
			return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
		}

		@Override
		protected BlockState rotate(final BlockState state, final Rotation rotation) {
			return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
		}

		@Override
		protected BlockState mirror(final BlockState state, final Mirror mirror) {
			return state.rotate(mirror.getRotation(state.getValue(FACING)));
		}
	}
}
