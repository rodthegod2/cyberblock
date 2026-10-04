package dev.cyberblock;

import dev.cyberblock.replica.ReplicaBlocks;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Which Minecraft block changes Cyberpunk hears about ({"t":"block"}: it turns them into invisible 1 m collision boxes), and
 * the queue that paces them. Every change counts except the ones this mod makes itself for Night City's ground (barriers),
 * whoever caused the rest: the player placing or breaking, explosions, commands, pistons, falling blocks.
 */
public final class BlockEvents {
	/** Who made a block change: the host's own ground (barriers, not news to the host) or anything else. */
	public enum Cause {
		WORLD, HOST_GROUND
	}

	public enum Op {
		ADD, REMOVE
	}

	/**
	 * At most this many events leave per server tick: 16 x 20 = 320 a second. The host keeps block changes per cell and never
	 * drops one (Cyberblock.dll's EventQueue), and its script (Events.reds) applies up to 512 every 100 ms; this rate only keeps
	 * a burst (a big explosion, /fill) from stalling the link, spread over ticks.
	 */
	static final int PER_TICK = 16;

	private BlockEvents() {
	}

	/**
	 * True when a block in this state is something the host should know about: solid for entities, and not Night City's own
	 * barrier or replica block (megaplan 3-R1: the replica is the hosts' own ground, never a player's block, whoever breaks it). An
	 * open door or trapdoor still has a thin collision shape, but the host's boxes are 1 m: it must be passable.
	 */
	public static boolean shouldReport(final BlockState state, final Cause cause) {
		if (cause != Cause.WORLD || state.isAir() || state.is(Blocks.BARRIER) || ReplicaBlocks.is(state)) {
			return false;
		}

		boolean opening = state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock;
		return !(opening && state.getValue(BlockStateProperties.OPEN))
			&& !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
	}

	/** The event a change from oldState to newState needs, or null when the host's collision does not change. */
	public static Op change(final BlockState oldState, final BlockState newState, final Cause cause) {
		boolean was = shouldReport(oldState, Cause.WORLD);
		boolean is = shouldReport(newState, Cause.WORLD);
		if (cause == Cause.HOST_GROUND || was == is) {
			return null;
		}

		return is ? Op.ADD : Op.REMOVE;
	}

	/** Pending events, oldest first, one per cell (a later change of a cell replaces the earlier one). Server thread only. */
	static final class Outbox {
		private final Map<Long, Op> pending = new LinkedHashMap<>();

		void put(final long pos, final Op op) {
			this.pending.put(pos, op);
		}

		int size() {
			return this.pending.size();
		}

		void clear() {
			this.pending.clear();
		}

		/** Hands over (and forgets) up to max events, oldest first. */
		void drain(final int max, final BiConsumer<Long, Op> sink) {
			Iterator<Map.Entry<Long, Op>> it = this.pending.entrySet().iterator();
			for (int n = 0; n < max && it.hasNext(); n++) {
				Map.Entry<Long, Op> e = it.next();
				sink.accept(e.getKey(), e.getValue());
				it.remove();
			}
		}
	}
}
