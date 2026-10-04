package dev.cyberblock.device.block;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Cameras (section 1.11, toggle bit 8 {@code camera}): the "eye" block. Its {@link #MODE} is the camera's state; the client draws a
 * particle vision cone out of its front while it watches (red when it raised the alarm, green when friendly). A right-click asks to
 * {@code disable} a watching camera or {@code enable} one that is off.
 */
public final class EyeCameraBlock extends FacingDeviceBlock implements EntityBlock {
	public enum Mode implements StringRepresentable {
		OFF, ON, ALARM, FRIENDLY;

		@Override
		public String getSerializedName() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	public static final EnumProperty<Mode> MODE = EnumProperty.create("mode", Mode.class);
	/** The client's cone (client/device installs it before any level loads); null on a dedicated server. */
	public static volatile @Nullable BlockEntityTicker<DeviceBlockEntity> clientTicker;

	EyeCameraBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH).setValue(MODE, Mode.ON)
			.setValue(QUEST_LOCKED, false));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(MODE);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		return DeviceHooks.act(state, level, pos, player, state.getValue(MODE) == Mode.OFF ? "enable" : "disable");
	}

	@Override
	public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
		return new DeviceBlockEntity(DeviceBlocks.EYE_CAMERA_ENTITY, pos, state);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(final Level level, final BlockState state, final BlockEntityType<T> type) {
		return level.isClientSide() && type == DeviceBlocks.EYE_CAMERA_ENTITY ? (BlockEntityTicker<T>) clientTicker : null;
	}
}
