// Based on universal-modder (MIT) by Rehan: examples/minecraft-gta5-passthrough
package dev.cyberblock;

import java.util.Locale;
import java.util.function.Consumer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Cyberblock implements ModInitializer {
	public static final String ID = "cyberblock";
	public static final Logger LOG = LoggerFactory.getLogger(ID);
	/** True while Cyberpunk is driving the camera. The integrated server shares this JVM, so both sides read it. */
	public static volatile boolean active;
	/** Where events for the host go (JSON lines); the client's HostLink sets it. */
	public static volatile Consumer<String> events = message -> {};
	/**
	 * Steve is launched (plan retest2 R5): Minecraft's physics move him, so he collides with blocks (PlayerMixin) as while gliding.
	 * The client's Flight sets it for the length of a launch; the integrated server shares this JVM.
	 */
	public static volatile boolean launching;

	@Override
	public void onInitialize() {
		ProxyEntities.register();
		Bridge.proxyById = ProxyManager::byId;
		Bridge.proxyIdOf = ProxyManager::idOf;
		Bridge.hostileProxy = e -> e instanceof ProxyEntity p && p.hostile();
		Bridge.proxyAimedAt = ProxyManager::aimedAt;
		// Compass Ping (plan retest3 section 1.5)
		Bridge.pingedProxy = e -> e instanceof ProxyEntity p && p.pinged();
		Bridge.pingProxy = (e, ticks) -> {
			if (e instanceof ProxyEntity p) {
				p.setPinged(ticks);
			}
		};
		ServerLifecycleEvents.SERVER_STARTED.register(WorldBridge::attach);
		// before the world is saved, so Night City's barriers and the proxies are never kept in the save
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			ProxyManager.clear();
			MobWar.clear();
			WorldBridge.detach();
		});
		ServerEntityEvents.ENTITY_LOAD.register(MobWar::onEntityLoad);
		ServerTickEvents.END_SERVER_TICK.register(WorldBridge::tick);
		ServerTickEvents.END_SERVER_TICK.register(ProxyManager::tick);
		ServerTickEvents.END_SERVER_TICK.register(MobWar::tick);
		ServerTickEvents.END_SERVER_TICK.register(Projectiles::tick);
		ServerTickEvents.END_SERVER_TICK.register(PlayerEffects::tick);
		// /cyberblock level, for everyone (permission level 0; Brigadier merges it with the cyber track's /cyberblock literals)
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
			Commands.literal("cyberblock").then(Commands.literal("level").executes(command -> level(command.getSource())))));
		// /cyberblock survey [start|redo|stop|status]: the Atlas survey tour (the client sends `survey` to Cyberpunk)
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
			var survey = Commands.literal("survey").executes(command -> survey(command.getSource(), "start"));
			for (String op : new String[] {"start", "redo", "stop", "status"}) {
				survey = survey.then(Commands.literal(op).executes(command -> survey(command.getSource(), op)));
			}

			dispatcher.register(Commands.literal("cyberblock").then(survey));
		});
		LOG.info("cyberblock loaded");
	}

	/** /cyberblock level (plan retest2 section 1.9): re-aligns the world's ground to the floor V stands on. */
	private static int level(final CommandSourceStack source) {
		String refused = WorldBridge.levelRefused();
		if (refused != null) {
			source.sendFailure(Component.translatable(refused));
			return 0;
		}

		int was = WorldBridge.level(source.getServer(), "/cyberblock level");
		String now = metres(Bridge.alignK), before = metres(Math.max(was, 0));
		source.sendSuccess(() -> Component.translatable("cyberblock.level.done", now, before), false);
		return 1;
	}

	private static int survey(final CommandSourceStack source, final String op) {
		String line = Bridge.surveyCommand.apply(op);
		source.sendSuccess(() -> Component.literal(line), false);
		return 1;
	}

	private static String metres(final int k) {
		return String.format(Locale.ROOT, "%.3f", GroundAlign.offset(k));
	}
}
