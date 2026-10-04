package dev.cyberblock.hack.redstone;

import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The Cyberdeck Link (megaplan 6-R1). Pick a device: scan it with G (a host's scan of a device locks it for 5 s; right-click the air
 * to keep it) or right-click its block; then right-click a Device Relay or a Breach Port to link that block to it. The picked device
 * rides on the item as its {@link Link} (custom data {@code cyberblock_link}) with a lore line naming it. The block clicks are handled
 * in RedstoneHacks' use-block callback (before the device layer's own right-click on a device block).
 */
public final class CyberdeckLinkItem extends Item {
	static final String TAG = "cyberblock_link";

	/** A device picked with the link: where it is, and how the status lines name it. */
	record Picked(Link link, String label) {
	}

	public CyberdeckLinkItem(final Item.Properties properties) {
		super(properties);
	}

	/** Right-click in the air: keep the device the player's scan locked (a host's G scan of a device). */
	@Override
	public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
		if (player instanceof ServerPlayer p) {
			RedstoneHacks.pickLocked(p, player.getItemInHand(hand));
		}

		return InteractionResult.SUCCESS;
	}

	static void store(final ItemStack stack, final Link link, final String label) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			CompoundTag l = new CompoundTag();
			l.store("owner", UUIDUtil.CODEC, link.owner());
			l.putDouble("x", link.x());
			l.putDouble("y", link.y());
			l.putDouble("z", link.z());
			l.putString("cls", link.cls());
			l.putString("label", label);
			tag.put(TAG, l);
		});
		stack.set(DataComponents.LORE, new ItemLore(List.of(Component.translatableWithFallback("item.cyberblock.cyberdeck_link.picked", "Picked: %s", label)
			.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.AQUA)))));
	}

	static @Nullable Picked read(final ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		CompoundTag l = data == null ? null : data.copyTag().getCompound(TAG).orElse(null);
		if (l == null) {
			return null;
		}

		UUID owner = l.read("owner", UUIDUtil.CODEC).orElse(null);
		try {
			return owner == null ? null : new Picked(new Link(owner, l.getDoubleOr("x", Double.NaN), l.getDoubleOr("y", Double.NaN), l.getDoubleOr("z", Double.NaN),
				l.getStringOr("cls", "")), l.getStringOr("label", ""));
		} catch (IllegalArgumentException bad) {
			return null;
		}
	}
}
