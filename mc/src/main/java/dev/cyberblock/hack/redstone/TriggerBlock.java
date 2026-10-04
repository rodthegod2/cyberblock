package dev.cyberblock.hack.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The blocks a redstone rising edge fires (megaplan 6-R1): the Device Relay (toggles its linked device), the Breach Port (hacks the
 * figures in range) and the Charge (a real Cyberpunk explosion). {@code triggered} holds the input like a dispenser's, so only the
 * edge acts; RedstoneHacks applies the rate limit of 2 actions a second.
 */
public final class TriggerBlock extends HackBlock {
	public static final BooleanProperty TRIGGERED = BlockStateProperties.TRIGGERED;

	public enum Kind {
		RELAY, BREACH_PORT, CHARGE
	}

	private final Kind kind;

	TriggerBlock(final Kind kind, final BlockBehaviour.Properties properties) {
		super(properties);
		this.kind = kind;
		this.registerDefaultState(this.stateDefinition.any().setValue(TRIGGERED, false));
	}

	public Kind kind() {
		return this.kind;
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(TRIGGERED);
	}

	/** Placed onto a powered spot it holds that input: no action until the signal goes and comes back. */
	@Override
	public BlockState getStateForPlacement(final BlockPlaceContext context) {
		return this.defaultBlockState().setValue(TRIGGERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
	}

	@Override
	protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block from, final @Nullable Orientation orientation,
		final boolean moved) {
		boolean powered = level.hasNeighborSignal(pos);
		boolean was = state.getValue(TRIGGERED);
		if (powered == was) {
			return;
		}

		level.setBlock(pos, state.setValue(TRIGGERED, powered), Block.UPDATE_CLIENTS);
		if (powered && level instanceof ServerLevel server) {
			RedstoneHacks.fire(server, pos, this.kind);
		}
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (player instanceof ServerPlayer p && level instanceof ServerLevel server) {
			RedstoneHacks.use(server, pos, this.kind, p);
		}

		return InteractionResult.SUCCESS;
	}
}
