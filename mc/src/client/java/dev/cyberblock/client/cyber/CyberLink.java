package dev.cyberblock.client.cyber;

import com.google.gson.JsonObject;
import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.WorldBridge;
import dev.cyberblock.cyber.CyberMod;
import dev.cyberblock.cyber.HackResult;
import dev.cyberblock.cyber.Hacking;
import dev.cyberblock.cyber.PerkEffects;
import dev.cyberblock.cyber.PerkRules;
import dev.cyberblock.cyber.PerkShop;
import dev.cyberblock.cyber.ScanInfo;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;

/**
 * Track C's host message listeners (plan section 1.6): HostLink hands them every `hello`, `state`, `key`, `scaninfo`,
 * `hackresult` and `killed` on the link thread, after its own handling. Each moves its work to the client or the
 * integrated server thread.
 */
final class CyberLink {
	/** Sprint presses from the host (link thread only). */
	private static final PerkRules.DoubleTap sprintTaps = new PerkRules.DoubleTap();
	private static int bad;

	private CyberLink() {
	}

	static void register() {
		listen("hello", m -> {
			onClient(CyberKeys::sendBinds);
			onServer(() -> {
				ServerPlayer player = CyberMod.localPlayer();
				if (player != null) {
					PerkShop.sendPerks(player);
				}
			});
		});
		listen("key", CyberLink::key);
		listen("scaninfo", CyberLink::scanInfo);
		listen("hackresult", CyberLink::hackResult);
	}

	/** {"t":"hackresult",...}: refund or sound, on the server. */
	private static void hackResult(final JsonObject m) {
		HackResult result = HackResult.parse(m);
		MinecraftServer server = WorldBridge.server();
		if (result == null) {
			warn("bad hackresult dropped");
		} else if (server != null) {
			server.execute(() -> Hacking.onResult(server, result));
		}
	}

	private static void listen(final String type, final Consumer<JsonObject> listener) {
		Bridge.listeners.put(type, listener);
	}

	private static void onClient(final Runnable task) {
		Minecraft.getInstance().execute(task);
	}

	/** Runs on the integrated server's thread; dropped when no world is open. */
	static void onServer(final Runnable task) {
		MinecraftServer server = WorldBridge.server();
		if (server != null) {
			server.execute(task);
		}
	}

	/** A hostile host could make this a log flood: say it once, then every 100th time. */
	static void warn(final String what) {
		if (bad++ % 100 == 0) {
			Cyberblock.LOG.warn("{} (seen {} times)", what, bad);
		}
	}

	/**
	 * {"t":"key","k":...,"down":bool}: track C handles hack and skills, and watches sprint (forwarded, Cyberpunk keeps it)
	 * for the Sandevistan's double tap.
	 */
	private static void key(final JsonObject m) {
		String k = m.get("k").getAsString();
		boolean down = !m.has("down") || m.get("down").getAsBoolean();
		if (k.equals("sprint")) {
			if (down && sprintTaps.press(Util.getMillis())) {
				onServer(() -> {
					ServerPlayer player = CyberMod.localPlayer();
					if (player != null) {
						PerkEffects.sandevistan(player.level().getServer(), player);
					}
				});
			}

			return;
		}

		KeyMapping mapping = switch (k) {
			case "hack" -> CyberKeys.hack;
			case "skills" -> CyberKeys.skills;
			default -> null;
		};
		if (mapping != null) {
			Minecraft minecraft = Minecraft.getInstance();
			onClient(() -> CyberKeys.onHostKey(minecraft, mapping, down));
		}
	}

	/** {"t":"scaninfo",...}: drawn on the client; a target that is there becomes the locked one on the server. */
	private static void scanInfo(final JsonObject m) {
		ScanInfo info = ScanInfo.parse(m);
		if (info == null) {
			warn("bad scaninfo dropped");
			return;
		}

		ScanClient.onInfo(info);
		if (info.ok()) {
			onServer(() -> Hacking.lock(info.target(), info.device()));
		}
	}
}
