package dev.cyberblock;

import net.minecraft.world.level.GameType;
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

	/** "Creative can erase the replica permanently" of the running world (server thread writes, any thread reads). */
	private static volatile boolean creativeErases;

	private OutlineRules() {
	}

	public static boolean creativeErases() {
		return creativeErases;
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
	 * No explosion removes these (ServerExplosionMixin). Vanilla gives barriers a blast resistance of 3,600,000, but an explosion
	 * with its own immune-block list (data-driven ones) ignores resistance.
	 */
	public static boolean sparedByExplosions(final BlockState state) {
		return state.is(Blocks.BARRIER);
	}
}
