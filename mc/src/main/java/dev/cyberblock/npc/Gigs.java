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
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jspecify.annotations.Nullable;

/**
 * Fixer gigs (megaplan task 7-N1): the fixer's screen ({@code gigs}: take one of the {@code contract}s still offered to the player, hand
 * a delivery in, drop the gig; {@code rumours} as at the bar) and the objective tracking. One gig at a time per player (GigData,
 * saved). {@code deliver_item}: handed in at any fixer (plain copies from the hotbar and
 * backpack only, as a vendor buys them). {@code defeat_hostiles}: a Night City person the player struck (in melee or
 * with a projectile) dies within 60 s (its figure lies down: the host's {@code npcs} dead flag or {@code killed}) and its faction matches. {@code
 * reach_landmark}: checked once a second against the landmark's position (7T's {@code landmark} content). {@code breach_device}:
 * {@link #breached}, which Phase 6's Breach calls on a solved breach. A gig past its {@code time_limit} fails. On completion the
 * reward is paid at once in Minecraft eddies (section 1.16) and items, and the gigs-done count and the {@code cb_gigs} scoreboard
 * objective (the hub's "Gigs Done" board) rise. Server thread.
 */
public final class Gigs implements Visit.Service {
	/** A melee blow on a Night City person by a player with a defeat gig. */
	private record Strike(UUID player, int tick) {
	}

	static final int STRIKE_TICKS = 60 * 20, MAX_OFFERS = 16;
	static final String BOARD = "cb_gigs";
	private static final Map<Entity, Strike> strikes = new WeakHashMap<>();

	@Override
	public List<String> tabs(final Visit v) {
		List<String> tabs = new ArrayList<>();
		if (v.role.offers("gigs")) {
			tabs.add("gigs");
		}

		if (v.role.offers("rumours")) {
			tabs.add("rumours");
		}

		return tabs;
	}

