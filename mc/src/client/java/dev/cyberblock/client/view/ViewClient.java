package dev.cyberblock.client.view;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.settings.CyberblockSettingsScreen;
import dev.cyberblock.client.settings.DebugFlags;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;

/**
 * Phase Q, package QV: the Minecraft side of the third-person view. Entry point {@link #init} (CyberblockClient calls it): the
 * view debug overlay and its log line (Q-V4), the camera options and the {@code prefs} message to the DLL (section 1.9), and their
 * rows on the Cyberblock settings screen. Steve's render position (Q-V1), poses (Q-V2) and the crosshair (Q-V3) hook in through
 * PlayerSync, AvatarRendererMixin and CrosshairHud.
 */
public final class ViewClient {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("cyberblock").resolve("view.json");
	private static volatile ViewPrefs prefs = ViewPrefs.DEFAULT;
	/** The view debug flag the last prefs carried (the DLL starts with it off). */
	private static volatile boolean sentDebug;

	private ViewClient() {
	}

	public static void init() {
		prefs = load();
		// after each host hello the DLL is back on its defaults: tell it ours (link thread)
		Bridge.listen("hello", m -> send());
		Bridge.listen("cam", ViewDebugHud::cam);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(Cyberblock.ID, "view_debug"), ViewDebugHud::draw);
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			ViewDebugHud.tick();
			tick();
		});
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof CyberblockSettingsScreen) {
				ViewSettingsRows.add(screen);
			}
		});
	}

	public static ViewPrefs prefs() {
		return prefs;
	}

	/** A settings row changed: saved, and the DLL hears it. */
	static void setPrefs(final ViewPrefs p) {
		if (p.equals(prefs)) {
			return;
		}

		prefs = p;
		save(p);
		send();
	}

	/** `/cyberblock debug view` flipped since the last prefs: the DLL hears it, so `cam` carries vdbg exactly while it is on. */
	private static void tick() {
		if (DebugFlags.view() != sentDebug) {
			send();
		}
	}

	/** Link thread (hello) and client thread (settings rows, tick). */
	private static synchronized void send() {
		boolean debug = DebugFlags.view();
		sentDebug = debug;
		Cyberblock.events.accept(prefs.message(debug).toString());
	}

	private static ViewPrefs load() {
		if (!Files.isRegularFile(FILE)) {
			return ViewPrefs.DEFAULT;
		}

		try {
			return ViewPrefs.fromJson(JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject());
		} catch (IOException | RuntimeException e) {
			Cyberblock.LOG.warn("view: could not read {}, using the defaults: {}", FILE.getFileName(), e.toString());
			return ViewPrefs.DEFAULT;
		}
	}

	private static void save(final ViewPrefs p) {
		JsonObject o = p.toJson();
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, o.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Cyberblock.LOG.warn("view: could not save the camera options: {}", e.toString());
		}
	}
}
