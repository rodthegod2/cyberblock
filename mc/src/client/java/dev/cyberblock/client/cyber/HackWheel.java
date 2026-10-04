package dev.cyberblock.client.cyber;

import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.CyberActs;
import dev.cyberblock.cyber.Hack;
import dev.cyberblock.cyber.Hacking;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.QuickhackItem;
import dev.cyberblock.cyber.Ram;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Quickhack chips without the hotbar: while the hack key is held, the mouse wheel picks one of the chips anywhere in the
 * inventory and right-click fires it at the locked target, on the server (`act t:"hack"`, Hacking.useChip, the same rules as a
 * chip used from the hand). A chip in the main hand always wins over the pick (plan retest3 section 1.6: the old order fired
 * the remembered pick, usually the first chip, whatever he held). Without the hack key both do what they always do. Client
 * thread.
 */
public final class HackWheel {
	/** The chip picked last; kept between holds while he still has one. */
	private static @Nullable Hack selected;
	/** The last use press was the wheel's, so its release is too (and keyUse never sees either). */
	private static boolean usePressTaken;
	/** The hotbar slot selected last tick (-1: no player). */
	private static int lastSlot = -1;

	private HackWheel() {
	}

	/** The kinds of chip in the inventory, in Hack order. */
	private static List<Hack> chips(final LocalPlayer player) {
		List<Hack> chips = new ArrayList<>();
		for (Hack hack : Hack.values()) {
			if (!Hacking.findChip(player, hack).isEmpty()) {
				chips.add(hack);
			}
		}

		return chips;
	}

	/** The chip an item stack is, else null. */
	private static @Nullable Hack chipOf(final ItemStack stack) {
		return stack.getItem() instanceof QuickhackItem item ? item.hack() : null;
	}

	/**
	 * Plan retest3 section 1.6, the wheel rule: the chip in the main hand wins, then the wheel's pick, then the first chip he
	 * has; null without chips. Only chips he still has count.
	 */
	public static @Nullable Hack pick(final @Nullable Hack inHand, final @Nullable Hack picked, final List<Hack> chips) {
		if (inHand != null && chips.contains(inHand)) {
			return inHand;
		}

		if (picked != null && chips.contains(picked)) {
			return picked;
		}

		return chips.isEmpty() ? null : chips.getFirst();
	}

	/** The wheel's pick after he selects another hotbar slot: a slot holding a chip clears it (that chip is what he wants). */
	public static @Nullable Hack afterSlotChange(final @Nullable Hack picked, final @Nullable Hack inNewSlot) {
		return inNewSlot != null ? null : picked;
	}

	private static @Nullable Hack current(final LocalPlayer player, final List<Hack> chips) {
		return pick(chipOf(player.getMainHandItem()), selected, chips);
	}

	/** Every client tick: a hotbar slot change to a chip clears the pick (afterSlotChange). */
	static void tick(final Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			lastSlot = -1;
			return;
		}

		int slot = player.getInventory().getSelectedSlot();
		if (lastSlot >= 0 && slot != lastSlot) {
			selected = afterSlotChange(selected, chipOf(player.getMainHandItem()));
		}

		lastSlot = slot;
	}

	/**
	 * Wheel notches with no screen open (+1 = up): while the hack key is held and he has chips, they pick the previous or next
	 * chip, as the hotbar would scroll, and true is returned; otherwise they are the hotbar's. With a chip in his hand the
	 * hand wins anyway, so the wheel scrolls the hotbar (the way to change the chip he holds).
	 */
	public static boolean scroll(final Minecraft minecraft, final int notches) {
		LocalPlayer player = minecraft.player;
		if (player == null || !CyberKeys.hack.isDown() || chipOf(player.getMainHandItem()) != null) {
			return false;
		}

		List<Hack> chips = chips(player);
		Hack now = current(player, chips);
		if (now == null) {
			return false;
		}

		selected = step(chips, now, notches);
		return true;
	}

	/** The chip `notches` wheel notches away from `now` (one of `chips`): up (+) goes back, down (-) forward, wrapping round. */
	public static Hack step(final List<Hack> chips, final Hack now, final int notches) {
		return chips.get(Math.floorMod(chips.indexOf(now) - notches, chips.size()));
	}

	/**
	 * The use key (right mouse) with no screen open. A press while the hack key is held and he has chips fires the current one
	 * and returns true, and so does its release: neither may reach keyUse. Anything else returns false.
	 */
	public static boolean use(final Minecraft minecraft, final boolean down) {
		if (!down) {
			boolean taken = usePressTaken;
			usePressTaken = false;
			return taken;
		}

		usePressTaken = fire(minecraft);
		return usePressTaken;
	}

	private static boolean fire(final Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (player == null || player.isSpectator() || !CyberKeys.hack.isDown()) {
			return false;
		}

		Hack hack = current(player, chips(player));
		if (hack == null) {
			return false;
		}

		selected = hack;
		// the server holds the lock, RAM and cooldowns (megaplan task 1-C1: `act t:"hack"`, a guest's too)
		return CyberLink.act(CyberActs.HACK, CyberActs.hack(hack));
	}

	/** The scan tooltip's lines while the hack key is held: the chip right-click fires and what it costs now; none without chips. */
	static List<Component> lines(final LocalPlayer player) {
		Hack hack = current(player, chips(player));
		if (hack == null) {
			return List.of();
		}

		ItemStack stack = Hacking.findChip(player, hack);
		Component name = stack.getHoverName();
		Component chip = player.getCooldowns().isOnCooldown(stack)
			? Component.translatable("cyberblock.wheel.recharging", name).withStyle(ChatFormatting.GRAY)
			: Component.translatable("cyberblock.wheel.chip", name, Ram.cost(hack, CyberData.has(player, Perk.CHEAP_HACKS))).withStyle(ChatFormatting.AQUA);
		return List.of(chip, Component.translatable("cyberblock.wheel.hint").withStyle(ChatFormatting.DARK_GRAY));
	}
}
