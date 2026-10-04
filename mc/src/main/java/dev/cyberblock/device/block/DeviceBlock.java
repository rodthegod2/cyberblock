package dev.cyberblock.device.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * A Night City device as a Minecraft block (megaplan section 1.11, task 4-B1). The server places and drives these blocks (package
 * 4M through {@link DeviceHooks}); a right-click asks for a device action and the block changes only when Night City answers. Every
 * device block carries {@link #QUEST_LOCKED}: a quest- or cutscene-locked device shows greyed and refuses with
 * {@code cyberblock.device.quest_locked}.
 */
public abstract class DeviceBlock extends Block {
	/** Section 1.11: greyed, and every action refused, while a Night City quest or cutscene holds the device. */
	public static final BooleanProperty QUEST_LOCKED = BooleanProperty.create("quest_locked");

	protected DeviceBlock(final BlockBehaviour.Properties properties) {
		super(properties);
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(QUEST_LOCKED);
	}
}
