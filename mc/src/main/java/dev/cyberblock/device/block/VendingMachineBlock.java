package dev.cyberblock.device.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Vending machines (section 1.11, toggle bit 32 {@code vending}): a Minecraft shop paid in Minecraft eddies (section 1.16). A
 * right-click asks the server for the machine's shop ({@code shop}); the server answers with {@code cyberblock:ui t:"shop"}
 * ({@link ShopWire}), the client opens the vending screen, a purchase comes back as {@code devact buy}, and the server takes the eddies
 * and gives the item. Phase 0 c: buying never reaches Night City. A {@link #BROKEN} machine says it is broken.
 */
public final class VendingMachineBlock extends FacingDeviceBlock {
	public static final BooleanProperty BROKEN = BooleanProperty.create("broken");

	VendingMachineBlock(final BlockBehaviour.Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(BROKEN, false).setValue(QUEST_LOCKED, false));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(BROKEN);
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hit) {
		if (state.getValue(BROKEN)) {
			return DeviceHooks.say(player, "cyberblock.device.broken", "This device is broken");
		}

		return DeviceHooks.act(state, level, pos, player, "shop");
	}

	static int light(final BlockState state) {
		return state.getValue(BROKEN) ? 0 : 10;
	}
}
