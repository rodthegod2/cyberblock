package dev.cyberblock.cyber;

import com.mojang.serialization.Codec;
import dev.cyberblock.Cyberblock;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

/**
 * The cyber state kept on the player (Fabric data attachments): saved with the world, kept through death, and synced to
 * that player's own client so the HUD and the skill tree can draw it. Values are immutable; replace them to change them.
 */
public final class CyberData {
	/** Perk id (plan section 1.5) to rank; a missing id is rank 0. */
	public static final AttachmentType<Map<String, Integer>> PERKS = AttachmentRegistry.<Map<String, Integer>>builder()
		.persistent(Codec.unboundedMap(Codec.STRING, Codec.INT))
		.copyOnDeath()
		.initializer(Map::of)
		.syncWith(ByteBufCodecs.<ByteBuf, String, Integer, Map<String, Integer>>map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT),
			AttachmentSyncPredicate.targetOnly())
		.buildAndRegister(id("perks"));
	/** Current RAM. */
	public static final AttachmentType<Integer> RAM = AttachmentRegistry.<Integer>builder()
		.persistent(Codec.INT)
		.copyOnDeath()
		.initializer(() -> 0)
		.syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.targetOnly())
		.buildAndRegister(id("ram"));
	/** The starter kit was handed out (automatically or by command it is given once per player and world). */
	public static final AttachmentType<Boolean> KIT_GIVEN = AttachmentRegistry.<Boolean>builder()
		.persistent(Codec.BOOL)
		.copyOnDeath()
		.initializer(() -> false)
		.buildAndRegister(id("kit_given"));

	private CyberData() {
	}

	private static Identifier id(final String path) {
		return Identifier.fromNamespaceAndPath(Cyberblock.ID, path);
	}

	/** Loads the class, which registers the attachment types (once, at startup). */
	static void register() {
	}

	/** The player's rank in a perk, 0 when not owned (either side: the client holds its own player's synced copy). */
	public static int rank(final Player player, final String perkId) {
		return player.getAttachedOrElse(PERKS, Map.of()).getOrDefault(perkId, 0);
	}

	public static boolean has(final Player player, final String perkId) {
		return rank(player, perkId) > 0;
	}

	/** Server side: sets one perk's rank (the change is synced to the client). */
	public static void setRank(final Player player, final String perkId, final int rank) {
		Map<String, Integer> perks = new HashMap<>(player.getAttachedOrElse(PERKS, Map.of()));
		perks.put(perkId, rank);
		player.setAttached(PERKS, Map.copyOf(perks));
	}

	public static int ram(final Player player) {
		return player.getAttachedOrElse(RAM, 0);
	}

	/** Server side. */
	public static void setRam(final Player player, final int ram) {
		if (ram != ram(player)) {
			player.setAttached(RAM, ram);
		}
	}
}
