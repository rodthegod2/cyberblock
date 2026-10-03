package dev.cyberblock.cyber;

import dev.cyberblock.Cyberblock;
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

	/** Pure: may this perk be bought with these ranks (perk id to rank) and this many levels? */
	public static Outcome check(final Perk perk, final Map<String, Integer> ranks, final int levels) {
		if (ranks.getOrDefault(perk.id(), 0) > 0) {
			return Outcome.OWNED;
		}

		Perk before = perk.prerequisite();
		if (before != null && ranks.getOrDefault(before.id(), 0) <= 0) {
			return Outcome.LOCKED;
		}

		return levels >= perk.cost() ? Outcome.OK : Outcome.TOO_POOR;
	}

	/** Server thread (the skill tree screen hands its click over): buy it if the rules allow, and say how it went. */
	public static void buy(final ServerPlayer player, final Perk perk) {
		Map<String, Integer> ranks = player.getAttachedOrElse(CyberData.PERKS, Map.of());
		Outcome outcome = check(perk, ranks, player.experienceLevel);
		Component name = Component.translatable("perk.cyberblock." + perk.id());
		switch (outcome) {
			case OK -> {
				player.giveExperienceLevels(-perk.cost());
				CyberData.setRank(player, perk.id(), 1);
				player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7F, 1.4F);
				player.sendOverlayMessage(Component.translatable("cyberblock.skills.bought", name));
				sendPerks(player);
			}
			case OWNED -> player.sendOverlayMessage(Component.translatable("cyberblock.skills.already"));
			case LOCKED -> player.sendOverlayMessage(Component.translatable("cyberblock.skills.locked",
				Component.translatable("perk.cyberblock." + perk.prerequisite().id())));
			case TOO_POOR -> player.sendOverlayMessage(Component.translatable("cyberblock.skills.too_poor", perk.cost()));
		}
	}

	/** {"t":"perks"} with all 20 ranks: on every host hello and after every purchase. */
	public static void sendPerks(final ServerPlayer player) {
		Cyberblock.events.accept(CyberWire.perks(player.getAttachedOrElse(CyberData.PERKS, Map.of())));
	}
}
