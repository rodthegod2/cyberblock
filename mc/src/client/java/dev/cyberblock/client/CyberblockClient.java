// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock.client;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntities;
import dev.cyberblock.WorldBridge;
import dev.cyberblock.client.settings.AimDebug;
import dev.cyberblock.client.settings.SettingsClient;
import dev.cyberblock.client.view.ViewClient;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.server.level.ServerPlayer;

public class CyberblockClient implements ClientModInitializer {
	/** Run on the server once the player has joined. */
	private static final List<String> SETUP = List.of(
		"gamerule advance_time false",
		"gamerule advance_weather false",
		"gamerule spawn_mobs false",
		"gamerule spawn_monsters false",
		"gamerule spawn_patrols false",
		"gamerule spawn_phantoms false",
		"gamerule spawn_wandering_traders false",
		"gamerule send_command_feedback false",
		"gamerule log_admin_commands false",
		"gamerule keep_inventory true",
		"difficulty normal",
		"gamerule show_advancement_messages false",
		"gamerule player_movement_check false",
		"time set noon",
		"weather clear"
	);
	private static boolean configured;
	/** Server ticks until the setup commands run (the player isn't in the player list yet when JOIN fires). */
	private static int setupIn = -1;
	private static int respawnIn;

	@Override
	public void onInitializeClient() {
		HostLink.launch();
		SettingsClient.init();
		ViewClient.init();
		// proxies are never drawn: Cyberpunk shows the NPC or vehicle they stand for
		EntityRendererRegistry.register(ProxyEntities.HUMAN, NoopRenderer::new);
		EntityRendererRegistry.register(ProxyEntities.VEHICLE, NoopRenderer::new);
		ClientTickEvents.END_CLIENT_TICK.register(CyberblockClient::tick);
		// launcher players (plan retest3 M5): a "Play Cyberblock" button on the title screen
		ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
			if (screen instanceof TitleScreen title) {
				AutoWorld.addTitleButton(minecraft, title, width, height);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			hang(handler.player);
			setupIn = 10;
		});
		// a respawned player is a new entity without NoGravity: he fell into the void again and died, over and over
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> hang(newPlayer));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (setupIn > 0 && --setupIn == 0) {
				SETUP.forEach(WorldBridge::command);
			}
		});
	}

	/**
	 * Never left gliding (from a previous session or life), and with no host yet Steve hangs where he is instead of falling into the
	 * void. The chest slot is kept: it may hold his elytra (the cyber track's starter kit replaces the old block kit).
	 */
	private static void hang(final ServerPlayer player) {
		player.stopFallFlying();
		player.setNoGravity(true);
	}

	private static void tick(final Minecraft minecraft) {
		// a death with no host driving (the void) is respawned straight away; while the host drives, Cyberpunk damage can kill
		// Steve for real: then the death screen is shown over the host's picture and he respawns with the host's cursor and click
		if (minecraft.player != null && !Cyberblock.active && minecraft.gui.screen() instanceof DeathScreen && --respawnIn <= 0) {
			respawnIn = 40;
			minecraft.player.respawn();
		}

		if (!configured) {
			configured = true;
			configure(minecraft.options);
		}

		AutoWorld.tick(minecraft);
		StatusReporter.tick(minecraft);
		AimDebug.tick(minecraft);
	}

	/** Settings for sitting behind another game: keep running unfocused, and no sky/cloud/bobbing effects in the picture. */
	private static void configure(final Options options) {
		options.pauseOnLostFocus = false;
		options.onboardAccessibility = false;
		options.tutorialStep = TutorialSteps.NONE;
		options.cloudStatus().set(CloudStatus.OFF);
		options.bobView().set(false);
		options.vignette().set(false);
		options.improvedTransparency().set(false);
		options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
		options.fovEffectScale().set(0.0);
		options.damageTiltStrength().set(0.0);
		options.menuBackgroundBlurriness().set(0);
		options.enableVsync().set(false);
		// the host shows ~60-120 fps: rendering faster only competes with it for the GPU
		options.framerateLimit().set(120);
		options.save();
	}
}
