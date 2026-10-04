package dev.cyberblock.mode.overlord;

import dev.cyberblock.Cyberblock;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Overlord Console (megaplan 8-O1: "a block in the hub and a craftable item"): {@code cyberblock:overlord_console}, registered on
 * both sides with its item. Right-clicking the block opens the console; so does using the item in the air, so a crafted console
 * works from the hand as well as placed (recipe/overlord_console.json; the hub copy is placed by the hub layout, where nobody but
 * creative players breaks blocks). Its art (blockstate, model, texture, item definition) and its English name are 8A's, under this
 * id. In the Cyberblock creative tab.
 */
public final class OverlordBlocks {
	public static final String CONSOLE_ID = "overlord_console";
	public static Block CONSOLE;
	public static Item CONSOLE_ITEM;

	private OverlordBlocks() {
	}

	static void register() {
		Identifier id = Identifier.fromNamespaceAndPath(Cyberblock.ID, CONSOLE_ID);
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
		CONSOLE = Registry.register(BuiltInRegistries.BLOCK, blockKey, new ConsoleBlock(BlockBehaviour.Properties.of().setId(blockKey)
			.mapColor(MapColor.COLOR_RED).sound(SoundType.METAL).strength(1.5F, 1200.0F).noOcclusion().lightLevel(state -> 9)
			.isValidSpawn(Blocks::never)));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
		CONSOLE_ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey, new ConsoleItem(CONSOLE, new Item.Properties().setId(itemKey)
			.useBlockDescriptionPrefix()));
		CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(Cyberblock.ID,
			"cyberblock"))).register(output -> output.accept(CONSOLE_ITEM));
	}

	/** The placed console: a right-click opens it (the server checks the player is an Overlord). */
	static final class ConsoleBlock extends Block {
		ConsoleBlock(final Properties properties) {
			super(properties);
		}

		@Override
		protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player,
			final BlockHitResult hit) {
			if (player instanceof ServerPlayer serverPlayer) {
				Overlord.open(serverPlayer, "console at " + pos.toShortString());
				return InteractionResult.SUCCESS_SERVER;
			}

			return InteractionResult.SUCCESS;
		}
	}

	/** The console in the hand: used in the air it opens; used on a block it is placed like any block item. */
	static final class ConsoleItem extends BlockItem {
		ConsoleItem(final Block block, final Properties properties) {
			super(block, properties);
		}

		@Override
		public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
			if (player instanceof ServerPlayer serverPlayer) {
				Overlord.open(serverPlayer, "console in hand");
				return InteractionResult.SUCCESS_SERVER;
			}

			return InteractionResult.SUCCESS;
		}
	}
}
