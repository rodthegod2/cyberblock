package dev.cyberblock.cyber;

import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
 * to the client (the attachment), told to Cyberpunk (`cyberware`) and mirrored in Bridge.installedCyberware.
 */
public final class Ripperdoc {
	private Ripperdoc() {
	}

	/** The implant's item name, for messages. */
	static Component name(final Cyberware cyberware) {
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

		if (!free) {
			stack.shrink(1);
		}

		CyberData.setSlots(player, result.slots());
		Cyberware replaced = result.replaced();
		if (replaced != null && !free) {
			player.getInventory().placeItemBackInInventory(new ItemStack(replaced.item()), Prediction.SERVER_ONLY);
		}

		Cyberblock.LOG.info("cyberware: installed {} in {}", cyberware.id(), result.slotKey());
		effects(player, 1.0F);
		player.sendOverlayMessage(replaced == null ? Component.translatable("cyberblock.cyberware.installed", name(cyberware))
			: Component.translatable("cyberblock.cyberware.replaced", name(cyberware), name(replaced)));
		changed(player);
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

	/** A right-click on an installed ACTIVE implant in the screen: the activate key fires it from now on. */
	public static void select(final ServerPlayer player, final Cyberware cyberware) {
		if (cyberware.kind() == Cyberware.Kind.ACTIVE && CyberData.has(player, cyberware)) {
			player.setAttached(CyberData.CYBER_SELECTED, cyberware.id());
			player.sendOverlayMessage(Component.translatable("cyberblock.cyberware.selected", name(cyberware)));
		}
	}

	/** The activate key held: the next ACTIVE implant is selected (CyberwareRules.next). */
	public static void cycle(final ServerPlayer player) {
		Cyberware next = CyberwareRules.next(CyberData.installed(player), CyberData.selected(player));
		if (next == null) {
			player.sendOverlayMessage(Component.translatable("cyberblock.cyberware.none"));
			return;
		}

		player.setAttached(CyberData.CYBER_SELECTED, next.id());
		player.sendOverlayMessage(Component.translatable("cyberblock.cyberware.selected", name(next)));
	}

	/**
	 * {"t":"cyberware"} with the installed implants, and Bridge.installedCyberware: after each host hello, on join and respawn,
	 * and after every install or remove.
	 */
	public static void sendCyberware(final ServerPlayer player) {
		Set<String> ids = CyberData.installed(player).stream().map(Cyberware::id).collect(Collectors.toUnmodifiableSet());
		Bridge.installedCyberware = ids;
		Cyberblock.events.accept(CyberWire.cyberware(ids));
	}

	/** The player left the world: Cyberpunk takes every implant off V, and nobody counts as having any. */
	public static void disconnected() {
		Bridge.installedCyberware = Set.of();
		Cyberblock.events.accept(CyberWire.cyberware(Set.of()));
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

	/** After an install or remove: the effects match the new set, and Cyberpunk hears of it. */
	private static void changed(final ServerPlayer player) {
		sendCyberware(player);
	}
}
