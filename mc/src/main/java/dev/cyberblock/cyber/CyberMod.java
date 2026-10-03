package dev.cyberblock.cyber;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
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
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import org.jspecify.annotations.Nullable;

/** Layer 6 (plan 2026-10-03, track C; plan retest2 package C), server half: cyber items, RAM, perks, cyberware, the starter kit. */
public class CyberMod implements ModInitializer {
	@Override
	public void onInitialize() {
		CyberData.register();
		CyberItems.register();
		ServerTickEvents.END_SERVER_TICK.register(CyberServer::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			Hacking.clear();
			PerkEffects.reset();
			CyberwareEffects.reset(server);
		});
		// the host usually says hello at the title screen, before any world: so `perks` and `cyberware` also go out when the
		// player joins (handler.player: JOIN fires before PlayerList adds him, so localPlayer() is still null), and a closed
		// world's ranks and implants are dropped on the Cyberpunk side when he leaves. The perk owners' implant hand-out
		// (Ripperdoc.migrate) runs on his first server tick, once he is in the world.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			PerkShop.sendPerks(handler.player);
			Ripperdoc.sendCyberware(handler.player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			PerkEffects.reset();
			CyberwareEffects.reset(server);
			Cyberblock.events.accept(CyberWire.perks(Map.of()));
			Ripperdoc.disconnected();
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			PerkEffects.apply(newPlayer);
			CyberwareEffects.apply(newPlayer);
			Ripperdoc.sendCyberware(newPlayer);
		});
		// Steve's attacks: the arms implants' particles and sounds, and the camo breaks
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (player instanceof ServerPlayer serverPlayer && !level.isClientSide()) {
				CyberwareEffects.onAttack(serverPlayer, entity);
			}

			return InteractionResult.PASS;
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			ItemStack stack = player.getItemInHand(hand);
			if (player instanceof ServerPlayer serverPlayer) {
				// vanilla's ServerPlayerGameMode.useItem ignores an item on cooldown without a word (this callback runs before that test)
				if (stack.getItem() instanceof QuickhackItem && player.getCooldowns().isOnCooldown(stack)) {
					serverPlayer.sendOverlayMessage(Component.translatable("cyberblock.hack.cooldown"));
				}

				// Kerenzikov: drawing a bow, crossbow or trident in the air
				if (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem || stack.getItem() instanceof TridentItem) {
					CyberwareEffects.kerenzikov(serverPlayer);
				}
			}

			return InteractionResult.PASS;
		});
		// /cyberblock kit and /cyberblock perks free [on|off], for everyone (permission level 0); Brigadier merges these
		// `cyberblock` literals with the world track's
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
			Commands.literal("cyberblock")
				.then(Commands.literal("kit").executes(command -> {
					Kit.give(command.getSource().getPlayerOrException());
					command.getSource().sendSuccess(() -> Component.translatable("cyberblock.kit.given"), false);
					return 1;
				}))
				.then(Commands.literal("perks").then(Commands.literal("free")
					.executes(command -> freePerks(command.getSource(), null))
					.then(Commands.literal("on").executes(command -> freePerks(command.getSource(), true)))
					.then(Commands.literal("off").executes(command -> freePerks(command.getSource(), false)))))));
		Bridge.perkRank = id -> {
			ServerPlayer player = localPlayer();
			return player == null ? 0 : CyberData.rank(player, id);
		};
		Cyberblock.LOG.info("cyberblock cyber loaded");
	}

	/**
	 * `/cyberblock perks free [on|off]` (plan retest2 section 1.9): sets the player's FREE_PERKS (null: only reports it) and says
	 * whether perks are free now (creative always is).
	 */
	private static int freePerks(final CommandSourceStack source, final @Nullable Boolean on) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (on != null) {
			player.setAttached(CyberData.FREE_PERKS, on);
		}

		Component state = player.getAttachedOrElse(CyberData.FREE_PERKS, false) ? Component.translatable("cyberblock.perks.free.on")
			: player.isCreative() ? Component.translatable("cyberblock.perks.free.creative") : Component.translatable("cyberblock.perks.free.off");
		source.sendSuccess(() -> state, false);
		return 1;
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
