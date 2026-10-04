package dev.cyberblock.client.mode.match;

import com.google.gson.JsonObject;
import dev.cyberblock.client.HostState;
import dev.cyberblock.client.net.Roster;
import dev.cyberblock.mode.match.MatchRules;
import dev.cyberblock.mode.match.MatchWire;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The Match Terminal's screen (megaplan 8-M1), vanilla widgets on the server's {@code ui match} state. Left: every match on the
 * server with a Join button and the role to join as (a linked player always joins as a teammate: V fights; a Minecraft-only player
 * picks teammate, hacker, spectator or overlord). Right, outside a match: the create form, for Cyberpunk-linked players only (others
 * see it greyed with the reason): mode, zone (a district from the landmarks, or a pick on the Atlas), time limit, PvP and invite
 * only. Right, in a match: its teams and members, Start and End (its creator), Leave and Invite. Bottom: the PvP button. Every action
 * is a request; the server answers with a new state (or a refusal on the action bar).
 */
final class MatchScreen extends Screen {
	private static final int[] MINUTES = {5, 10, 15, 20, 30, 45, 60};
	private static final int ROW = 22;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int DIM = 0xFFAAAAAA;
	private static final int WARN = 0xFFFFAA00;
	private static final int MINE = 0xFFFFFF55;
	// the create form keeps its choices while the screen is closed and reopened (and across the Atlas zone pick)
	private static int mode;
	private static int zone;
	private static double[] custom;
	private static int minutes = 1;
	private static boolean pvp = true;
	private static boolean invite;
	private static int role;
	private static int inviteName;
	private final List<Line> lines = new ArrayList<>();

	/** A line of text the screen draws over its widgets' background. */
	private record Line(String text, int x, int y, int colour) {
	}

	MatchScreen() {
		super(Component.translatableWithFallback("cyberblock.match.title", "Match Terminal"));
	}

	void refresh() {
		this.rebuildWidgets();
	}

	private static String key(final String path) {
		return "cyberblock.match.screen." + path;
	}

	private static Component text(final String path, final String english, final Object... args) {
		return Component.translatableWithFallback(key(path), english, args);
	}

