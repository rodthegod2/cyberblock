package dev.cyberblock.device.block;

import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
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
import org.jspecify.annotations.Nullable;

/**
 * Doors (section 1.11, toggle bit 1 {@code door}): a vanilla wooden door, or an iron one for a secure door, that only Night City
 * opens. It is a vanilla {@link DoorBlock} (same shapes, sounds and models) with these changes: a right-click asks Night City to
 * {@code open} or {@code close} it instead of swinging it; mobs (villager and piglin door goals), redstone and wind charges leave it
 * alone; it stands where Night City has it without needing a floor; and it plays its open or close sound whenever its state changes,
 * however the server set it (4M sets the state directly). The wooden one uses {@link #NIGHT_CITY_WOOD}: oak's sounds, but not openable
 * by hand, so {@link DoorBlock#isWoodenDoor} is false and zombies never break it and mob pathing treats it like an iron door. Its own
 * block id (not {@code minecraft:oak_door}) lets an active host's client hide it like the other device blocks, and carries
 * {@link DeviceBlock#QUEST_LOCKED}. A double door is two of them with opposite hinges; {@link Doors} places them and the lock lamp.
 */
public final class DeviceDoorBlock extends DoorBlock {
	/** Oak's sounds and sound type, closed to hands and wind charges (BlockSetType's public 14-argument constructor; not registered). */
	static final BlockSetType NIGHT_CITY_WOOD = new BlockSetType("cyberblock_device_door", false, false, BlockSetType.OAK.canButtonBeActivatedByArrows(),
		BlockSetType.OAK.pressurePlateSensitivity(), BlockSetType.OAK.soundType(), BlockSetType.OAK.doorClose(), BlockSetType.OAK.doorOpen(),
		BlockSetType.OAK.trapdoorClose(), BlockSetType.OAK.trapdoorOpen(), BlockSetType.OAK.pressurePlateClickOff(), BlockSetType.OAK.pressurePlateClickOn(),
		BlockSetType.OAK.buttonClickOff(), BlockSetType.OAK.buttonClickOn());

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

	/**
	 * Mobs (DoorInteractGoal, InteractWithDoor) pass themselves and are ignored: only Night City opens this door. The server's own call
	 * ({@link Doors#setOpen}, entity null) sets the state; the sound comes from {@link #onPlace}.
	 */
	@Override
	public void setOpen(final @Nullable Entity entity, final Level level, final BlockState state, final BlockPos pos, final boolean open) {
		if (entity != null || !state.is(this) || state.getValue(OPEN) == open) {
			return;
		}

		level.setBlock(pos, state.setValue(OPEN, open), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
	}

	/** The open or close sound once per door (its lower half) whenever its open state changes on the server, whoever set it. */
	@Override
	protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState old, final boolean movedByPiston) {
		super.onPlace(state, level, pos, old, movedByPiston);
		if (!level.isClientSide() && old.is(this) && state.getValue(HALF) == DoubleBlockHalf.LOWER && old.getValue(OPEN) != state.getValue(OPEN)) {
			boolean open = state.getValue(OPEN);
			level.playSound(null, pos, open ? this.type().doorOpen() : this.type().doorClose(), SoundSource.BLOCKS, 1.0F,
				level.getRandom().nextFloat() * 0.1F + 0.9F);
		}
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
