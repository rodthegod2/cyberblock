package dev.cyberblock.hub;

import com.mojang.brigadier.CommandDispatcher;
import dev.cyberblock.Cyberblock;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * The Afterlife hub (megaplan 7-H1), its own entrypoint: the hub blocks, {@code /cyberblock hub [leave|status|rebuild]}, the build
 * at the first start (built once), entering and leaving ({@link HubTravel}) and the hub's protection (outside creative nobody breaks
 * or places blocks there). Open with no host online: it is plain Minecraft. Log line at every start: {@code hub: the Afterlife is
 * open (built from <layout>)} or {@code hub: the Afterlife is off (<why>)}.
 */
public final class HubMod implements ModInitializer {
	@Override
	public void onInitialize() {
		HubBlocks.register();
		HubTravel.init();
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> commands(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(HubMod::started);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> HubTravel.stopping());
		ServerTickEvents.END_SERVER_TICK.register(HubTravel::tick);
		// besides mayBuild (HubTravel.grounding, which covers the rest of what vanilla checks, block placing included): no breaking, and
		// none of the refused items on a block (a hub block's or the Holo-Table's own use still works) or in the air
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> !Afterlife.is(level) || player.isCreative());
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> grounded(player, level)
			&& HubRules.refusedOnBlock(held(player, hand), HubBlocks.usable(level.getBlockState(hit.getBlockPos())))
			? InteractionResult.FAIL : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) -> grounded(player, level) && HubRules.refusedItem(held(player, hand))
			? InteractionResult.FAIL : InteractionResult.PASS);
	}

	private static boolean grounded(final Player player, final Level level) {
		return Afterlife.is(level) && HubRules.grounded(true, player.isCreative());
	}

	private static Class<? extends Item> held(final Player player, final InteractionHand hand) {
		return player.getItemInHand(hand).getItem().getClass();
	}

	/** Builds the hub once, the first time the Afterlife exists; one line says whether the hub is open. */
	private static void started(final MinecraftServer server) {
		ServerLevel hub = Afterlife.level(server);
		if (hub == null) {
			Cyberblock.LOG.warn("hub: the Afterlife is off (the dimension {} is missing: the mod's datapack files were removed)", Afterlife.LEVEL.identifier());
			return;
		}

		HubData data = HubData.get(server);
		HubBuilder.Source source = HubBuilder.load(server);
		if (data.built().isEmpty()) {
			HubBuilder.build(hub, source, data);
		} else if (data.built().equals(source.from())) {
			HubBuilder.console(hub, source.layout(), true); // a station added after this hub was built (the Overlord Console)
		}

		Cyberblock.LOG.info("hub: the Afterlife is open (built from {})", data.built());
	}

	private static void commands(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("hub")
			.executes(c -> HubTravel.toggle(c.getSource().getPlayerOrException()))
			.then(Commands.literal("leave").executes(c -> HubTravel.leave(c.getSource().getPlayerOrException())))
			.then(Commands.literal("status").executes(c -> status(c.getSource())))
			.then(Commands.literal("rebuild").requires(HubMod::opOrOwner).executes(c -> rebuild(c.getSource())))));
	}

	private static int status(final CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		ServerLevel hub = Afterlife.level(server);
		if (hub == null) {
			source.sendSystemMessage(Component.translatableWithFallback("cyberblock.hub.missing", "The Afterlife is not open on this server"));
			return 0;
		}

		long inside = server.getPlayerList().getPlayers().stream().filter(HubTravel::inHub).count();
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.hub.status", "The Afterlife is open (built from %s); players inside: %s",
			HubData.get(server).built(), inside));
		return 1;
	}

	/** Builds the hub again from the layout of the current data (players inside stay; blocks outside the layout stay too). */
	private static int rebuild(final CommandSourceStack source) {
		ServerLevel hub = Afterlife.level(source.getServer());
		if (hub == null) {
			source.sendFailure(Component.translatableWithFallback("cyberblock.hub.missing", "The Afterlife is not open on this server"));
			return 0;
		}

		int cells = HubBuilder.build(hub, HubBuilder.load(source.getServer()), HubData.get(source.getServer()));
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.hub.rebuilt", "The Afterlife was built again (%s cells)", cells));
		return 1;
	}

	/** Operators (permission level 2) and the singleplayer/LAN owner. */
	private static boolean opOrOwner(final CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return Commands.LEVEL_GAMEMASTERS.check(source.permissions()) || player != null && source.getServer().isSingleplayerOwner(player.nameAndId());
	}
}
