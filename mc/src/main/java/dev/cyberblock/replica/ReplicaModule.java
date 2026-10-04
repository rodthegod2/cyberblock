package dev.cyberblock.replica;

import dev.cyberblock.net.HostSessions;
import dev.cyberblock.net.ServerNet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

/**
 * The replica's registrations (megaplan Phase 3, package 3R): the 26 blocks, the material_palette content kind, the shared Night City
 * map (terrain UP, maptile requests) and the Offline Builds log. WorldModule.init calls init() once; the ground, unground and clear
 * handling stays in WorldBridge.
 */
public final class ReplicaModule {
	private ReplicaModule() {
	}

	public static void init() {
		ReplicaBlocks.register();
		Palette.register();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			NightCityMap.attach(server);
			ChangeLog.attach(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			NightCityMap.detach();
			ChangeLog.detach();
		});
		ServerTickEvents.END_SERVER_TICK.register(NightCityMap::tick);
		HostSessions.listen("terrain", NightCityMap::terrain);
		ServerNet.onAct("maptile", NightCityMap::request);
		// who is changing a block (the Offline Builds log): a use places into the hit cell or its neighbour; a break is that cell
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level instanceof ServerLevel server && player instanceof ServerPlayer actor) {
				BlockPos at = hit.getBlockPos();
				ChangeLog.acting(actor.getPlainTextName(), at.asLong(), at.relative(hit.getDirection()).asLong(), server.getServer().getTickCount());
			}

			return InteractionResult.PASS;
		});
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (level instanceof ServerLevel server && player instanceof ServerPlayer actor) {
				ChangeLog.acting(actor.getPlainTextName(), pos.asLong(), pos.asLong(), server.getServer().getTickCount());
			}

			return true;
		});
	}
}
