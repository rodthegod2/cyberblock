package dev.cyberblock.cyber;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cyberblock.Cyberblock;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

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
	/** `/cyberblock perks free on`: the skill tree costs no levels (plan retest2 R3). Synced, so the tree can show it. */
	public static final AttachmentType<Boolean> FREE_PERKS = AttachmentRegistry.<Boolean>builder()
		.persistent(Codec.BOOL)
		.copyOnDeath()
		.initializer(() -> false)
		.syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.targetOnly())
		.buildAndRegister(id("free_perks"));
	/** Installed cyberware (plan retest2 section 1.7): slot key "<slot>/<n>" to implant id. */
	public static final AttachmentType<Map<String, String>> CYBERWARE = AttachmentRegistry.<Map<String, String>>builder()
		.persistent(Codec.unboundedMap(Codec.STRING, Codec.STRING))
		.copyOnDeath()
		.initializer(Map::of)
		.syncWith(ByteBufCodecs.<ByteBuf, String, String, Map<String, String>>map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.STRING_UTF8),
			AttachmentSyncPredicate.targetOnly())
		.buildAndRegister(id("cyberware"));
	private static final Codec<CyberwareRules.Timer> TIMER_CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.LONG.fieldOf("start").forGetter(CyberwareRules.Timer::start),
		Codec.LONG.fieldOf("active_until").forGetter(CyberwareRules.Timer::activeUntil),
		Codec.LONG.fieldOf("ready_at").forGetter(CyberwareRules.Timer::readyAt)
	).apply(i, CyberwareRules.Timer::new));
	private static final StreamCodec<ByteBuf, CyberwareRules.Timer> TIMER_STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_LONG, CyberwareRules.Timer::start,
		ByteBufCodecs.VAR_LONG, CyberwareRules.Timer::activeUntil,
		ByteBufCodecs.VAR_LONG, CyberwareRules.Timer::readyAt,
		CyberwareRules.Timer::new);
	/** Implant id to its last firing (level game time): saved, so a Second Heart's 5 minutes survive a reload; synced for the HUD. */
	public static final AttachmentType<Map<String, CyberwareRules.Timer>> CYBER_READY = AttachmentRegistry.<Map<String, CyberwareRules.Timer>>builder()
		.persistent(Codec.unboundedMap(Codec.STRING, TIMER_CODEC))
		.copyOnDeath()
		.initializer(Map::of)
		.syncWith(ByteBufCodecs.<ByteBuf, String, CyberwareRules.Timer, Map<String, CyberwareRules.Timer>>map(HashMap::new, ByteBufCodecs.STRING_UTF8, TIMER_STREAM_CODEC),
			AttachmentSyncPredicate.targetOnly())
		.buildAndRegister(id("cyber_ready"));
	/** The implant the activate key (Y) fires, by id ("" or one no longer installed: the first ACTIVE one). */
	public static final AttachmentType<String> CYBER_SELECTED = AttachmentRegistry.<String>builder()
		.persistent(Codec.STRING)
		.copyOnDeath()
		.initializer(() -> "")
		.syncWith(ByteBufCodecs.STRING_UTF8, AttachmentSyncPredicate.targetOnly())
		.buildAndRegister(id("cyber_selected"));
	/** The once-per-player hand-out of implant items to owners of the perks that now need one (Ripperdoc.migrate) is done. */
	public static final AttachmentType<Boolean> CYBER_MIGRATED = AttachmentRegistry.<Boolean>builder()
		.persistent(Codec.BOOL)
		.copyOnDeath()
		.initializer(() -> false)
		.buildAndRegister(id("cyber_migrated"));

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

	public static boolean has(final Player player, final Perk perk) {
		return rank(player, perk.id()) > 0;
	}

	/** Server side: sets one perk's rank (the change is synced to the client). */
	public static void setRank(final Player player, final String perkId, final int rank) {
		Map<String, Integer> perks = new HashMap<>(player.getAttachedOrElse(PERKS, Map.of()));
		perks.put(perkId, rank);
		player.setAttached(PERKS, Map.copyOf(perks));
	}

	/** Perks cost no levels: always in creative, otherwise while `/cyberblock perks free on` holds. Either side. */
	public static boolean freePerks(final Player player) {
		return player.isCreative() || player.getAttachedOrElse(FREE_PERKS, false);
	}

	public static int ram(final Player player) {
		return player.getAttachedOrElse(RAM, 0);
	}

	/** An installed cyberdeck, or one carried while the Operating System slot is empty (plan retest2 section 1.7). Either side. */
	public static boolean hasDeck(final Player player) {
		boolean osSlotEmpty = Cyberware.byId(slots(player).get(CyberSlot.OPERATING_SYSTEM.key(0))) == null;
		return Ram.hasDeck(has(player, Cyberware.CYBERDECK), osSlotEmpty, osSlotEmpty && player.getInventory().contains(stack -> stack.is(CyberItems.CYBERDECK)));
	}

	/** Either side (the inventory, the perks and the cyberware are synced to the client). */
	public static int maxRam(final Player player) {
		return Ram.max(hasDeck(player), has(player, Perk.EXTRA_RAM), has(player, Cyberware.RAM_UPGRADE));
	}

	/** Slot key to implant id (either side: the client holds its own player's synced copy). */
	public static Map<String, String> slots(final Player player) {
		return player.getAttachedOrElse(CYBERWARE, Map.of());
	}

	/** Server side: the new slot map (synced to the client). */
	static void setSlots(final Player player, final Map<String, String> slots) {
		player.setAttached(CYBERWARE, Map.copyOf(slots));
	}

	public static Set<Cyberware> installed(final Player player) {
		return CyberwareRules.installed(slots(player));
	}

	public static boolean has(final Player player, final Cyberware cyberware) {
		return slots(player).containsValue(cyberware.id());
	}

	/** The implant's last firing, Timer.NONE when it never fired. */
	public static CyberwareRules.Timer timer(final Player player, final Cyberware cyberware) {
		return player.getAttachedOrElse(CYBER_READY, Map.of()).getOrDefault(cyberware.id(), CyberwareRules.Timer.NONE);
	}

	/** Server side: the implant fired (or its effect was cut short). */
	static void setTimer(final Player player, final Cyberware cyberware, final CyberwareRules.Timer timer) {
		Map<String, CyberwareRules.Timer> timers = new HashMap<>(player.getAttachedOrElse(CYBER_READY, Map.of()));
		timers.put(cyberware.id(), timer);
		player.setAttached(CYBER_READY, Map.copyOf(timers));
	}

	/** The implant may fire again (level game time). */
	public static boolean ready(final Player player, final Cyberware cyberware) {
		return timer(player, cyberware).ready(player.level().getGameTime());
	}

	/** What the activate key fires now (CyberwareRules.defaultSelected), or null without an ACTIVE implant. Either side. */
	public static @Nullable Cyberware selected(final Player player) {
		return CyberwareRules.defaultSelected(installed(player), Cyberware.byId(player.getAttachedOrElse(CYBER_SELECTED, "")));
	}

	/** Server side. */
	public static void setRam(final Player player, final int ram) {
		if (ram != ram(player)) {
			player.setAttached(RAM, ram);
		}
	}
}
