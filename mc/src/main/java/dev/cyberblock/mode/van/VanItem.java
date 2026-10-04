package dev.cyberblock.mode.van;

import dev.cyberblock.Cyberblock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

/** The Van as an item (megaplan task 8-V1): use it on a block to park a Van there, facing the way you look. */
final class VanItem extends Item {
	VanItem(final Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(final UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level)) {
			return InteractionResult.SUCCESS;
		}

		Player player = context.getPlayer();
		BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
		VanEntity van = VanModule.VAN.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (van == null) {
			return InteractionResult.FAIL;
		}

		Vec3 at = Vec3.atBottomCenterOf(pos);
		van.snapTo(at.x, at.y, at.z, player == null ? 0.0F : player.getYRot(), 0.0F);
		if (!level.noCollision(van, van.getBoundingBox())) {
			if (player != null) {
				player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.van.no_room", "No room to park the Van here").withStyle(ChatFormatting.RED));
			}

			return InteractionResult.FAIL;
		}

		level.addFreshEntity(van);
		context.getItemInHand().consume(1, player);
		Cyberblock.LOG.info("van: {} parked a Van at {} {} {}", player == null ? "a dispenser" : player.getPlainTextName(), pos.getX(), pos.getY(), pos.getZ());
		return InteractionResult.SUCCESS_SERVER;
	}
}
