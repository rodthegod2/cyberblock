package dev.cyberblock.hack.redstone;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import org.jspecify.annotations.Nullable;

/**
 * A redstone hacking block (megaplan 6-R1): a player's block (placed from its item, drops itself), with a {@link HackBlockEntity}
 * that remembers who placed it: the owner's guest toggles are checked against that player.
 */
public abstract class HackBlock extends Block implements EntityBlock {
	protected HackBlock(final BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
		return new HackBlockEntity(RedstoneBlocks.ENTITY, pos, state);
	}

	@Override
	public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state, final @Nullable LivingEntity by, final ItemStack stack) {
		super.setPlacedBy(level, pos, state, by, stack);
		if (!level.isClientSide() && by instanceof Player player && level.getBlockEntity(pos) instanceof HackBlockEntity be) {
			be.actor(player.getUUID());
		}
	}

	/** Itself, without a loot table file (the block's art and data are 6A's; its behaviour is code). */
	@Override
	protected List<ItemStack> getDrops(final BlockState state, final LootParams.Builder params) {
		return List.of(new ItemStack(this));
	}
}
