package dev.cyberblock.client.cyber;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.HostLink;
import dev.cyberblock.client.net.LoginScreen;
import dev.cyberblock.cyber.CyberActs;
import dev.cyberblock.cyber.CyberWire;
import dev.cyberblock.cyber.PerkRules;
import dev.cyberblock.cyber.ScanInfo;
import dev.cyberblock.net.Payloads;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

/**
 * Track C's links (megaplan 2026-10-04 task 1-C1): the LOCAL host messages it reads from its own Cyberpunk (`hello`, `key`,
 * through HostLink.listen, on the link thread), the requests it sends the server (`cyberblock:act`, CyberActs) and the
 * server's answers (`cyberblock:ui`). The UP messages (`scaninfo`, `hackresult`, `hackspread`, `killed`, `state`) are the
 * server's now (CyberNet), so a guest's scans come back here the same way as a host's.
 */
public final class CyberLink {
	/** Sprint presses from the host (link thread only). */
	private static final PerkRules.DoubleTap sprintTaps = new PerkRules.DoubleTap();
	private static int bad;

	private CyberLink() {
	}

	static void register() {
		// the binds, and a chat note when Cyberpunk uses other keys than Minecraft's controls show
		HostLink.listen("hello", m -> onClient(CyberKeys::hostHello));
		HostLink.listen("key", CyberLink::key);
		// the world closed: Cyberpunk takes V's perks and implants off (the server that sent them can no longer say so)
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			HostLink.send(CyberWire.perks(Map.of()));
			HostLink.send(CyberWire.cyberware(Set.of()));
		});
		if (!ClientPlayNetworking.registerGlobalReceiver(Payloads.Ui.TYPE, (payload, context) -> {
			// the join password box is the client net's; everything else here is the cyber track's
			if (!LoginScreen.onUi(payload.t(), payload.json())) {
				onUi(payload.t(), payload.json());
			}
		})) {
			// the client net already receives `cyberblock:ui`: it hands the cyber track's types to onUi
			Cyberblock.LOG.info("cyber: cyberblock:ui is received by the client net");
		}
	}

	/** A request to the server (client thread). False when the server has no Cyberblock channel (nothing is sent). */
	static boolean act(final String t, final String json) {
		if (!ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			return false;
		}

		ClientPlayNetworking.send(new Payloads.Act(t, json));
		return true;
	}

	/** One `cyberblock:ui` message for the cyber track (client thread): false when the type is not its own. */
	public static boolean onUi(final String t, final String json) {
		if (!t.equals("scaninfo")) {
			return false;
		}

		try {
			JsonObject m = JsonParser.parseString(json).getAsJsonObject();
			ScanInfo info = ScanInfo.parse(m);
			if (info == null) {
				warn("bad scaninfo dropped");
			} else {
				ScanClient.onInfo(info, m.has("eid") ? m.get("eid").getAsInt() : -1);
			}
		} catch (RuntimeException e) {
			warn("bad scaninfo dropped");
		}

		return true;
	}

	private static void onClient(final Runnable task) {
		Minecraft.getInstance().execute(task);
	}

	/** A bad message could make this a log flood: say it once, then every 100th time. */
	static void warn(final String what) {
		if (bad++ % 100 == 0) {
			Cyberblock.LOG.warn("{} (seen {} times)", what, bad);
		}
	}

	/**
	 * {"t":"key","k":...,"down":bool}: track C handles hack, skills, cyberware, activate, arms and camo, and watches sprint (forwarded,
	 * Cyberpunk keeps it) for the Sandevistan's double tap, and jump for the legs implants' effects (CyberwareClient).
	 */
	private static void key(final JsonObject m) {
		String k = m.get("k").getAsString();
		boolean down = !m.has("down") || m.get("down").getAsBoolean();
		if (k.equals("sprint")) {
			if (down && sprintTaps.press(Util.getMillis())) {
				onClient(() -> act(CyberActs.SANDEVISTAN, "{}"));
			}

			return;
		}

		if (k.equals("jump")) {
			CyberwareClient.hostJump(down);
			return;
		}

		KeyMapping mapping = switch (k) {
			case "hack" -> CyberKeys.hack;
			case "skills" -> CyberKeys.skills;
			case "cyberware" -> CyberKeys.cyberware;
			case "activate" -> CyberKeys.activate;
			case "arms" -> CyberKeys.arms;
			case "camo" -> CyberKeys.camo;
			default -> null;
		};
		if (mapping != null) {
			Minecraft minecraft = Minecraft.getInstance();
			onClient(() -> CyberKeys.onHostKey(minecraft, mapping, down));
		}
	}
}