	@Override
	protected void init() {
		this.lines.clear();
		MatchWire.State s = MatchClient.state();
		int half = this.width / 2;
		this.addRenderableWidget(MatchClient.pvpButton(8, this.height - 26, 90));
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> this.onClose()).bounds(this.width - 108, this.height - 26, 100, 20).build());
		if (s == null) {
			return;
		}

		this.list(s, 8, 24, half - 12);
		MatchWire.View mine = s.mine();
		if (mine != null) {
			this.mine(s, mine, half + 4, 24, this.width - half - 12);
		} else {
			this.create(s, half + 4, 24, this.width - half - 12);
		}
	}

	private boolean linked() {
		return this.minecraft.player != null && Roster.linked(this.minecraft.player.getUUID());
	}

	/** Left: the role to join as, then one row per match. */
	private void list(final MatchWire.State s, final int x, final int y0, final int w) {
		List<String> roles = this.linked() ? List.of("teammate") : List.of("teammate", "hacker", "spectator", "overlord");
		role = Math.min(role, roles.size() - 1);
		CycleButton<String> as = CycleButton.builder((String r) -> text("role." + r, r.substring(0, 1).toUpperCase(Locale.ROOT) + r.substring(1)),
			roles.get(role)).withValues(roles).create(x, y0, w, 20, text("join_as", "Join as"), (b, v) -> role = roles.indexOf(v));
		if (this.linked()) {
			as.active = false;
			as.setTooltip(Tooltip.create(text("linked_fights", "Your V fights: linked players join as teammates")));
		}

		this.addRenderableWidget(as);
		int y = y0 + 26, rows = Math.max(1, (this.height - 60 - y) / ROW);
		if (s.matches().isEmpty()) {
			this.lines.add(new Line(text("none", "No matches yet").getString(), x, y + 6, DIM));
		}

		for (MatchWire.View v : s.matches().subList(0, Math.min(rows, s.matches().size()))) {
			int players = v.teams().stream().mapToInt(t -> t.members().size()).sum() + v.others().size();
			String line = v.name() + (v.district().isEmpty() ? "" : " (" + v.district() + ")") + " - " + text("state." + v.state(), v.state()).getString()
				+ " - " + players;
			this.lines.add(new Line(this.font.plainSubstrByWidth(line, w - 56), x, y + 6, v.id().equals(s.you()) ? MINE : TEXT));
			if (!v.id().equals(s.you()) && !v.state().equals("ended")) {
				Button join = Button.builder(text("join", "Join"), b -> {
					JsonObject o = new JsonObject();
					o.addProperty("op", "join");
					o.addProperty("id", v.id());
					o.addProperty("role", roles.get(role));
					MatchClient.act(o.toString());
				}).bounds(x + w - 50, y, 50, 20).build();
				join.active = s.you().isEmpty() && (!v.invite() || v.invited());
				if (v.invite() && !v.invited()) {
					join.setTooltip(Tooltip.create(Component.translatableWithFallback("cyberblock.match.refused.invite_only", "That match is invite only")));
				}

				this.addRenderableWidget(join);
			}

			y += ROW;
		}
	}

	/** Right, outside a match: the create form. */
	private void create(final MatchWire.State s, final int x, final int y0, final int w) {
		if (s.modes().isEmpty()) {
			return;
		}

		mode = Math.min(mode, s.modes().size() - 1);
		int y = y0;
		List<Integer> modeIdx = indices(s.modes().size());
		CycleButton<Integer> modeButton = CycleButton.builder((Integer i) -> Component.literal(s.modes().get(i).name()), mode).withValues(modeIdx)
			.create(x, y, w, 20, text("mode", "Mode"), (b, v) -> {
				mode = v;
				this.rebuildWidgets();
			});
		modeButton.setTooltip(Tooltip.create(Component.literal(s.modes().get(mode).description())));
		this.addRenderableWidget(modeButton);
		y += 24;
		// zone choices: every district, then "a spot on the map" (the last pick, or the Atlas)
		int districts = s.districts().size();
		zone = Math.min(zone, districts);
		List<Integer> zoneIdx = indices(districts + 1);
		this.addRenderableWidget(CycleButton.builder((Integer i) -> i < districts ? Component.literal(s.districts().get(i).name())
			: custom == null ? text("zone.map", "A spot on the map...") : text("zone.custom", "Map spot %s, %s", Math.round(custom[0]), Math.round(custom[1])),
			zone).withValues(zoneIdx).create(x, y, w - 46, 20, text("zone", "Zone"), (b, v) -> zone = v));
		this.addRenderableWidget(Button.builder(text("map", "Map"), b -> this.pick(s)).bounds(x + w - 44, y, 44, 20).build());
		y += 24;
		List<Integer> timeIdx = indices(MINUTES.length);
		minutes = Math.min(minutes, MINUTES.length - 1);
		this.addRenderableWidget(CycleButton.builder((Integer i) -> text("minutes", "%s min", MINUTES[i]), minutes).withValues(timeIdx)
			.create(x, y, w, 20, text("time", "Time limit"), (b, v) -> minutes = v));
		y += 24;
		this.addRenderableWidget(CycleButton.onOffBuilder(pvp).create(x, y, w / 2 - 2, 20, text("pvp", "PvP"), (b, v) -> pvp = v));
		this.addRenderableWidget(CycleButton.onOffBuilder(invite).create(x + w / 2 + 2, y, w - w / 2 - 2, 20, text("invite", "Invite only"),
			(b, v) -> invite = v));
		y += 24;
		Button go = Button.builder(text("create", "Create match"), b -> this.sendCreate(s)).bounds(x, y, w, 20).build();
		go.active = s.canCreate();
		if (!s.canCreate()) {
			go.setTooltip(Tooltip.create(Component.translatableWithFallback(s.why(), MatchRules.fallback(s.why()))));
		}

		this.addRenderableWidget(go);
		y += 26;
		MatchWire.Mode m = s.modes().get(mode);
		y = this.wrap(m.description(), x, y, w, 3, DIM);
		if (!s.canCreate()) {
			this.wrap(Component.translatableWithFallback(s.why(), MatchRules.fallback(s.why())).getString(), x, y + 1, w, 2, WARN);
		}
	}

	/** Adds {@code text} as at most {@code max} lines of {@code w} px, broken at spaces; returns the y under them. */
	private int wrap(final String text, final int x, final int y0, final int w, final int max, final int colour) {
		String rest = text.strip();
		int y = y0;
		for (int n = 0; n < max && !rest.isEmpty(); n++) {
			String part = this.font.plainSubstrByWidth(rest, w);
			int space = part.lastIndexOf(' ');
			if (part.length() < rest.length() && space > 0 && n < max - 1) {
				part = part.substring(0, space);
			}

			this.lines.add(new Line(part, x, y, colour));
			rest = rest.substring(part.length()).strip();
			y += 11;
		}

		return y;
	}

	private static List<Integer> indices(final int n) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(i);
		}

		return out;
	}

	/** The zone the form describes: a district, the map pick, or (no pick yet) V's spot, else the world centre. */
	private static double[] zoneOf(final MatchWire.State s, final MatchWire.Mode m) {
		if (zone < s.districts().size()) {
			MatchWire.District d = s.districts().get(zone);
			return new double[] {d.x(), d.z(), Math.max(d.r(), MatchRules.MIN_RADIUS)};
		}

		if (custom != null) {
			return custom;
		}

		HostState.Pose pose = HostState.frame();
		return new double[] {pose != null ? Math.round(pose.px()) : 0.0, pose != null ? Math.round(pose.pz()) : 0.0, m.radius()};
	}

	private void pick(final MatchWire.State s) {
		double[] z = zoneOf(s, s.modes().get(mode));
		ZonePick.open(this, z[0], z[1], z[2], picked -> {
			custom = picked;
			zone = s.districts().size();
		});
	}

	private void sendCreate(final MatchWire.State s) {
		MatchWire.Mode m = s.modes().get(mode);
		double[] z = zoneOf(s, m);
		JsonObject o = new JsonObject();
		o.addProperty("op", "create");
		o.addProperty("mode", m.id());
		JsonObject zo = new JsonObject();
		zo.addProperty("x", z[0]);
		zo.addProperty("z", z[1]);
		zo.addProperty("r", Math.clamp(z[2], MatchRules.MIN_RADIUS, MatchRules.MAX_RADIUS));
		zo.addProperty("district", zone < s.districts().size() ? s.districts().get(zone).name() : "");
		o.add("zone", zo);
		o.addProperty("time", MINUTES[minutes] * 60);
		o.addProperty("pvp", pvp);
		o.addProperty("invite", invite);
		MatchClient.act(o.toString());
	}

	/** Right, in a match: its teams, then Start / End / Leave and Invite. */
	private void mine(final MatchWire.State s, final MatchWire.View v, final int x, final int y0, final int w) {
		int y = y0;
		boolean creator = this.minecraft.player != null && v.creator().equals(this.minecraft.player.getPlainTextName());
		String clock = v.state().equals("running") ? String.format(Locale.ROOT, " %d:%02d", v.time() / 60, v.time() % 60) : "";
		this.lines.add(new Line(this.font.plainSubstrByWidth(v.name() + " - " + text("state." + v.state(), v.state()).getString() + clock, w), x, y, TEXT));
		y += 12;
		for (int t = 0; t < v.teams().size(); t++) {
			MatchWire.Team team = v.teams().get(t);
			StringBuilder names = new StringBuilder();
			for (MatchWire.Member m : team.members()) {
				names.append(names.isEmpty() ? "" : ", ").append(m.linked() ? "[V] " : "").append(m.name()).append(m.role().equals("hacker") ? " (Van)" : "");
			}

			this.lines.add(new Line(this.font.plainSubstrByWidth((t == s.team() ? "> " : "") + team.name() + " " + team.score() + ": " + names, w), x, y,
				0xFF000000 | team.colour()));
			y += 11;
		}

		if (!v.others().isEmpty()) {
			StringBuilder names = new StringBuilder();
			for (MatchWire.Member m : v.others()) {
				names.append(names.isEmpty() ? "" : ", ").append(m.name()).append(" (").append(text("role." + m.role(), m.role()).getString()).append(')');
			}

			this.lines.add(new Line(this.font.plainSubstrByWidth(names.toString(), w), x, y, DIM));
			y += 11;
		}

		y += 6;
		int bw = (w - 4) / 3;
		if (creator && v.state().equals("lobby")) {
			this.addRenderableWidget(Button.builder(text("start", "Start"), b -> op("start")).bounds(x, y, bw, 20).build());
		}

		if (creator && !v.state().equals("ended")) {
			this.addRenderableWidget(Button.builder(text("end", "End"), b -> op("end")).bounds(x + bw + 2, y, bw, 20).build());
		}

		this.addRenderableWidget(Button.builder(text("leave", "Leave"), b -> op("leave")).bounds(x + 2 * (bw + 2), y, w - 2 * (bw + 2), 20).build());
		y += 24;
		if (creator && !v.state().equals("ended")) {
			List<String> names = new ArrayList<>();
			for (Roster.Entry e : Roster.all()) {
				if (this.minecraft.player != null && !e.uuid().equals(this.minecraft.player.getUUID())) {
					names.add(e.name());
				}
			}

			if (!names.isEmpty()) {
				inviteName = Math.min(inviteName, names.size() - 1);
				this.addRenderableWidget(CycleButton.builder((String n) -> Component.literal(n), names.get(inviteName)).withValues(names)
					.create(x, y, w - 64, 20, text("player", "Player"), (b, n) -> inviteName = names.indexOf(n)));
				this.addRenderableWidget(Button.builder(text("invite_send", "Invite"), b -> {
					JsonObject o = new JsonObject();
					o.addProperty("op", "invite");
					o.addProperty("name", names.get(Math.min(inviteName, names.size() - 1)));
					MatchClient.act(o.toString());
				}).bounds(x + w - 60, y, 60, 20).build());
			}
		}
	}

	private static void op(final String op) {
		JsonObject o = new JsonObject();
		o.addProperty("op", op);
		MatchClient.act(o.toString());
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 8, TEXT);
		for (Line l : this.lines) {
			graphics.text(this.font, l.text(), l.x(), l.y(), l.colour(), true);
		}

		if (MatchClient.state() == null) {
			graphics.centeredText(this.font, text("waiting", "Waiting for the server..."), this.width / 2, this.height / 2, DIM);
		}
	}

	@Override
	public boolean isPauseScreen() {
		// singleplayer pauses the server under a pause screen: Night City keeps going, so Minecraft must too
		return false;
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen((Screen) null);
	}
}
