package dev.cyberblock.cyber;

import dev.cyberblock.Cyberblock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Cyberblock's advancements (plan retest3 section 1.8, F11; the data is A's, in data/cyberblock/advancement/), awarded on
 * the server thread with ServerPlayer.getAdvancements().award(holder, criterion). A missing advancement (an older data
 * pack) is logged once and skipped.
 */
public final class CyberAdvancements {
	/** Advancements already reported missing, so the log says it once. */
	private static final Set<String> missing = new HashSet<>();

	private CyberAdvancements() {
	}

	/** Awards one criterion of cyberblock:<id> (a criterion already done changes nothing). */
	static void award(final ServerPlayer player, final String id, final String criterion) {
		AdvancementHolder holder = holder(player, id);
		if (holder != null && player.getAdvancements().award(holder, criterion)) {
			Cyberblock.LOG.info("advancement: {} {}", id, criterion);
		}
	}

	/** Every criterion of cyberblock:<id>: the root, whatever trigger A gave it (tick, or impossible awarded on join). */
	static void awardAll(final ServerPlayer player, final String id) {
		AdvancementHolder holder = holder(player, id);
		if (holder != null) {
			for (String criterion : List.copyOf(holder.value().criteria().keySet())) {
				player.getAdvancements().award(holder, criterion);
			}
		}
	}

	private static @Nullable AdvancementHolder holder(final ServerPlayer player, final String id) {
		AdvancementHolder holder = player.level().getServer().getAdvancements().get(Identifier.fromNamespaceAndPath(Cyberblock.ID, id));
		if (holder == null && missing.add(id)) {
			Cyberblock.LOG.warn("advancement: cyberblock:{} is missing", id);
		}

		return holder;
	}

	/** Joining: the root. */
	static void joined(final ServerPlayer player) {
		awardAll(player, "root");
	}

	/** After every cyberware change: any implant is first_implant; the deck with a Sandevistan or Berserk is dual_os. */
	static void cyberwareChanged(final ServerPlayer player) {
		Set<Cyberware> installed = CyberData.installed(player);
		if (!installed.isEmpty()) {
			award(player, "first_implant", "installed");
		}

		if (installed.contains(Cyberware.CYBERDECK) && (installed.contains(Cyberware.SANDEVISTAN) || installed.contains(Cyberware.BERSERK))) {
			award(player, "dual_os", "installed");
		}
	}

	/** An ok `hackresult`: first_quickhack, and the chip's own criterion of every_quickhack (its wire name). */
	static void hacked(final ServerPlayer player, final Hack hack) {
		award(player, "first_quickhack", "hacked");
		award(player, "every_quickhack", hack.wire());
	}

	/** A kill (Minecraft or Night City): sandevistan_kill while the Sandevistan runs. */
	static void killed(final ServerPlayer player) {
		if (CyberData.timer(player, Cyberware.SANDEVISTAN).active(player.level().getGameTime())) {
			award(player, "sandevistan_kill", "kill");
		}
	}
}
