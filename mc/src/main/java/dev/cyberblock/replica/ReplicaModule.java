package dev.cyberblock.replica;

import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.ServerNet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * The replica's registrations (megaplan Phase 3, package 3R): the 26 blocks, the material_palette content kind, the shared Night City
 * map (terrain UP, maptile requests). WorldModule.init calls init() once; the ground, unground and clear
 * handling stays in WorldBridge.
 */
public final class ReplicaModule {
	private ReplicaModule() {
	}

	public static void init() {
		ReplicaBlocks.register();
		Palette.register();
		ServerLifecycleEvents.SERVER_STARTED.register(NightCityMap::attach);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> NightCityMap.detach());
		ServerTickEvents.END_SERVER_TICK.register(NightCityMap::tick);
		HostSessions.listen("terrain", NightCityMap::terrain);
		ServerNet.onAct("maptile", NightCityMap::request);
	}
}
