package dev.cyberblock.cyber;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread upkeep of the cyber state, every server tick. */
final class CyberServer {
	private CyberServer() {
	}

	static void tick(final MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Ripperdoc.migrate(player);
			CyberData.setRam(player, Ram.tick(CyberData.ram(player), CyberData.maxRam(player), player.tickCount, CyberData.has(player, Perk.OVERCLOCK)));
		}

		Hacking.tick(server);
		HackFx.tick(server);
		PerkEffects.tick(server);
		CyberwareEffects.tick(server);
	}
}
