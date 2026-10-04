package dev.cyberblock.hack.breach;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.net.DevCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Dev-only Breach tools (with {@code -Dcyberblock.devtools=true}, ops only; protocol.md "Test commands", mpcheck's {@code breach}
 * scenario and the screenshots), acting as the source player through the same paths a client takes:
 * <ul>
 * <li>{@code /cyberblock test breach <x> <y> <z> solve|fail}: opens the Breach of the Net Port at that position (as a right-click) and
 * plays it: {@code solve} picks the server's planted path (every daemon uploaded), {@code fail} fills the buffer with picks that upload
 * nothing</li>
 * <li>{@code /cyberblock test breach practice <security 1-3>} and {@code ... breach short}: a practice breach to look at (no device);
 * {@code ... breach step}: the next pick of the planted path; {@code ... breach abort}</li>
 * </ul>
 */
final class BreachCommands {
	private BreachCommands() {
	}

	static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		if (!DevCommands.enabled()) {
			return;
		}

		dispatcher.register(Commands.literal("cyberblock").then(Commands.literal("test").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("breach")
				.then(Commands.literal("practice").then(Commands.argument("security", IntegerArgumentType.integer(1, 3))
					.executes(c -> practice(c, IntegerArgumentType.getInteger(c, "security"), false))))
				.then(Commands.literal("short").executes(c -> practice(c, 1, true)))
				.then(Commands.literal("step").executes(BreachCommands::step))
				.then(Commands.literal("abort").executes(c -> {
					ServerPlayer p = c.getSource().getPlayerOrException();
					Breach.abort(p.getUUID());
					return say(c.getSource(), "test: breach abort by " + p.getPlainTextName());
				}))
				.then(Commands.argument("x", IntegerArgumentType.integer()).then(Commands.argument("y", IntegerArgumentType.integer())
					.then(Commands.argument("z", IntegerArgumentType.integer())
						.then(Commands.literal("solve").executes(c -> play(c, true)))
						.then(Commands.literal("fail").executes(c -> play(c, false)))))))));
	}

	private static int practice(final CommandContext<CommandSourceStack> c, final int security, final boolean shortBreach) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		boolean opened = Breach.practice(p, security, shortBreach, null);
		return say(c.getSource(), "test: breach practice " + (shortBreach ? "short" : "security " + security) + " for " + p.getPlainTextName() + " -> "
			+ (opened ? "opened" : "busy"));
	}

	/** The next pick of the planted path (while the picks so far follow it). */
	private static int step(final CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		BreachGame g = Breach.game(p);
		int k = g == null ? -1 : g.picks().length;
		boolean ok = g != null && k < g.puzzle().solution().length && Breach.pick(p, g.puzzle().solution()[k]);
		return say(c.getSource(), "test: breach step by " + p.getPlainTextName() + " -> " + (ok ? "picked " + g.puzzle().solution()[k] : "no pick"));
	}

	private static int play(final CommandContext<CommandSourceStack> c, final boolean solve) throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		BlockPos pos = new BlockPos(IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z"));
		if (Breach.game(p) == null) {
			Breach.netPort(p, pos);
		}

		BreachGame g = Breach.game(p);
		if (g == null || g.picks().length > 0) {
			c.getSource().sendFailure(Component.literal("test: breach at " + pos.toShortString() + " did not open a fresh breach for " + p.getPlainTextName()));
			return 0;
		}

		int[] path = solve ? g.puzzle().solution() : clean(g.puzzle());
		int picked = 0;
		while (path != null && picked < path.length && !g.ended() && Breach.pick(p, path[picked])) {
			picked++;
		}

		if (!g.ended()) {
			Breach.abort(p.getUUID());
		}

		BreachResults.Outcome o = BreachResults.Outcome.of(g);
		return say(c.getSource(), String.format(Locale.ROOT, "test: breach %s at %s by %s: %dx%d, %d picks -> %s %s strength %d", solve ? "solve" : "fail",
			pos.toShortString(), p.getPlainTextName(), g.puzzle().size(), g.puzzle().size(), picked, o.state(), o.ids(), o.strength()));
	}

	/** A legal pick sequence that fills the buffer and uploads no daemon (null when the grid has none). */
	static int @Nullable [] clean(final BreachPuzzle p) {
		List<Integer> picks = new ArrayList<>();
		return search(p, picks, new boolean[p.grid().length]) ? picks.stream().mapToInt(Integer::intValue).toArray() : null;
	}

	private static boolean search(final BreachPuzzle p, final List<Integer> picks, final boolean[] used) {
		int[] codes = picks.stream().mapToInt(p::code).toArray();
		if (p.daemons().stream().anyMatch(d -> BreachRules.uploaded(codes, codes.length, d.seq()))) {
			return false;
		}

		if (picks.size() == p.buffer()) {
			return true;
		}

		int[] done = picks.stream().mapToInt(Integer::intValue).toArray();
		for (int cell = 0; cell < used.length; cell++) {
			if (!used[cell] && BreachRules.allowed(p.size(), done, done.length, cell)) {
				used[cell] = true;
				picks.add(cell);
				if (search(p, picks, used)) {
					return true;
				}

				picks.removeLast();
				used[cell] = false;
			}
		}

		return false;
	}

	private static int say(final CommandSourceStack source, final String line) {
		Cyberblock.LOG.info(line);
		source.sendSuccess(() -> Component.literal(line), false);
		return 1;
	}
}
