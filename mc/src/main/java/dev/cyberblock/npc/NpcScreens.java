package dev.cyberblock.npc;

import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntities;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.net.ServerNet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The role screens (megaplan task 7-N1): a right-click on a Night City figure whose role tag an {@code npc_role} lists, or on a hub
 * NPC, opens its role's screen, and every press comes back as {@code cyberblock:act t:"npc"}. The server checks each act again (the
 * NPC still exists, is within reach, still has that role), runs it in the role's service and sends the screen again with the result
 * and the NPC's line. Server thread.
 */
final class NpcScreens {
	/** How far from the NPC a player may use its screen (vanilla's entity reach, plus a margin). */
	static final double REACH = 8.0;
	private static final Map<UUID, Visit> visits = new HashMap<>();
	static final Shopkeeper shopkeeper = new Shopkeeper();
	static final Clinic clinic = new Clinic();
	private static final Map<String, Visit.Service> services = Map.of("shop", shopkeeper, "ripperdoc", clinic.ripperdoc, "medic", clinic.medic,
		"contracts", new Gigs(), "bartender", new Bar(), "mechanic", new Garage());

	private NpcScreens() {
	}

	/** The NPC's role: a hub NPC's own, a living Night City person's from its role tag; null when it has none with a screen. */
	static @Nullable Visit visit(final ServerPlayer player, final Entity npc) {
		if (npc instanceof HubNpc h && h.role() != null) {
			NpcRole role = NpcContent.roles().get(h.role());
			Identifier shop = role == null ? null : h.shop() != null ? h.shop() : role.shopFor("");
			return role == null ? null : new Visit(player, npc, h.role(), role, h.npcName(), h.spot().isEmpty() ? npc.getStringUUID() : "hub/" + h.spot(), shop);
		}

		if (npc instanceof ProxyEntity proxy && proxy.getType() == ProxyEntities.HUMAN && !proxy.dead()) {
			Map.Entry<Identifier, NpcRole> e = NpcContent.roleFor(proxy.label());
			return e == null ? null : new Visit(player, npc, e.getKey(), e.getValue(), "", npc.getStringUUID(), e.getValue().shopFor(NpcRole.tag(proxy.label())));
		}

		return null;
	}

	private static List<String> tabs(final Visit v) {
		List<String> tabs = services.get(v.role.screen()).tabs(v);
		return tabs.isEmpty() ? List.of("none") : tabs;
	}

	/** A right-click (UseEntityCallback): true when the NPC has a role screen and it opened. */
	static boolean open(final ServerPlayer player, final Entity npc) {
		Visit v = visit(player, npc);
		if (v == null) {
			return false;
		}

		v.tab = tabs(v).getFirst();
		v.say("greeting");
		visits.put(player.getUUID(), v);
		Cyberblock.LOG.info("npc: {} opened {} ({}) at {}", player.getPlainTextName(), v.roleId, v.role.screen(), npc instanceof HubNpc ? "a hub NPC" : "a figure");
		send(v);
		return true;
	}

	static void send(final Visit v) {
		List<NpcWire.Row> rows = v.tab.equals("none") ? List.of() : services.get(v.role.screen()).rows(v);
		ServerNet.ui(v.player, NpcWire.T_NPC, NpcWire.screen(new NpcWire.Screen(v.npc.getId(), v.role.screen(), v.name, v.role.titleKey(), v.line,
			Eddies.shown(v.player), v.tab, tabs(v), rows, v.result)));
	}

	/** {@code cyberblock:act t:"npc"}: a tab, a press, a surgery cut or abort. */
	static void act(final ServerPlayer player, final JsonObject json) {
		NpcWire.Act act = NpcWire.parseAct(json);
		Entity npc = act == null ? null : player.level().getEntity(act.e());
		if (npc == null || npc.isRemoved() || npc.distanceToSqr(player) > REACH * REACH) {
			return;
		}

		Visit fresh = visit(player, npc);
		Visit last = visits.get(player.getUUID());
		if (fresh == null) {
			// a /reload took the role away: nothing more to do here
			visits.remove(player.getUUID());
			return;
		}

		Visit v = last != null && last.npc == npc && last.roleId.equals(fresh.roleId) ? last : fresh;
		visits.put(player.getUUID(), v);
		List<String> tabs = tabs(v);
		if (!tabs.contains(v.tab)) {
			v.tab = tabs.getFirst();
		}

		switch (act.a()) {
			case "tab" -> {
				if (tabs.contains(act.arg())) {
					v.tab = act.arg();
					v.result = "";
				}
			}
			case "do" -> {
				if (v.tab.equals("none")) {
					return;
				}

				String result = services.get(v.role.screen()).press(v, act.arg());
				if (result.isEmpty() && v.role.screen().equals("ripperdoc")) {
					// a surgery started: the bar is on the player's screen and the cut answers
					return;
				}

				v.result = result;
			}
			case "cut", "abort" -> {
				String result = clinic.cut(v, act.a().equals("abort"));
				if (result == null) {
					return;
				}

				v.result = result;
			}
			default -> {
				return;
			}
		}

		send(v);
	}

	static void forget(final UUID player) {
		visits.remove(player);
		clinic.forget(player);
	}

	static void clear() {
		visits.clear();
		shopkeeper.clear();
		clinic.clear();
		Gigs.clear();
	}
}
