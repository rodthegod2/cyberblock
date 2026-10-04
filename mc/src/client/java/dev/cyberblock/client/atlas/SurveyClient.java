package dev.cyberblock.client.atlas;

import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.client.ui.UiLink;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The Atlas survey tour on the Minecraft side: {@code /cyberblock survey [start|redo|stop|status]} sends {@code survey} to Cyberpunk
 * (start lists the stops the shared map already has, so the tour resumes; redo lists none), and while the tour runs a vanilla
 * boss bar at the top of the screen shows its progress and time left. The toasts at the end come from AtlasStore.
 */
public final class SurveyClient {
	public static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "atlas_survey");
	private static final int BAR_WIDTH = 182;

	private SurveyClient() {
	}

	public static void register() {
		Bridge.surveyCommand = SurveyClient::command;
		HudElementRegistry.addLast(HUD_ID, SurveyClient::draw);
	}

	/** The command (server thread): sends the request and returns the line it prints. */
	static String command(final String op) {
		if (!UiLink.linked()) {
			return "Night City is not connected: start Cyberpunk, load a save and press F9 first";
		}

		AtlasWire.SurveyState state = AtlasStore.survey;
		switch (op) {
			case "start", "redo" -> {
				if (state != null && state.running()) {
					return "The Atlas survey is already running: " + progress(state);
				}

				List<Long> done = op.equals("redo") ? List.of() : AtlasStore.doneStops();
				if (op.equals("redo")) {
					AtlasStore.forgetStops();
				}

				Cyberblock.events.accept(AtlasWire.survey("start", done));
				return op.equals("redo") ? "Atlas survey: mapping all of Night City again. Stand somewhere safe on foot and stay AFK; F9 or /cyberblock survey stop ends it."
					: "Atlas survey: " + done.size() + " stops already mapped, mapping the rest. Stand somewhere safe on foot and stay AFK; F9 or /cyberblock survey stop ends it.";
			}
			case "stop" -> {
				Cyberblock.events.accept(AtlasWire.survey("stop", List.of()));
				return "Atlas survey: stopping, V goes back to where the survey started";
			}
			default -> {
				Cyberblock.events.accept(AtlasWire.survey("status", List.of()));
				return state == null ? "Atlas survey: not started yet (/cyberblock survey)" : "Atlas survey: " + state.state() + ", " + progress(state)
					+ (state.reason().isEmpty() ? "" : " (" + state.reason() + ")");
			}
		}
	}

	static String progress(final AtlasWire.SurveyState state) {
		int percent = state.total() == 0 ? 0 : (int) Math.floor(100.0 * state.done() / state.total());
		return state.done() + " of " + state.total() + " stops (" + percent + " %), " + AtlasWire.eta(state.eta()) + " left";
	}

	/** The progress bar while the tour runs (a HUD element, render thread). */
	private static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		AtlasWire.SurveyState state = AtlasStore.survey;
		if (state == null || !state.running() || !UiLink.recentlyLinked()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		int x = (graphics.guiWidth() - BAR_WIDTH) / 2, y = 22;
		Component text = Component.literal(state.state().equals("paused") ? "Atlas survey paused (Night City menu)" : "Atlas survey: " + progress(state));
		graphics.centeredText(minecraft.font, text, graphics.guiWidth() / 2, y - 10, McTheme.WHITE);
		McTheme.bossBar(graphics, x, y, BAR_WIDTH, state.total() == 0 ? 0.0F : (float) state.done() / state.total());
	}
}
