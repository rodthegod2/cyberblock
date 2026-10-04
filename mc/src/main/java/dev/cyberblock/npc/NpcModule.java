package dev.cyberblock.npc;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.JoinLock;
import dev.cyberblock.net.ServerNet;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

/**
 * Phase 7's NPC interaction layer (megaplan task 7-N1), server half: the content kinds, the hub NPC entity, the role screens opened by
 * a right-click on a figure (sneak to use an item on it instead) or hub NPC (nothing is ever used on one), fixer gig tracking, {@code /cyberblock gig} (your gig)
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
			if (!(player instanceof ServerPlayer p) || player.isSpectator() || player.isShiftKeyDown() || JoinLock.locked(p)) {
				return InteractionResult.PASS;
			}

			// the client consumes the click on a role figure (NpcClient) or hub NPC (HubNpc.interact); a client that still asks
			// again with the off hand gets no second screen, and no item is used on a role NPC either way
			boolean role = hand == InteractionHand.OFF_HAND ? NpcScreens.visit(p, entity) != null : NpcScreens.open(p, entity);
			return role ? InteractionResult.SUCCESS : InteractionResult.PASS;
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Gigs.tick(server);
			HubNpcs.tick(server);
		});
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Cyberblock.LOG.info("npc: {} roles, {} dialogue files, {} contracts, {} ripperdoc catalogues; breach gigs advance on a solved "
				+ "device breach, defeat gigs on any player hit (melee or projectile)", NpcContent.roles().size(), NpcContent.dialogue().size(),
				NpcContent.contracts().size(), NpcContent.catalogues().size());
			Cyberblock.LOG.info("npc: mechanic: Cyberpunk car repair not available yet (no confirmed repair answer from the host's game); "
				+ "fallback active: armour and elytra repair only");
			HubNpcs.load(server);
		});
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> HubNpcs.load(server));
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
			: Component.translatableWithFallback("cyberblock.npc.gig.status", "Gig: %s %s. Gigs done: %s.", c.title(), GigRules.progress(c, gig.progress()), done));
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

		HubNpcs.Figure here = new HubNpcs.Figure("", id, player.getX(), player.getY(), player.getZ(), player.getYRot() + 180.0F, 0, "", null);
		HubNpc npc = HubNpcs.spawn(player.level(), here);
		if (npc == null) {
			return 0;
		}

		Cyberblock.LOG.info("npc: {} spawned a {} NPC ({})", player.getPlainTextName(), id, r.screen());
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.npc.spawn.done", "Spawned a %s", Component.translatable(r.titleKey())));
		return 1;
	}
}
