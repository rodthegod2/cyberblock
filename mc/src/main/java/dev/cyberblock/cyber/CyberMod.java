package dev.cyberblock.cyber;

import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.WorldBridge;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/** Layer 6 (plan 2026-10-03, track C), server half: cyber items, RAM, perks, the starter kit. */
public class CyberMod implements ModInitializer {
	@Override
	public void onInitialize() {
		CyberData.register();
		CyberItems.register();
		ServerTickEvents.END_SERVER_TICK.register(CyberServer::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			Hacking.clear();
			PerkEffects.reset(server);
		});
		// the host usually says hello at the title screen, before any world: so `perks` also goes out when the player joins
		// (handler.player: JOIN fires before PlayerList adds him, so localPlayer() is still null), and a closed world's
		// ranks are dropped on the Cyberpunk side when he leaves
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> PerkShop.sendPerks(handler.player));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			PerkEffects.reset(server);
			Cyberblock.events.accept(CyberWire.perks(Map.of()));
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> PerkEffects.apply(newPlayer));
		// /cyberblock kit, for everyone (permission level 0)
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
			Commands.literal("cyberblock").then(Commands.literal("kit").executes(command -> {
				Kit.give(command.getSource().getPlayerOrException());
				command.getSource().sendSuccess(() -> Component.translatable("cyberblock.kit.given"), false);
				return 1;
			}))));
		Bridge.perkRank = id -> {
			ServerPlayer player = localPlayer();
			return player == null ? 0 : CyberData.rank(player, id);
		};
		Cyberblock.LOG.info("cyberblock cyber loaded");
	}

	/** The singleplayer world's player (the spec is singleplayer only), or null before anyone joined. Server thread. */
	public static @Nullable ServerPlayer localPlayer() {
		MinecraftServer server = WorldBridge.server();
		if (server == null) {
			return null;
		}

		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		return players.isEmpty() ? null : players.getFirst();
	}
}
