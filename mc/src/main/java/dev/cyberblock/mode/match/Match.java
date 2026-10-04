package dev.cyberblock.mode.match;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One match (megaplan 8-M1): created by a Cyberpunk-linked player at the Match Terminal with a mode, a zone, a time limit, PvP and
 * an invite rule; players join with a role; teams exist only for the match. Plain state with no Minecraft objects, so the rules are
 * tested without a server; {@link Matches} drives it on the server thread. Times are wall-clock milliseconds.
 */
public final class Match {
	public enum State {
		LOBBY, RUNNING, ENDED;

		public String key() {
			return this.name().toLowerCase(Locale.ROOT);
		}
	}

	/**
	 * What a member does: TEAMMATE fights on a team (every linked player is one: V fights), HACKER runs ops for a team from the Van
	 * (8-V1), SPECTATOR watches (8-T3), OVERLORD spawns trouble (8-O1, at most {@link MatchRules#MAX_OVERLORDS}).
	 */
	public enum Role {
		TEAMMATE, HACKER, SPECTATOR, OVERLORD;

		public String key() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		/** On a team (teammates and hackers); spectators and overlords are not. */
		public boolean onTeam() {
			return this == TEAMMATE || this == HACKER;
		}

		public static @Nullable Role of(final String key) {
			for (Role r : values()) {
				if (r.key().equals(key)) {
					return r;
				}
			}

			return null;
		}
	}

	public record Member(UUID uuid, String name, Role role, int team, boolean linked) {
	}

	/** The match area: a circle in the overworld (Night City) and the district it was picked for ("" a free pick). */
	public record Zone(double x, double z, double r, String district) {
		public boolean inside(final double px, final double pz) {
			double dx = px - this.x, dz = pz - this.z;
			return dx * dx + dz * dz <= this.r * this.r;
		}
	}

	/** A team's spawn point (the Holo-Table offers it to that team only). */
	public record Base(String name, double x, double y, double z, int team) {
	}

	public final String id;
	public final String modeId;
	public final MatchMode mode;
	public final UUID creator;
	public final String creatorName;
	public final Zone zone;
	public final boolean pvp;
	public final boolean inviteOnly;
	/** Seconds. */
	public final int timeLimit;
	public final long createdMs;
	State state = State.LOBBY;
	final Map<UUID, Member> members = new LinkedHashMap<>();
	final Set<UUID> invited = new LinkedHashSet<>();
	final int[] scores;
	final List<Capture.Point> points = new ArrayList<>();
	final List<Base> bases = new ArrayList<>();
	/** The PvP toggle each teammate had before the match turned it on, restored when the match ends. */
	final Map<UUID, Boolean> pvpBefore = new LinkedHashMap<>();
	/** Teammates defeated in a mode without respawn ({@code respawn_s} 0): out until the match ends. */
	final Set<UUID> out = new LinkedHashSet<>();
	long startedMs, endsMs, endedMs;
	int winner = -1;

	public Match(final String id, final String modeId, final MatchMode mode, final UUID creator, final String creatorName, final Zone zone,
		final boolean pvp, final boolean inviteOnly, final int timeLimit, final long now) {
		this.id = id;
		this.modeId = modeId;
		this.mode = mode;
		this.creator = creator;
		this.creatorName = creatorName;
		this.zone = zone;
		this.pvp = pvp;
		this.inviteOnly = inviteOnly;
		this.timeLimit = Math.clamp(timeLimit, MatchRules.MIN_TIME, MatchRules.MAX_TIME);
		this.createdMs = now;
		this.scores = new int[mode.teams().size()];
	}

	public State state() {
		return this.state;
	}

	public Collection<Member> members() {
		return List.copyOf(this.members.values());
	}

	public @Nullable Member member(final UUID uuid) {
		return this.members.get(uuid);
	}

	public int[] scores() {
		return this.scores.clone();
	}

	public List<Capture.Point> points() {
		return List.copyOf(this.points);
	}

	public List<Base> bases() {
		return List.copyOf(this.bases);
	}

	public int winner() {
		return this.winner;
	}

	public boolean invited(final UUID uuid) {
		return uuid.equals(this.creator) || this.invited.contains(uuid);
	}

	/** Members on each team (teammates and hackers). */
	public int[] teamSizes() {
		int[] sizes = new int[this.scores.length];
		for (Member m : this.members.values()) {
			if (m.role().onTeam() && m.team() >= 0 && m.team() < sizes.length) {
				sizes[m.team()]++;
			}
		}

		return sizes;
	}

	public int count(final Role role) {
		return (int) this.members.values().stream().filter(m -> m.role() == role).count();
	}

	/** Seconds left of a running match (the full limit in the lobby, 0 when ended). */
	public int secondsLeft(final long now) {
		return switch (this.state) {
			case LOBBY -> this.timeLimit;
			case RUNNING -> (int) Math.max(0L, (this.endsMs - now + 999L) / 1000L);
			case ENDED -> 0;
		};
	}

	/** The team {@code uuid} would get as {@code role} (its own seat not counted): -1 for no team, or when every team is full. */
	public int teamFor(final UUID uuid, final Role role, final int preferredTeam) {
		if (!role.onTeam()) {
			return -1;
		}

		int[] sizes = this.teamSizes();
		Member was = this.members.get(uuid);
		if (was != null && was.role().onTeam() && was.team() >= 0 && was.team() < sizes.length) {
			sizes[was.team()]--;
		}

		return MatchRules.team(sizes, preferredTeam, this.mode.minTeams(), this.mode.teamSizeMax());
	}

	/** The teams the match shows: the first {@code teams.min}, and every later one up to the last that has a member or a score. */
	public int shownTeams() {
		int n = Math.min(this.mode.minTeams(), this.scores.length);
		int[] sizes = this.teamSizes();
		for (int t = n; t < this.scores.length; t++) {
			if (sizes[t] > 0 || this.scores[t] > 0) {
				n = t + 1;
			}
		}

		return n;
	}

	public boolean isOut(final UUID uuid) {
		return this.out.contains(uuid);
	}

	/**
	 * Adds (or moves) a member; its team is the preferred one when valid and not full, else the smallest of the open teams
	 * ({@link MatchRules#team}). Callers check {@link MatchRules#joinRefusal} first (it refuses when every team is full). Returns the member.
	 */
	public Member join(final UUID uuid, final String name, final Role role, final boolean linked, final int preferredTeam) {
		int team = this.teamFor(uuid, role, preferredTeam);
		this.members.remove(uuid);
		Member m = new Member(uuid, name, role, team, linked);
		this.members.put(uuid, m);
		return m;
	}

	public @Nullable Member leave(final UUID uuid) {
		return this.members.remove(uuid);
	}

	public void invite(final UUID uuid) {
		this.invited.add(uuid);
	}

	void start(final long now) {
		this.state = State.RUNNING;
		this.startedMs = now;
		this.endsMs = now + this.timeLimit * 1000L;
	}

	void end(final long now) {
		this.state = State.ENDED;
		this.endedMs = now;
		this.winner = MatchRules.winner(this.scores);
	}

	/** Adds points to a team (kills or held capture points); ignores a team the match does not have. */
	void score(final int team, final int points) {
		if (team >= 0 && team < this.scores.length) {
			this.scores[team] += points;
		}
	}

	/** Same match, same team (both on a team). */
	public boolean teammates(final UUID a, final UUID b) {
		Member ma = this.members.get(a), mb = this.members.get(b);
		return ma != null && mb != null && ma.role().onTeam() && mb.role().onTeam() && ma.team() == mb.team();
	}
}
