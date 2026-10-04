package dev.cyberblock.hub;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/**
 * The hub's leaderboards (megaplan 7-H1): plain scoreboard objectives (dummy criteria) that the board blocks show, so a datapack or an
 * operator can also feed them with {@code /scoreboard players add}. Match wins come from Phase 8's matches, gigs done from 7N's fixers and
 * chaos from Phase 8's Server Heat, through {@link #add}. A board shows the top {@link #SHOWN}; a right-click lists the top
 * {@link #LISTED} in chat. Server thread only.
 */
public final class Leaderboards {
	public static final String MATCH_WINS = "cb_wins";
	public static final String GIGS_DONE = "cb_gigs";
	public static final String CHAOS = "cb_chaos";
	static final int SHOWN = 5;
	static final int LISTED = 10;

	/** One line of a board: the score holder's name and its score. */
	public record Row(String name, int value) {
		static final Codec<Row> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("name").forGetter(Row::name),
			Codec.INT.fieldOf("value").forGetter(Row::value)
		).apply(i, Row::new));
	}

	private Leaderboards() {
	}

	/** Adds n to the player's score on a leaderboard objective (made when missing). */
	public static void add(final ServerPlayer player, final String objective, final int n) {
		ServerScoreboard board = player.level().getServer().getScoreboard();
		board.getOrCreatePlayerScore(player, objective(board, objective, "")).add(n);
	}

	/** The objective, made as a dummy one titled by the lang key (or the built-in English) when it does not exist yet. */
	static Objective objective(final ServerScoreboard board, final String name, final String titleKey) {
		Objective o = board.getObjective(name);
		return o != null ? o : board.addObjective(name, ObjectiveCriteria.DUMMY, title(name, titleKey), ObjectiveCriteria.RenderType.INTEGER, true, null);
	}

	/** A board's title: its lang key, with the English of the three plan objectives (or the objective's name) as the fallback. */
	public static Component title(final String objective, final String titleKey) {
		String english = switch (objective) {
			case MATCH_WINS -> "Match Wins";
			case GIGS_DONE -> "Gigs Done";
			case CHAOS -> "Chaos";
			default -> objective;
		};
		return titleKey.isEmpty() ? Component.literal(english) : Component.translatableWithFallback(titleKey, english);
	}

	/** The visible scores, best first (equal scores by name), at most n. */
	static List<Row> top(final Collection<PlayerScoreEntry> scores, final int n) {
		return rank(scores.stream().filter(s -> !s.isHidden()).map(s -> new Row(s.owner(), s.value())).toList(), n);
	}

	/** Best first, equal scores by name (ignoring case), at most n. */
	static List<Row> rank(final List<Row> rows, final int n) {
		return rows.stream().sorted(Comparator.comparingInt(Row::value).reversed().thenComparing(r -> r.name().toLowerCase(Locale.ROOT))).limit(n).toList();
	}
}
