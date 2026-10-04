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
 * The role screens (megaplan task 7-N1): a right-click on a figure or hub NPC whose role has a screen opens it, and every press comes
 * back as {@code cyberblock:act t:"npc"}. The server checks each act again (the NPC still exists, is within reach, still has that
 * role), runs it in the role's service and sends the screen again with the result and the NPC's line. Server thread.
 */
final class NpcScreens {
	/** How far from the NPC a player may use its screen (vanilla's entity reach, plus a margin). */
	static final double REACH = 8.0;
	private static final Map<UUID, Visit> visits = new HashMap<>();
	static final Shopkeeper shopkeeper = new Shopkeeper();
	static final Clinic clinic = new Clinic();
	static final Gigs gigs = new Gigs();
	private static final Map<String, Visit.Service> services = Map.of("vendor", shopkeeper, "ripperdoc", clinic.ripperdoc, "medic", clinic.medic,
		"fixer", gigs, "bartender", new Bar(), "mechanic", new Garage());

	private NpcScreens() {
	}

	/** An NPC's role: a hub NPC's own, a living Night City person's from its name tag; null when it has none with a screen. */
	static @Nullable Visit visit(final ServerPlayer player, final Entity npc) {
		Identifier roleId = null;
		NpcRole role = null;
		boolean hub = npc instanceof HubNpc;
		if (npc instanceof HubNpc h && h.role() != null) {
			roleId = h.role();
			role = NpcContent.roles().get(roleId);
		} else if (npc instanceof ProxyEntity proxy && proxy.getType() == ProxyEntities.HUMAN && !proxy.dead()) {
			Map.Entry<Identifier, NpcRole> e = NpcContent.roleFor(proxy.label());
			if (e != null) {
				roleId = e.getKey();
				role = e.getValue();
			}
		}

		if (role == null || !role.has() || !services.containsKey(role.screen())) {
			return null;
		}

		String key = npc instanceof HubNpc h ? h.spot() : npc.getStringUUID();
		return new Visit(player, npc, roleId, role, hub, key.isEmpty() ? npc.getStringUUID() : key);
	}

	/** A right-click (UseEntityCallback): true when the NPC has a role screen and it opened. */
	static boolean open(final ServerPlayer player, final Entity npc) {
		Visit v = visit(player, npc);
		if (v == null) {
			return false;
		}

		Visit.Service service = services.get(v.role.screen());
		v.tab = service.tabs(v).getFirst();
		v.say("greeting");
		visits.put(player.getUUID(), v);
		Cyberblock.LOG.info("npc: {} opened {} ({}) at {}", player.getPlainTextName(), v.roleId, v.role.screen(), v.hub ? "the hub" : "a figure");
		send(v);
		return true;
	}

	static void send(final Visit v) {
		Visit.Service service = services.get(v.role.screen());
		List<String> tabs = service.tabs(v);
		ServerNet.ui(v.player, NpcWire.T_NPC, NpcWire.screen(new NpcWire.Screen(v.npc.getId(), v.role.screen(), v.name(), v.line, Eddies.shown(v.player),
			v.tab, tabs, service.rows(v), v.result)));
	}

	/** {@code cyberblock:act t:"npc"}: a tab, a press, a surgery cut or abort. */
	static void act(final ServerPlayer player, final JsonObject json) {
		NpcWire.Act act = NpcWire.parseAct(json);
		Entity npc = act == null ? null : player.level().getEntity(act.e());
		if (npc == null || npc.isRemoved() || npc.distanceToSqr(player) > REACH * REACH) {
			return;
		}

		Visit last = visits.get(player.getUUID());
		Visit v = last != null && last.npc == npc ? last : visit(player, npc);
		if (v == null) {
			return;
		}

		Visit fresh = visit(player, npc);
		if (fresh == null || !fresh.roleId.equals(v.roleId)) {
			// a /reload took the role away (or changed it): nothing more to do here
			visits.remove(player.getUUID());
			return;
		}

		visits.put(player.getUUID(), v);
		Visit.Service service = services.get(v.role.screen());
		List<String> tabs = service.tabs(v);
		if (v.tab.isEmpty() || !tabs.contains(v.tab)) {
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
				String result = service.press(v, act.arg());
				if (result.isEmpty() && v.role.screen().equals("ripperdoc") && act.arg().startsWith("i.")) {
					// a surgery started: the bar is on the player's screen; the cut answers
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
