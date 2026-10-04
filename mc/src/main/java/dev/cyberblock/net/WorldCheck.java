package dev.cyberblock.net;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.SafeSpots;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * The dedicated server's world type check: Cyberblock's world is the cyberblock:nightcity preset (server.properties
 * {@code level-type=cyberblock:nightcity}), whose overworld has the cyberblock:nightcity dimension type (Night City's height range
 * over the void). On any other overworld a host's Night City ground would land in Minecraft terrain, so hosts are refused with
 * {@code cyberblock.worldcheck.refused} and a warning is logged once a minute; an operator can override it with
 * {@code /cyberblock worldcheck off} (saved in the world, cyberblock:worldcheck). On a Night City world the spawn gets its platform
 * (SafeSpots). Singleplayer and LAN are never checked. 26.3 names (javap): {@code Level.dimensionTypeRegistration()},
 * {@code Holder.unwrapKey()}, {@code ResourceKey.identifier()}, {@code MinecraftServer.getRespawnData()}.
 */
public final class WorldCheck {
	static final Identifier NIGHTCITY = Identifier.fromNamespaceAndPath(Cyberblock.ID, "nightcity");
	static final String REFUSED = "Cyberblock: this server's world is not the Night City world type (level-type cyberblock:nightcity), so Cyberpunk "
		+ "hosts are refused. An operator can allow them with /cyberblock worldcheck off.";
	private static final long WARN_EVERY_S = 60;

	/** The operator's override, saved with the world. Server thread. */
	static final class Settings extends SavedData {
		static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.BOOL.optionalFieldOf("off", false).forGetter(s -> s.off)
		).apply(i, Settings::new));
		/** No data fixer: Fabric API's SavedDataStorageMixin reads a type without one as it is (as LoginsData). */
		static final SavedDataType<Settings> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Cyberblock.ID, "worldcheck"), Settings::new, CODEC, null);
		private boolean off;

		Settings() {
			this(false);
		}

		Settings(final boolean off) {
			this.off = off;
		}

		boolean off() {
			return this.off;
		}
	}

	private static @Nullable Settings settings;
	/** Read by the handshake and by the warning timer's thread. */
	private static volatile boolean dedicated, nightCity = true, off;
	private static volatile String overworldType = "";
	private static volatile @Nullable ScheduledExecutorService warnings;

	private WorldCheck() {
	}

	/** Pure: the overworld's dimension type is Night City's. */
	static boolean isNightCity(final @Nullable Identifier type) {
		return NIGHTCITY.equals(type);
	}

	/** Pure: hosts are refused on a dedicated server whose overworld is not Night City, unless an operator turned the check off. */
	static boolean refuses(final boolean dedicated, final boolean nightCity, final boolean off) {
		return dedicated && !nightCity && !off;
	}

	static String warning(final String type) {
		return "worldcheck: overworld is " + type + ", not " + NIGHTCITY + "; hosts refused (op: /cyberblock worldcheck off)";
	}

	/** Common init (Cyberblock.onInitialize): the server events and {@code /cyberblock worldcheck [on|off]}. */
	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(WorldCheck::started);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopped());
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(Commands.literal("cyberblock")
			.then(Commands.literal("worldcheck").requires(Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(c -> command(c.getSource(), null))
				.then(Commands.literal("on").executes(c -> command(c.getSource(), false)))
				.then(Commands.literal("off").executes(c -> command(c.getSource(), true))))));
	}

	/** The handshake asks: a host role must be refused on this server now. Any thread. */
	public static boolean refusesHosts() {
		return refuses(dedicated, nightCity, off);
	}

	/** What a refused host is told (in chat, besides hs_ack.reason). */
	public static Component refusal() {
		return Component.translatableWithFallback("cyberblock.worldcheck.refused", REFUSED);
	}

	private static void started(final MinecraftServer server) {
		ServerLevel overworld = server.overworld();
		Identifier type = overworld.dimensionTypeRegistration().unwrapKey().map(k -> k.identifier()).orElse(null);
		dedicated = server.isDedicatedServer();
		nightCity = isNightCity(type);
		overworldType = type == null ? "an unnamed dimension type" : type.toString();
		settings = overworld.getDataStorage().computeIfAbsent(Settings.TYPE);
		off = settings.off;
		if (!dedicated) {
			return;
		}

		if (nightCity) {
			Cyberblock.LOG.info("worldcheck: overworld is {} (ok)", overworldType);
			SafeSpots.placeSpawnPlatform(overworld);
		} else if (off) {
			Cyberblock.LOG.warn("worldcheck: overworld is {}, not {}; hosts allowed (an operator turned the check off)", overworldType, NIGHTCITY);
		}

		// a timer, not a tick listener: an empty 26.3 dedicated server stops ticking (as ServerAdmin's whitelist warning)
		ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("cyberblock-worldcheck").factory());
		timer.scheduleAtFixedRate(() -> {
			if (refusesHosts()) {
				Cyberblock.LOG.warn(warning(overworldType));
			}
		}, 0, WARN_EVERY_S, TimeUnit.SECONDS);
		warnings = timer;
	}

	private static void stopped() {
		ScheduledExecutorService timer = warnings;
		warnings = null;
		if (timer != null) {
			timer.shutdownNow();
		}

		settings = null;
		dedicated = false;
		nightCity = true;
		off = false;
	}

	/** {@code /cyberblock worldcheck} (turnOff null: the status), {@code ... off} and {@code ... on}. */
	private static int command(final CommandSourceStack source, final @Nullable Boolean turnOff) {
		Settings s = settings;
		if (turnOff != null && s != null && s.off != turnOff) {
			s.off = turnOff;
			s.setDirty();
			off = turnOff;
			Cyberblock.LOG.info("worldcheck: check {} (by {})", turnOff ? "off" : "on", source.getTextName());
			// a host that waited becomes one now; with the check back on, a host on the wrong world type goes back to guest
			for (ServerPlayer p : List.copyOf(source.getServer().getPlayerList().getPlayers())) {
				ServerNet.rehandshake(p);
			}
		}

		String state = !dedicated ? "not checked (singleplayer or LAN)"
			: nightCity ? "overworld is " + overworldType + " (ok)"
			: "overworld is " + overworldType + ", not " + NIGHTCITY + (off ? "; check OFF: hosts allowed" : "; hosts refused");
		source.sendSystemMessage(Component.translatableWithFallback("cyberblock.worldcheck.status", "World check: %s", state));
		return refusesHosts() ? 0 : 1;
	}
}
