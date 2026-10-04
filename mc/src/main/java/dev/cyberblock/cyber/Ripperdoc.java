package dev.cyberblock.cyber;

import dev.cyberblock.Cyberblock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;

/**
 * Installing and removing cyberware (plan retest2 section 1.7, task C4), on the server thread. In survival an install takes
 * the implant item from the inventory and gives back the one it replaces; in creative both are free. Every change is synced
 * to the client (the attachment) and told to the player's own Cyberpunk, if he is a host (`cyberware`, megaplan task 1-C1;
 * CyberData.installedIds replaces the JVM-wide Bridge.installedCyberware).
 */
public final class Ripperdoc {
	private Ripperdoc() {
	}

	/** The implant's item name, for messages. */
	public static Component name(final Cyberware cyberware) {
		return new ItemStack(cyberware.item()).getHoverName();
	}

	/** The first stack of the implant's item in the inventory, else EMPTY. */
	private static ItemStack find(final ServerPlayer player, final Cyberware cyberware) {
		for (ItemStack stack : player.getInventory()) {
			if (stack.is(cyberware.item())) {
				return stack;
			}
		}

		return ItemStack.EMPTY;
	}

	/** The item's use or a click in the cyberware screen: installs the implant into its slot (CyberwareRules.install). */
	public static void install(final ServerPlayer player, final Cyberware cyberware) {
		boolean free = player.isCreative();
		ItemStack stack = free ? ItemStack.EMPTY : find(player, cyberware);
		if (!free && stack.isEmpty()) {
			player.sendOverlayMessage(Component.translatable("cyberblock.cyberware.not_owned"));
			return;
		}

		CyberwareRules.Installed result = CyberwareRules.install(CyberData.slots(player), cyberware);
		if (result.outcome() == CyberwareRules.Install.ALREADY) {
			player.sendOverlayMessage(Component.translatable("cyberblock.cyberware.installed", name(cyberware)));
			return;
		}

		CyberData.setSlots(player, result.slots());
		Cyberware replaced = result.replaced();
		if (!free) {
			// the replaced implant goes back first: once the used stack is empty its slot counts as free, and when the item was
			// used from the hand, vanilla then empties that hand slot (ServerPlayerGameMode.useItem), with whatever went into it
			if (replaced != null) {
				player.getInventory().placeItemBackInInventory(new ItemStack(replaced.item()), Prediction.SERVER_ONLY);
			}

			stack.shrink(1);
		}

		Cyberblock.LOG.info("cyberware: installed {} in {}", cyberware.id(), result.slotKey());
		effects(player, 1.0F);
		player.sendOverlayMessage(replaced == null ? Component.translatable("cyberblock.cyberware.installed", name(cyberware))
			: Component.translatable("cyberblock.cyberware.replaced", name(cyberware), name(replaced)));
		changed(player);
	}

	/**
	 * The starter kit's quiet install (no item used, no message): true when the implant is in now (or already was), false when
	 * its slot is full of other implants and the kit hands out the item instead. Call changed() once the kit is given.
	 */
	static boolean installFree(final ServerPlayer player, final Cyberware cyberware) {
		CyberwareRules.Installed result = CyberwareRules.install(CyberData.slots(player), cyberware);
		if (result.outcome() == CyberwareRules.Install.REPLACED) {
			return false;
		}

		if (result.outcome() == CyberwareRules.Install.INSTALLED) {
			CyberData.setSlots(player, result.slots());
			Cyberblock.LOG.info("cyberware: installed {} in {}", cyberware.id(), result.slotKey());
		}

		return true;
	}

	/** On join, before the first `cyberware` send: an older save's Operating System implants move to their fixed positions. */
	public static void normalize(final ServerPlayer player) {
		Map<String, String> slots = CyberData.slots(player);
		Map<String, String> fixed = CyberwareRules.normalize(slots);
		if (fixed != slots) {
			CyberData.setSlots(player, fixed);
			Cyberblock.LOG.info("cyberware: normalized slots {} -> {}", slots, fixed);
		}
	}

	/** A click on an installed implant in the cyberware screen: out it goes (back into the inventory in survival). */
	public static void remove(final ServerPlayer player, final String slotKey) {
		Map<String, String> slots = new HashMap<>(CyberData.slots(player));
		Cyberware cyberware = Cyberware.byId(slots.remove(slotKey));
		if (cyberware == null) {
			return;
		}

		CyberData.setSlots(player, slots);
		if (!player.isCreative()) {
			player.getInventory().placeItemBackInInventory(new ItemStack(cyberware.item()), Prediction.SERVER_ONLY);
		}

		Cyberblock.LOG.info("cyberware: removed {} from {}", cyberware.id(), slotKey);
		effects(player, 0.7F);
		player.sendOverlayMessage(Component.translatable("cyberblock.cyberware.removed", name(cyberware)));
		changed(player);
	}

	/**
	 * {"t":"cyberware"} with the installed implants, DOWN to the player's own session only (a guest's implants stay
	 * Minecraft-side): after each host hello, on respawn, and after every install or remove. When the world closes the client
	 * takes them off V itself (CyberLink).
	 */
	public static void sendCyberware(final ServerPlayer player) {
		CyberNet.toOwn(player, CyberWire.cyberware(CyberData.installedIds(player)));
	}

	/**
	 * Once per player (on join): owners of the perks that now upgrade an implant get that implant's item, never installed for
	 * them (CyberwareRules.migration), and are told to install it.
	 */
	public static void migrate(final ServerPlayer player) {
		if (player.getAttachedOrElse(CyberData.CYBER_MIGRATED, false)) {
			return;
		}

		player.setAttached(CyberData.CYBER_MIGRATED, true);
		List<Cyberware> items = CyberwareRules.migration(perk -> CyberData.has(player, perk));
		if (items.isEmpty()) {
			return;
		}

		for (Cyberware cyberware : items) {
			player.getInventory().placeItemBackInInventory(new ItemStack(cyberware.item()), Prediction.SERVER_ONLY);
		}

		Cyberblock.LOG.info("cyberware: migrated perks, gave {}", items);
		player.sendSystemMessage(Component.translatable("cyberblock.cyberware.migrated", ComponentUtils.formatList(items, Ripperdoc::name)));
	}

	/** The ripperdoc's sound and sparks at the player. */
	private static void effects(final ServerPlayer player, final float pitch) {
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARMOR_EQUIP_NETHERITE.value(), SoundSource.PLAYERS, 1.0F, pitch);
		player.level().sendParticles(ParticleTypes.ELECTRIC_SPARK, player.getX(), player.getY() + 1.0, player.getZ(), 10, 0.3, 0.5, 0.3, 0.05);
	}

	/** After an install or remove: the effects match the new set (perk upgrades too), and Cyberpunk hears of it. */
	static void changed(final ServerPlayer player) {
		CyberwareEffects.apply(player);
		PerkEffects.apply(player);
		sendCyberware(player);
		CyberAdvancements.cyberwareChanged(player);
	}
}
