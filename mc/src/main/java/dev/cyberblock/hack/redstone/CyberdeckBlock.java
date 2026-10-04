package dev.cyberblock.hack.redstone;

import dev.cyberblock.hack.redstone.RedstoneRules.Deck;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The Cyberdeck block (megaplan 6-R1): RAM for the Breach Ports next to it. It holds {@link RedstoneRules#DECK_MAX_RAM} and gets one
 * point back every 5 s, starting empty when a player places it; a comparator reads how full it is; a right-click says so. Block state (6A's blockstate draws it): a
 * horizontal {@code facing} (its front towards whoever placed it) and {@code ram} 0..4, the lights on the front
 * ({@link Deck#lights}), kept in step by {@link #refresh} whenever the RAM changes and at each refill point until it is full.
 */
public final class CyberdeckBlock extends HackBlock {
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final IntegerProperty RAM = IntegerProperty.create("ram", 0, Deck.LIGHTS);

	CyberdeckBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(RAM, Deck.LIGHTS));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, RAM);
	}

	/** A placed Cyberdeck block starts dark: empty, it refills one point every 5 s ({@link Deck#placed}). */
	@Override
	public BlockState getStateForPlacement(final BlockPlaceContext context) {
		return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()).setValue(RAM, 0);
	}

	/** Its RAM starts empty, so breaking and replacing the block does not refill it. */
	@Override
	public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state, final @Nullable LivingEntity by, final ItemStack stack) {
		super.setPlacedBy(level, pos, state, by, stack);
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof HackBlockEntity be) {
			be.deck(Deck.placed(RedstoneHacks.now(level)));
		}
	}

	/**
	 * The RAM changed or a refill point came: the front's lights follow it, comparators read again, and the next refill point is
	 * scheduled while it is not full. Does nothing on any other block.
	 */
	static void refresh(final ServerLevel level, final BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof CyberdeckBlock block) || !(level.getBlockEntity(pos) instanceof HackBlockEntity be)) {
			return;
		}

		long now = RedstoneHacks.now(level);
		Deck deck = be.deck(now);
		int lights = deck.lights(now);
		if (lights != state.getValue(RAM)) {
			level.setBlock(pos, state.setValue(RAM, lights), Block.UPDATE_CLIENTS);
		}

		level.updateNeighbourForOutputSignal(pos, block);
		long wait = deck.untilRefill(now);
		if (wait > 0) {
			level.scheduleTick(pos, block, (int) wait);
		}
	}

	@Override
	protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
		refresh(level, pos);
	}

	@Override
	protected boolean hasAnalogOutputSignal(final BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(final BlockState state, final Level level, final BlockPos pos, final Direction direction) {
		return level.getBlockEntity(pos) instanceof HackBlockEntity be ? be.deck(RedstoneHacks.now(level)).signal(RedstoneHacks.now(level)) : 0;
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (!level.isClientSide() && level.getBlockEntity(pos) instanceof HackBlockEntity be) {
			player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.redstone.cyberdeck.ram", "RAM %s of %s",
				be.deck(RedstoneHacks.now(level)).ram(RedstoneHacks.now(level)), RedstoneRules.DECK_MAX_RAM));
		}

		return InteractionResult.SUCCESS;
	}
}
