package dev.cyberblock.hack.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The blocks a redstone rising edge fires (megaplan 6-R1): the Device Relay (toggles its linked device), the Breach Port (hacks the
 * figures in range) and the Charge (a real Cyberpunk explosion). {@code powered} holds the input like a dispenser's, so only the
 * edge acts; RedstoneHacks applies the rate limit of 2 actions a second. The Device Relay also shows {@code linked}, and the Breach
 * Port has a horizontal {@code facing} (its front towards whoever placed it): 6A's blockstates draw these.
 */
public class TriggerBlock extends HackBlock {
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
	public static final BooleanProperty LINKED = BooleanProperty.create("linked");
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

	public enum Kind {
		RELAY, BREACH_PORT, CHARGE
	}

	private final Kind kind;

	TriggerBlock(final Kind kind, final BlockBehaviour.Properties properties) {
		super(properties);
		this.kind = kind;
		BlockState state = this.stateDefinition.any().setValue(POWERED, false);
		if (state.hasProperty(LINKED)) {
			state = state.setValue(LINKED, false);
		}

		if (state.hasProperty(FACING)) {
			state = state.setValue(FACING, Direction.NORTH);
		}

		this.registerDefaultState(state);
	}

	public Kind kind() {
		return this.kind;
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(POWERED);
	}

	/** Placed onto a powered spot it holds that input: no action until the signal goes and comes back. */
	@Override
	public BlockState getStateForPlacement(final BlockPlaceContext context) {
		BlockState state = this.defaultBlockState().setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
		return state.hasProperty(FACING) ? state.setValue(FACING, context.getHorizontalDirection().getOpposite()) : state;
	}

	@Override
	protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block from, final @Nullable Orientation orientation,
		final boolean moved) {
		boolean powered = level.hasNeighborSignal(pos);
		boolean was = state.getValue(POWERED);
		if (powered == was) {
			return;
		}

		level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
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

	/** The Device Relay: {@code linked} once the Cyberdeck Link has given it a device. */
	static final class Relay extends TriggerBlock {
		Relay(final BlockBehaviour.Properties properties) {
			super(Kind.RELAY, properties);
		}

		@Override
		protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
			super.createBlockStateDefinition(builder);
			builder.add(LINKED);
		}
	}

	/** The Breach Port: its screen side faces whoever placed it. */
	static final class Port extends TriggerBlock {
		Port(final BlockBehaviour.Properties properties) {
			super(Kind.BREACH_PORT, properties);
		}

		@Override
		protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
			super.createBlockStateDefinition(builder);
			builder.add(FACING);
		}
	}
}
