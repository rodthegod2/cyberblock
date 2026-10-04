package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Containers (section 1.11, toggle bit 1024 {@code container}; read only). A {@link #READABLE} crate is a Night City container whose
 * loot the host's game can read: a right-click asks to {@code view} it and the server shows the contents in a vanilla chest view
 * ({@link LootView}, nothing can be taken). An unreadable one stays a closed crate.
 */
public final class CrateBlock extends DeviceBlock {
	public static final BooleanProperty READABLE = BooleanProperty.create("readable");

	CrateBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(READABLE, false).setValue(QUEST_LOCKED, false));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(READABLE);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (!state.getValue(READABLE)) {
			return DeviceHooks.say(player, "cyberblock.device.container.closed", "A closed crate: its contents are unknown");
		}

		return DeviceHooks.act(state, level, pos, player, "view");
	}
}
