package dev.cyberblock.device.block;

import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Doors (section 1.11, toggle bit 1 {@code door}): a vanilla wooden door, or an iron one for a secure door, that only Night City
 * opens. It is a vanilla {@link DoorBlock} (same shapes, sounds and models) with three changes: a right-click asks Night City to
 * {@code open} or {@code close} it instead of swinging it, redstone and wind charges leave it alone, and it stands where Night City has
 * it without needing a floor. Its own block id (not {@code minecraft:oak_door}) lets an active host's client hide it like the other
 * device blocks, and carries {@link DeviceBlock#QUEST_LOCKED}. A double door is two of them with opposite hinges; {@link Doors} places
 * them and the lock lamp.
 */
public final class DeviceDoorBlock extends DoorBlock {
	DeviceDoorBlock(final BlockSetType type, final BlockBehaviour.Properties properties) {
		super(type, properties);
		this.registerDefaultState(this.defaultBlockState().setValue(DeviceBlock.QUEST_LOCKED, false));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(DeviceBlock.QUEST_LOCKED);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		BlockPos lower = state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
		return DeviceHooks.act(state, level, lower, player, state.getValue(OPEN) ? "close" : "open");
	}

	@Override
	protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block block, final Orientation orientation,
		final boolean movedByPiston) {
		// redstone never opens a Night City door: only Night City does
	}

	@Override
	protected void onExplosionHit(final BlockState state, final ServerLevel level, final BlockPos pos, final Explosion explosion,
		final BiConsumer<ItemStack, BlockPos> dropConsumer) {
		// a wind charge opens vanilla doors; this one stays as Night City has it
	}

	@Override
	protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos) {
		// Night City's door stands where it is, over replica ground or over nothing
		return true;
	}
}
