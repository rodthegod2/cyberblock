package dev.cyberblock.device.block;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Traffic lights (section 1.11, toggle bit 64 {@code traffic}): a three-colour lamp showing Night City's {@link #LIGHT}. Switching
 * it is Phase 6's Traffic Tool, so a right-click does nothing yet.
 */
public final class TrafficLampBlock extends FacingDeviceBlock {
	public enum Light implements StringRepresentable {
		OFF, RED, YELLOW, GREEN;

		@Override
		public String getSerializedName() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	public static final EnumProperty<Light> LIGHT = EnumProperty.create("light", Light.class);

	TrafficLampBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIGHT, Light.RED).setValue(QUEST_LOCKED, false));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(LIGHT);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		return InteractionResult.PASS;
	}

	static int light(final BlockState state) {
		return state.getValue(LIGHT) == Light.OFF ? 0 : 12;
	}
}
