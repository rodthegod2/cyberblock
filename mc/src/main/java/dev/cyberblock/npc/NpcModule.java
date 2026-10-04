package dev.cyberblock.npc;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.JoinLock;
import dev.cyberblock.net.ServerNet;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

/**
 * Phase 7's NPC interaction layer (megaplan task 7-N1), server half: the content kinds, the hub NPC entity, the role screens opened by
 * a right-click on a figure or hub NPC (sneak to use an item on it instead), fixer gig tracking, {@code /cyberblock gig} (your gig)
 * and {@code /cyberblock npc spawn <role>} (operators: a role NPC where you stand, for tests and builds outside the hub).
 */
public final class NpcModule {
	private NpcModule() {
	}

	/** Common init, once (Cyberblock.onInitialize), before the server loads its datapacks. */
	public static void init() {
		NpcContent.register();
		GigData.register();
		HubNpcs.register();
		ServerNet.onAct(NpcWire.ACT, NpcScreens::act);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (player instanceof ServerPlayer p && !player.isSpectator() && !player.isShiftKeyDown() && !JoinLock.locked(p) && NpcScreens.open(p, entity)) {
				return InteractionResult.SUCCESS;
			}

			return InteractionResult.PASS;
		});
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (player instanceof ServerPlayer p && !level.isClientSide()) {
				Gigs.struck(p, entity);
			}

			return InteractionResult.PASS;
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Gigs.tick(server);
			HubNpcs.tick(server);
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Cyberblock.LOG.info("npc: {} roles, {} dialogue files, {} contracts, {} ripperdoc implants", NpcContent.roles().size(), NpcContent.dialogue().size(),
				NpcContent.contracts().size(), NpcContent.implants().size());
			Gigs.started();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			NpcScreens.clear();
			HubNpcs.clear();
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> NpcScreens.forget(handler.player.getUUID()));
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(Commands.literal("cyberblock")
			.then(Commands.literal("gig").executes(c -> gig(c.getSource())))
			.then(Commands.literal("npc").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("spawn").then(Commands.argument("role", StringArgumentType.greedyString())
					.suggests((c, b) -> {
						NpcContent.roles().keySet().forEach(id -> b.suggest(id.toString()));
						return b.buildFuture();
					})
					.executes(c -> spawn(c.getSource(), StringArgumentType.getString(c, "role"))))))));
	}

	/** /cyberblock gig: the player's gig and its progress. */
	private static int gig(final CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			return 0;
		}

		GigData.Gig gig = Gigs.gig(player);
		Contract c = Gigs.contract(gig);
		int done = player.getAttachedOrElse(GigData.DONE, 0);
		source.sendSystemMessage(c == null ? Component.translatableWithFallback("cyberblock.npc.gig.none", "No gig. Gigs done: %s. Ask a fixer.", done)
			: Component.translatableWithFallback("cyberblock.npc.gig.status", "Gig: %s (%s). Gigs done: %s.", c.title(), GigRules.progressLine(c, gig.progress()), done));
		return 1;
	}

	/** /cyberblock npc spawn <role>: a role NPC where the operator stands (never saved, as in the hub). */
	private static int spawn(final CommandSourceStack source, final String role) {
		ServerPlayer player = source.getPlayer();
		Identifier id = Identifier.tryParse(role);
		NpcRole r = id == null ? null : NpcContent.roles().get(id);
		if (player == null || r == null) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.npc.spawn.unknown", "No npc_role %s", role));
			return 0;
		}

		NpcRole.HubSpot spot = r.hub().isEmpty() ? new NpcRole.HubSpot(0, 0, 0, 0F, r.name(), "", false) : r.hub().getFirst();
		NpcRole.HubSpot here = new NpcRole.HubSpot(player.getX(), player.getY(), player.getZ(), player.getYRot() + 180.0F, spot.name(), spot.skin(), spot.slim());
		HubNpc npc = HubNpcs.spawn(player.level(), id, here, "");
		if (npc == null) {
			return 0;
		}

		Cyberblock.LOG.info("npc: {} spawned a {} NPC ({})", player.getPlainTextName(), id, r.screen());
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.npc.spawn.done", "Spawned %s (%s)", r.name(), r.screen()));
		return 1;
	}
}
