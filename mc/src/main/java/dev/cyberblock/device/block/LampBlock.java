package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Lights and neon (section 1.11, toggle bit 4 {@code light}): one lamp block in the 16 dye colours, lit (light 15) or unlit. A
 * right-click asks Night City to switch it ({@code on}/{@code off}).
 */
public final class LampBlock extends DeviceBlock {
	public static final EnumProperty<DyeColor> COLOR = EnumProperty.create("color", DyeColor.class);
	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	LampBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(COLOR, DyeColor.WHITE).setValue(LIT, true).setValue(QUEST_LOCKED, false));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(COLOR, LIT);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		return DeviceHooks.act(state, level, pos, player, state.getValue(LIT) ? "off" : "on");
	}

	static int light(final BlockState state) {
		return state.getValue(LIT) ? 15 : 0;
	}

	/** The lamp in a colour, lit or not. */
	public static BlockState state(final DyeColor colour, final boolean lit) {
		return DeviceBlocks.LAMP.defaultBlockState().setValue(COLOR, colour).setValue(LIT, lit);
	}

	/** The dye whose colour is nearest to {@code rgb} (a {@code device_map} {@code #RRGGBB} colour), by squared RGB distance. Pure. */
	public static DyeColor nearest(final int rgb) {
		DyeColor best = DyeColor.WHITE;
		int bestDistance = Integer.MAX_VALUE;
		for (DyeColor dye : DyeColor.values()) {
			int c = dye.getTextureDiffuseColor();
			int dr = ARGB.red(c) - ARGB.red(rgb);
			int dg = ARGB.green(c) - ARGB.green(rgb);
			int db = ARGB.blue(c) - ARGB.blue(rgb);
			int d = dr * dr + dg * dg + db * db;
			if (d < bestDistance) {
				bestDistance = d;
				best = dye;
			}
		}

		return best;
	}
}
