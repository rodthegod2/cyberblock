package dev.cyberblock.cyber;

import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.WorldBridge;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
