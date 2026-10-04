package dev.cyberblock.device.block;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
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
 * Turrets (section 1.11, toggle bit 256 {@code turret}; Phase 0 d: Minecraft-side only, never spawned in Night City). The block is
 * the dispenser-style body (4A's art, its front LED shows the mode); the client's renderer draws a gun head on top, turned toward its
 * target and coloured by {@link #MODE} (off grey and drooping, active red and tracking the nearest player, friendly green and
 * sweeping). A right-click asks to {@code disable}
 * an active or friendly turret or {@code enable} one that is off; sneaking asks for {@code friendly}.
 */
public final class TurretBlock extends FacingDeviceBlock implements EntityBlock {
	public enum Mode implements StringRepresentable {
		OFF, ACTIVE, FRIENDLY;

		@Override
		public String getSerializedName() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	public static final EnumProperty<Mode> MODE = EnumProperty.create("mode", Mode.class);
	/** How far an active turret looks for a target (blocks). */
	public static final double RANGE = 16.0;
	/** Degrees the head turns per client tick at most. */
	public static final float TURN_SPEED = 9.0F;
	/** The client's aim (client/device installs it before any level loads); null on a dedicated server. */
	public static volatile @Nullable BlockEntityTicker<DeviceBlockEntity> clientTicker;

	TurretBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(MODE, Mode.ACTIVE).setValue(QUEST_LOCKED, false));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(MODE);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		String act = player.isSecondaryUseActive() ? "friendly" : state.getValue(MODE) == Mode.OFF ? "enable" : "disable";
		return DeviceHooks.act(state, level, pos, player, act);
	}

	@Override
	public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
		DeviceBlockEntity entity = new DeviceBlockEntity(DeviceBlocks.TURRET_ENTITY, pos, state);
		entity.yaw = entity.yawO = state.getValue(FACING).toYRot();
		return entity;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(final Level level, final BlockState state, final BlockEntityType<T> type) {
		return level.isClientSide() && type == DeviceBlocks.TURRET_ENTITY ? (BlockEntityTicker<T>) clientTicker : null;
	}

	/** The yaw (Entity convention: 0 = south, 90 = west) that looks from the turret along (dx, dz). Pure. */
	public static float yawToward(final double dx, final double dz) {
		return Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F);
	}

	/** The pitch (positive = down, as Entity pitch) that looks along (horizontal distance, dy). Pure. */
	public static float pitchToward(final double horizontal, final double dy) {
		return (float) -(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG);
	}

	/** {@code current} turned toward {@code target} by at most {@code step} degrees, the short way round. Pure. */
	public static float approach(final float current, final float target, final float step) {
		float delta = Mth.wrapDegrees(target - current);
		return current + Mth.clamp(delta, -step, step);
	}

	/** An idle (friendly) turret's slow sweep: 40 degrees each side of its front, one full sweep every 8 s. Pure. */
	public static float sweep(final Direction facing, final int ticks) {
		return facing.toYRot() + 40.0F * Mth.sin(ticks * Mth.TWO_PI / 160.0F);
	}
}
