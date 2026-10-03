// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock;

import java.util.function.Consumer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Cyberblock implements ModInitializer {
	public static final String ID = "cyberblock";
	public static final Logger LOG = LoggerFactory.getLogger(ID);
	/** True while Cyberpunk is driving the camera. The integrated server shares this JVM, so both sides read it. */
	public static volatile boolean active;
	/** Where events for the host go (JSON lines); the client's HostLink sets it. */
	public static volatile Consumer<String> events = message -> {};

	@Override
	public void onInitialize() {
		ProxyEntities.register();
		Bridge.proxyById = ProxyManager::byId;
		Bridge.proxyIdOf = ProxyManager::idOf;
		ServerLifecycleEvents.SERVER_STARTED.register(WorldBridge::attach);
		// before the world is saved, so Night City's barriers and the proxies are never kept in the save
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			ProxyManager.clear();
			WorldBridge.detach();
		});
		ServerTickEvents.END_SERVER_TICK.register(WorldBridge::tick);
		ServerTickEvents.END_SERVER_TICK.register(ProxyManager::tick);
		LOG.info("cyberblock loaded");
	}
}
