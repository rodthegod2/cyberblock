package dev.cyberblock;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cyberblock.replica.ReplicaBlocks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Who may break the outline blocks (plan Q-B4): the invisible barriers that stand for Night City's ground and walls. Decided on
 * the server thread from the breaking ServerPlayer's game mode, never from a client flag. Vanilla already keeps a barrier from
 * survival and adventure players (destroy time -1); creative breaks it at once. What happens after a creative break is the
 * decision: the block comes back (SNAP_BACK, OutlineGuard) or stays broken until the host's probes report the cell again (KEEP).
 */
public final class OutlineRules {
	/** What was broken: Night City ground, a projectile's 3 s barrier (WorldBridge.projectileCells), or a replica block (Phase 3). */
	public enum Kind {
		OUTLINE_BARRIER, PROJECTILE_BARRIER, REPLICA
	}

	public enum Decision {
		/** The break does not happen. */
		DENY,
		/** The block is put back after OutlineGuard.SNAP_BACK_TICKS, or at once when the probes report the cell again. */
		SNAP_BACK,
		/** The block stays broken until the probes report the cell again. */
		KEEP
	}

	/**
	 * The world's Cyberblock settings (saved data cyberblock:settings, plan Q-B5): "Creative can erase the replica permanently",
	 * default off. Phase 1 moves the default into config/cyberblock/server.json (section 1.13). Server thread only.
	 */
	public static final class Settings extends SavedData {
		static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.BOOL.optionalFieldOf("creativeErasesReplica", false).forGetter(s -> s.creativeErases)
		).apply(i, Settings::new));
		/** No data fixer: Fabric API's SavedDataStorageMixin reads a type without one as it is (as PlacedBlocks). */
		static final SavedDataType<Settings> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Cyberblock.ID, "settings"), Settings::new, CODEC, null);
		private boolean creativeErases;

		Settings() {
			this(false);
		}

		Settings(final boolean creativeErases) {
			this.creativeErases = creativeErases;
		}
	}

	/** The running world's settings (server thread; null while no world runs). */
	private static Settings settings;
	/** Its "Creative can erase the replica permanently", for any thread (the settings screen of the integrated server's owner). */
	private static volatile boolean creativeErases;

	private OutlineRules() {
	}

	/** Server thread, when the world has started. */
	static void attach(final MinecraftServer server) {
		settings = server.overworld().getDataStorage().computeIfAbsent(Settings.TYPE);
		creativeErases = settings.creativeErases;
	}

	/** Server thread, when the world stops. */
	static void detach() {
		settings = null;
		creativeErases = false;
	}

	public static boolean creativeErases() {
		return creativeErases;
	}

	/** Server thread: changes the running world's setting (no world: nothing to change). True when it changed. */
	static boolean setCreativeErases(final boolean on) {
		Settings s = settings;
		if (s == null || s.creativeErases == on) {
			return false;
		}

		s.creativeErases = on;
		s.setDirty();
		creativeErases = on;
		return true;
	}

	/**
	 * Who may change the setting: operators (permission level 2) and the singleplayer owner (the player whose world it is, also when
	 * it is opened to LAN). Phase 1 adds active hosts.
	 */
	static boolean mayChange(final CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return Commands.LEVEL_GAMEMASTERS.check(source.permissions()) || player != null && source.getServer().isSingleplayerOwner(player.nameAndId());
	}

	/**
	 * Survival, adventure and spectator: DENY. Creative: SNAP_BACK, or KEEP while "Creative can erase the replica permanently" is on.
	 * Every kind follows the same table in Phase Q (the kind is in the signature for the replica blocks of Phase 3).
	 */
	public static Decision decide(final GameType mode, final boolean creativeErases, final Kind kind) {
		return switch (mode) {
			case CREATIVE -> creativeErases ? Decision.KEEP : Decision.SNAP_BACK;
			case SURVIVAL, ADVENTURE, SPECTATOR -> Decision.DENY;
		};
	}

	/**
	 * No explosion removes these (ServerExplosionMixin): barriers and the replica blocks (Night City does not break, megaplan 3-R1).
	 * Vanilla gives barriers a blast resistance of 3,600,000, but an explosion with its own immune-block list (data-driven ones)
	 * ignores resistance.
	 */
	public static boolean sparedByExplosions(final BlockState state) {
		return state.is(Blocks.BARRIER) || ReplicaBlocks.is(state);
	}
}
