package dev.cyberblock.hack.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The two sensors (megaplan 6-R1), checked every {@link #PERIOD_TICKS}: the Proximity Sensor gives a full signal while a person, a
 * car or V (its screen picks which) is within its range (1-16 blocks), from the figures and the hosts' positions; the Heat Sensor's
 * strength is the nearest host's NCPD heat (stats.wanted 0-5) times 3. {@code power} is the output.
 */
public final class SensorBlock extends HackBlock {
	public static final IntegerProperty POWER = BlockStateProperties.POWER;
	static final int PERIOD_TICKS = 10;

	public enum Kind {
		PROXIMITY, HEAT
	}

	private final Kind kind;

	SensorBlock(final Kind kind, final BlockBehaviour.Properties properties) {
		super(properties);
		this.kind = kind;
		this.registerDefaultState(this.stateDefinition.any().setValue(POWER, 0));
	}

	public Kind kind() {
		return this.kind;
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(POWER);
	}

	@Override
	protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState old, final boolean moved) {
		if (!old.is(this)) {
			level.scheduleTick(pos, this, 1);
		}
	}

	@Override
	protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
		int power = RedstoneHacks.sense(level, pos, this.kind);
		if (power != state.getValue(POWER)) {
			level.setBlock(pos, state.setValue(POWER, power), Block.UPDATE_ALL);
		}

		level.scheduleTick(pos, this, PERIOD_TICKS);
	}

	@Override
	protected boolean isSignalSource(final BlockState state) {
		return true;
	}

	@Override
	protected int getSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction) {
		return state.getValue(POWER);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (player instanceof ServerPlayer p && level instanceof ServerLevel server) {
			RedstoneHacks.useSensor(server, pos, this.kind, p);
		}

		return InteractionResult.SUCCESS;
	}
}
