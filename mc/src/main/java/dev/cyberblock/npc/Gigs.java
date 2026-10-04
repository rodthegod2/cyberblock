package dev.cyberblock.npc;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntities;
import dev.cyberblock.ProxyEntity;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Fixer gigs (megaplan task 7-N1): the fixer's screen (take one of the role's {@code contract}s, turn a delivery in, drop the gig) and
 * the objective tracking. One gig at a time per player (GigData, saved). {@code deliver}: turned in at any fixer. {@code defeat}: a
 * Night City person the player struck in melee dies within 60 s (its figure lies down: the host's {@code npcs} dead flag or {@code
 * killed}) and its faction matches. {@code reach}: checked once a second against the contract's {@code pos} or its landmark.
 * {@code breach}: {@link #breached} (6B's Breach Protocol calls it on a solved breach). On completion the reward is paid at once in
 * Minecraft eddies (section 1.16) and the gigs-done count rises. Server thread.
 */
public final class Gigs implements Visit.Service {
	/** A melee blow on a Night City person by a player with a defeat gig. */
	private record Strike(UUID player, int tick) {
	}

	static final int STRIKE_TICKS = 60 * 20, MAX_OFFERS = 12;
	private static final Map<Entity, Strike> strikes = new WeakHashMap<>();
	private static boolean breachHookLogged;

	@Override
	public List<String> tabs(final Visit v) {
		return List.of("gigs");
	}

	/** The contracts this fixer offers, in content id order. */
	static List<Map.Entry<Identifier, Contract>> offers(final NpcRole role) {
		List<Map.Entry<Identifier, Contract>> out = new ArrayList<>();
		for (Map.Entry<Identifier, Contract> e : NpcContent.contracts().entrySet()) {
			if ((role.contracts().isEmpty() || role.contracts().contains(e.getKey())) && out.size() < MAX_OFFERS) {
				out.add(e);
			}
		}

		return out;
	}

	static GigData.Gig gig(final ServerPlayer player) {
		return player.getAttachedOrElse(GigData.GIG, GigData.Gig.NONE);
	}

	static @Nullable Contract contract(final GigData.Gig gig) {
		Identifier id = gig.none() ? null : Identifier.tryParse(gig.contract());
		return id == null ? null : NpcContent.contracts().get(id);
	}

	private static String icon(final Contract c) {
		return switch (c.type()) {
			case DELIVER -> c.target();
			case DEFEAT -> "minecraft:iron_sword";
			case REACH -> "minecraft:compass";
			case BREACH -> "minecraft:redstone";
		};
	}

	@Override
	public List<NpcWire.Row> rows(final Visit v) {
		List<NpcWire.Row> rows = new ArrayList<>();
		GigData.Gig gig = gig(v.player);
		Contract active = contract(gig);
		if (active != null) {
			boolean deliver = active.type() == Contract.Type.DELIVER;
			Item item = deliver ? Shopkeeper.item(active.target()) : null;
			int have = item == null ? 0 : Eddies.count(v.player.getInventory(), item);
			String line = GigRules.progressLine(active, deliver ? Math.min(have, active.count()) : gig.progress());
			rows.add(new NpcWire.Row("active", icon(active), deliver ? Math.min(active.count(), 64) : 0, active.title(), line, active.reward(),
				deliver ? "turn_in" : "abandon", !deliver || have >= active.count()));
			if (deliver) {
				rows.add(new NpcWire.Row("abandon", "minecraft:barrier", 0, active.title(), "", -1, "abandon", true));
			}
		}

		List<Map.Entry<Identifier, Contract>> offers = offers(v.role);
		for (int i = 0; i < offers.size(); i++) {
			Contract c = offers.get(i).getValue();
			if (offers.get(i).getKey().toString().equals(gig.contract())) {
				continue;
			}

			rows.add(new NpcWire.Row("c" + i, icon(c), c.type() == Contract.Type.DELIVER ? Math.min(c.count(), 64) : 0, c.title(),
				GigRules.progressLine(c, 0), c.reward(), "take", active == null));
		}

		return rows;
	}

	@Override
	public String press(final Visit v, final String row) {
		GigData.Gig gig = gig(v.player);
		Contract active = contract(gig);
		if (row.equals("abandon") || row.equals("active") && active != null && active.type() != Contract.Type.DELIVER) {
			if (gig.none()) {
				return "none";
			}

			v.player.setAttached(GigData.GIG, GigData.Gig.NONE);
			Cyberblock.LOG.info("npc: gig {} dropped by {}", gig.contract(), v.player.getPlainTextName());
			return "abandoned";
		}

		if (row.equals("active")) {
			if (active == null) {
				return "none";
			}

			Item item = Shopkeeper.item(active.target());
			if (item == null || Eddies.count(v.player.getInventory(), item) < active.count()) {
				return "noitem";
			}

			Eddies.take(v.player.getInventory(), item, active.count());
			complete(v.player, gig, active);
			v.say("gig_done");
			return "done";
		}

		List<Map.Entry<Identifier, Contract>> offers = offers(v.role);
		int i = row.matches("c\\d{1,2}") ? Integer.parseInt(row.substring(1)) : -1;
		if (i < 0 || i >= offers.size()) {
			return "gone";
		}

		if (active != null) {
			v.say("gig_busy");
			return "busy";
		}

		Map.Entry<Identifier, Contract> offer = offers.get(i);
		v.player.setAttached(GigData.GIG, new GigData.Gig(offer.getKey().toString(), 0));
		Cyberblock.LOG.info("npc: gig {} ({}) taken by {}", offer.getKey(), offer.getValue().type().id(), v.player.getPlainTextName());
		if (offer.getValue().type() == Contract.Type.REACH && target(offer.getValue()) == null) {
			Cyberblock.LOG.warn("npc: gig {} names landmark {} but no landmark content has it (it cannot be reached)", offer.getKey(), offer.getValue().target());
		}

		v.say("gig_taken");
		return "taken";
	}

	/** Pays the reward and clears the gig. */
	private static void complete(final ServerPlayer player, final GigData.Gig gig, final Contract c) {
		player.setAttached(GigData.GIG, GigData.Gig.NONE);
		player.setAttached(GigData.DONE, player.getAttachedOrElse(GigData.DONE, 0) + 1);
		Eddies.earn(player, c.reward());
		if (c.rewardItem() != null) {
			Item item = Shopkeeper.item(c.rewardItem().toString());
			Eddies.give(player, item, c.rewardCount());
		}

		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.0F);
		player.sendSystemMessage(Component.translatableWithFallback("cyberblock.npc.gig.done", "Gig done: %s (+%s eddies)", c.title(), c.reward()));
		Cyberblock.LOG.info("npc: gig {} done by {} (+{} eddies)", gig.contract(), player.getPlainTextName(), c.reward());
	}

	/** One step of an active gig: progress shown on the action bar, the reward at the count. */
	private static void step(final ServerPlayer player, final GigData.Gig gig, final Contract c) {
		int progress = GigRules.next(gig.progress(), c.count());
		if (progress >= c.count()) {
			complete(player, gig, c);
			return;
		}

		player.setAttached(GigData.GIG, new GigData.Gig(gig.contract(), progress));
		player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.npc.gig.progress", "%s: %s / %s", c.title(), progress, c.count()));
	}

	/** A player's melee blow (AttackEntityCallback): remembered when it is on a Night City person and the player has a defeat gig. */
	static void struck(final ServerPlayer player, final Entity entity) {
		Contract c = contract(gig(player));
		if (c != null && c.type() == Contract.Type.DEFEAT && entity instanceof ProxyEntity proxy && proxy.getType() == ProxyEntities.HUMAN && !proxy.dead()) {
			strikes.put(entity, new Strike(player.getUUID(), player.level().getServer().getTickCount()));
		}
	}

	/**
	 * A solved Breach Protocol on a device of this kind (section 1.11 kinds: door, terminal, camera, ...): one step of a breach gig.
	 * Phase 6's Breach (6B) calls this; until it does, breach gigs cannot progress (logged once at server start).
	 */
	public static void breached(final ServerPlayer player, final String deviceKind) {
		breachHookLogged = true;
		GigData.Gig gig = gig(player);
		Contract c = contract(gig);
		if (c != null && GigRules.breachCounts(c, deviceKind)) {
			step(player, gig, c);
		}
	}

	static void started() {
		if (!breachHookLogged) {
			Cyberblock.LOG.info("npc: breach gigs advance through Gigs.breached (Phase 6 Breach); deliver, defeat (melee) and reach gigs are tracked here");
		}
	}

	/** The place a reach gig leads to: its pos, else its landmark's. */
	static @Nullable Vec3 target(final Contract c) {
		if (c.pos() != null) {
			return new Vec3(c.pos()[0], c.pos()[1], c.pos()[2]);
		}

		Identifier id = c.target().isEmpty() ? null : Identifier.tryParse(c.target());
		return id == null ? null : NpcContent.landmark(id).orElse(null);
	}

	/** Server tick: struck people that died count for their striker; once a second, reach gigs. */
	static void tick(final MinecraftServer server) {
		int now = server.getTickCount();
		if (now % 10 == 0) {
			for (Iterator<Map.Entry<Entity, Strike>> it = strikes.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<Entity, Strike> e = it.next();
				if (!(e.getKey() instanceof ProxyEntity proxy) || now - e.getValue().tick() > STRIKE_TICKS) {
					it.remove();
				} else if (proxy.dead()) {
					it.remove();
					ServerPlayer player = server.getPlayerList().getPlayer(e.getValue().player());
					GigData.Gig gig = player == null ? GigData.Gig.NONE : gig(player);
					Contract c = contract(gig);
					if (c != null && GigRules.defeatCounts(c, proxy.label())) {
						Cyberblock.LOG.info("npc: gig {}: {} defeated a {} figure", gig.contract(), player.getPlainTextName(), proxy.label().isEmpty() ? "civilian" : proxy.label());
						step(player, gig, c);
					}
				} else if (proxy.isRemoved()) {
					it.remove();
				}
			}
		}

		if (now % 20 != 0) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			GigData.Gig gig = gig(player);
			Contract c = contract(gig);
			if (c != null && c.type() == Contract.Type.REACH && player.level().dimension() == Level.OVERWORLD
				&& GigRules.reached(c, target(c), player.position())) {
				complete(player, gig, c);
			}
		}
	}

	static void clear() {
		strikes.clear();
	}
}
