package dev.cyberblock.cyber;

import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Buying perks with XP levels (task C6): tier order within a branch, the level cost, then the rank and `perks`. */
public final class PerkShop {
	public enum Outcome {
		OK,
		OWNED,
		/** The tier below is not owned yet. */
		LOCKED,
		TOO_POOR
	}

	private PerkShop() {
	}

	/**
	 * Pure: may this perk be bought with these ranks (perk id to rank) and this many levels? Free perks (plan retest2 R3) skip
	 * only the level cost: an owned perk stays owned and the tier order still holds.
	 */
	public static Outcome check(final Perk perk, final Map<String, Integer> ranks, final int levels, final boolean free) {
		if (ranks.getOrDefault(perk.id(), 0) > 0) {
			return Outcome.OWNED;
		}

		Perk before = perk.prerequisite();
		if (before != null && ranks.getOrDefault(before.id(), 0) <= 0) {
			return Outcome.LOCKED;
		}

		return free || levels >= perk.cost() ? Outcome.OK : Outcome.TOO_POOR;
	}

	/** Server thread (the skill tree screen hands its click over): buy it if the rules allow, and say how it went. */
	public static void buy(final ServerPlayer player, final Perk perk) {
		Map<String, Integer> ranks = player.getAttachedOrElse(CyberData.PERKS, Map.of());
		boolean free = CyberData.freePerks(player);
		Outcome outcome = check(perk, ranks, player.experienceLevel, free);
		Component name = Component.translatable("perk.cyberblock." + perk.id());
		switch (outcome) {
			case OK -> {
				if (!free) {
					player.giveExperienceLevels(-perk.cost());
				}

				CyberData.setRank(player, perk.id(), 1);
				PerkEffects.apply(player);
				player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7F, 1.4F);
				player.sendOverlayMessage(free ? Component.translatable("cyberblock.skills.bought_free", name) : Component.translatable("cyberblock.skills.bought", name));
				sendPerks(player);
			}
			case OWNED -> player.sendOverlayMessage(Component.translatable("cyberblock.skills.already"));
			case LOCKED -> player.sendOverlayMessage(Component.translatable("cyberblock.skills.locked",
				Component.translatable("perk.cyberblock." + perk.prerequisite().id())));
			case TOO_POOR -> player.sendOverlayMessage(Component.translatable("cyberblock.skills.too_poor", perk.cost()));
		}
	}

	/**
	 * {"t":"perks"} with all 20 ranks, DOWN to the player's own session only (megaplan task 1-C1: a guest keeps his perks
	 * Minecraft-side): after each host hello and every purchase.
	 */
	public static void sendPerks(final ServerPlayer player) {
		CyberNet.toOwn(player, CyberWire.perks(player.getAttachedOrElse(CyberData.PERKS, Map.of())));
	}
}