	/** The gigs offered to this player, easiest first, then by id. */
	static List<Map.Entry<Identifier, Contract>> offers(final ServerPlayer player) {
		List<String> finished = player.getAttachedOrElse(GigData.FINISHED, List.of());
		List<Map.Entry<Identifier, Contract>> out = new ArrayList<>();
		for (Map.Entry<Identifier, Contract> e : NpcContent.contracts().entrySet()) {
			if (GigRules.offered(e.getKey().toString(), e.getValue(), finished)) {
				out.add(e);
			}
		}

		out.sort((a, b) -> a.getValue().difficulty() != b.getValue().difficulty() ? Integer.compare(a.getValue().difficulty(), b.getValue().difficulty())
			: a.getKey().compareTo(b.getKey()));
		return out.size() > MAX_OFFERS ? out.subList(0, MAX_OFFERS) : out;
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
			case DELIVER_ITEM -> c.item().toString();
			case DEFEAT_HOSTILES -> "minecraft:iron_sword";
			case REACH_LANDMARK -> "minecraft:compass";
			case BREACH_DEVICE -> "minecraft:redstone";
		};
	}

	/** The row's second line: the progress (if counted) and the gig's text. */
	static String sub(final Contract c, final int progress) {
		String count = GigRules.progress(c, progress);
		return count.isEmpty() ? c.text() : count + "  " + c.text();
	}

	@Override
	public List<NpcWire.Row> rows(final Visit v) {
		if (v.tab.equals("rumours")) {
			return Bar.rumourRows();
		}

		List<NpcWire.Row> rows = new ArrayList<>();
		GigData.Gig gig = gig(v.player);
		Contract active = contract(gig);
		if (active != null) {
			boolean deliver = active.type() == Contract.Type.DELIVER_ITEM;
			Item item = deliver ? Shopkeeper.item(active.item().toString()) : null;
			int have = item == null ? 0 : Eddies.goods(v.player.getInventory(), item, false);
			String note = item == null ? "" : Eddies.kept(v.player.getInventory(), item, active.count());
			String sub = sub(active, deliver ? Math.min(have, active.count()) : gig.progress());
			rows.add(new NpcWire.Row("active", icon(active), deliver ? active.count() : 0, "", active.title(), note.isEmpty() ? sub : note + "  " + sub,
				active.eddies(), deliver ? "turn_in" : "abandon", !deliver || have >= active.count()));
			if (deliver) {
				rows.add(new NpcWire.Row("abandon", "minecraft:barrier", 0, "", active.title(), "", -1, "abandon", true));
			}
		}

		List<Map.Entry<Identifier, Contract>> offers = offers(v.player);
		for (int i = 0; i < offers.size() && rows.size() < NpcWire.MAX_ROWS; i++) {
			Contract c = offers.get(i).getValue();
			if (!offers.get(i).getKey().toString().equals(gig.contract())) {
				rows.add(new NpcWire.Row("c" + i, icon(c), c.type() == Contract.Type.DELIVER_ITEM ? c.count() : 0, "", c.title(), sub(c, 0), c.eddies(),
					"take", active == null));
			}
		}

		return rows;
	}

	@Override
	public String press(final Visit v, final String row) {
		if (row.equals("ask") && v.role.offers("rumours")) {
			return Bar.ask(v);
		}

		if (!v.role.offers("gigs")) {
			return "gone";
		}

		GigData.Gig gig = gig(v.player);
		Contract active = contract(gig);
		if (row.equals("abandon") || row.equals("active") && active != null && active.type() != Contract.Type.DELIVER_ITEM) {
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

			Item item = Shopkeeper.item(active.item().toString());
			if (item == null || Eddies.goods(v.player.getInventory(), item, false) < active.count()) {
				return item == null || Eddies.kept(v.player.getInventory(), item, active.count()).isEmpty() ? "noitem" : "kept";
			}

			Eddies.takeGoods(v.player.getInventory(), item, active.count());
			complete(v.player, gig, active);
			v.line = active.doneText();
			v.say("gig_done", "title", active.title(), "reward", Integer.toString(active.eddies()));
			return "done";
		}

		List<Map.Entry<Identifier, Contract>> offers = offers(v.player);
		int i = row.matches("c\\d{1,2}") ? Integer.parseInt(row.substring(1)) : -1;
		if (i < 0 || i >= offers.size()) {
			return "gone";
		}

		if (active != null) {
			return "busy";
		}

		Map.Entry<Identifier, Contract> offer = offers.get(i);
		Contract c = offer.getValue();
		v.player.setAttached(GigData.GIG, new GigData.Gig(offer.getKey().toString(), 0, v.player.level().getGameTime()));
		Cyberblock.LOG.info("npc: gig {} ({}) taken by {}", offer.getKey(), c.type().id(), v.player.getPlainTextName());
		if (c.type() == Contract.Type.REACH_LANDMARK && target(c) == null) {
			Cyberblock.LOG.warn("npc: gig {} names landmark {} but no landmark content has it (it cannot be reached)", offer.getKey(), c.landmark());
		}

		v.say("gig_offer", "title", c.title(), "reward", Integer.toString(c.eddies()));
		return "taken";
	}

	/** Pays the reward, clears the gig, counts it (and remembers a gig that is not repeatable). */
	private static void complete(final ServerPlayer player, final GigData.Gig gig, final Contract c) {
		player.setAttached(GigData.GIG, GigData.Gig.NONE);
		player.setAttached(GigData.DONE, player.getAttachedOrElse(GigData.DONE, 0) + 1);
		if (!c.repeatable()) {
			List<String> finished = new ArrayList<>(player.getAttachedOrElse(GigData.FINISHED, List.of()));
			finished.add(gig.contract());
			player.setAttached(GigData.FINISHED, List.copyOf(finished));
		}

		Eddies.earn(player, c.eddies());
		for (Contract.Reward r : c.items()) {
			Eddies.give(player, Shopkeeper.item(r.item().toString()), r.count());
		}

		board(player);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.0F);
		player.sendSystemMessage(Component.translatableWithFallback("cyberblock.npc.gig.done", "Gig done: %s (+%s eddies)", c.title(), c.eddies()));
		if (!c.doneText().isEmpty() && c.type() != Contract.Type.DELIVER_ITEM) {
			player.sendSystemMessage(Component.literal(c.doneText()));
		}

		Cyberblock.LOG.info("npc: gig {} done by {} (+{} eddies)", gig.contract(), player.getPlainTextName(), c.eddies());
	}

	/** The hub's "Gigs Done" board: the cb_gigs objective, made as 7H makes it when it does not exist yet. */
	private static void board(final ServerPlayer player) {
		ServerScoreboard scores = player.level().getServer().getScoreboard();
		Objective o = scores.getObjective(BOARD);
		if (o == null) {
			o = scores.addObjective(BOARD, ObjectiveCriteria.DUMMY, Component.translatableWithFallback("cyberblock.afterlife.board.gigs", "Gigs Done"),
				ObjectiveCriteria.RenderType.INTEGER, true, null);
		}

		scores.getOrCreatePlayerScore(player, o).add(1);
	}

	/** One step of an active gig: progress shown on the action bar, the reward at the count. */
	private static void step(final ServerPlayer player, final GigData.Gig gig, final Contract c) {
		int progress = GigRules.next(gig.progress(), c.count());
		if (progress >= c.count()) {
			complete(player, gig, c);
			return;
		}

		player.setAttached(GigData.GIG, new GigData.Gig(gig.contract(), progress, gig.started()));
		player.sendOverlayMessage(Component.translatableWithFallback("cyberblock.npc.gig.progress", "%s: %s / %s", c.title(), progress, c.count()));
	}

	/**
	 * A player's blow, arrow, trident or other projectile on a Night City figure (ProxyEntity's damage path, so ranged kills count too):
	 * remembered when it is a living person and the player has a defeat gig.
	 */
	public static void struck(final ServerPlayer player, final Entity entity) {
		Contract c = contract(gig(player));
		if (c != null && c.type() == Contract.Type.DEFEAT_HOSTILES && entity instanceof ProxyEntity proxy && proxy.getType() == ProxyEntities.HUMAN
			&& !proxy.dead()) {
			strikes.put(entity, new Strike(player.getUUID(), player.level().getServer().getTickCount()));
		}
	}

	/**
	 * A solved Breach Protocol on a device of this section 1.11 kind (door, terminal, camera, turret, ...): one step of a breach gig.
	 * Phase 6's Breach calls this on a solved device breach with the device's {@code DeviceType} id.
	 */
	public static void breached(final ServerPlayer player, final String deviceKind) {
		GigData.Gig gig = gig(player);
		Contract c = contract(gig);
		if (c != null && GigRules.breachCounts(c, deviceKind)) {
			step(player, gig, c);
		}
	}

	/** The place a reach gig leads to: its landmark's position (null when no landmark content has it). */
	static @Nullable Vec3 target(final Contract c) {
		return c.landmark() == null ? null : NpcContent.landmark(c.landmark()).orElse(null);
	}

	/** Server tick: struck people that died count for their striker; once a second, reach gigs and time limits. */
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
						Cyberblock.LOG.info("npc: gig {}: {} defeated a {} figure", gig.contract(), player.getPlainTextName(), proxy.label());
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
			if (c == null) {
				continue;
			}

			if (GigRules.expired(c, gig.started(), player.level().getGameTime())) {
				player.setAttached(GigData.GIG, GigData.Gig.NONE);
				player.sendSystemMessage(Component.translatableWithFallback("cyberblock.npc.gig.expired", "Gig failed: %s ran out of time", c.title()));
				Cyberblock.LOG.info("npc: gig {} of {} ran out of time", gig.contract(), player.getPlainTextName());
			} else if (c.type() == Contract.Type.REACH_LANDMARK && player.level().dimension() == Level.OVERWORLD
				&& GigRules.reached(c, target(c), NpcContent.approximate(c.landmark()), player.position())) {
				complete(player, gig, c);
			}
		}
	}

	static void clear() {
		strikes.clear();
	}
}
