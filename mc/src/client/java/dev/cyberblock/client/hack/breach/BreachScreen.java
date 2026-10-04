package dev.cyberblock.client.hack.breach;

import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.hack.breach.BreachPuzzle;
import dev.cyberblock.hack.breach.BreachRules;
import dev.cyberblock.hack.breach.BreachWire;
import dev.cyberblock.net.Payloads;
import java.util.Arrays;
import java.util.Locale;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Breach Protocol screen (megaplan 6-B1), in the Minecraft container look ({@link McTheme}, vanilla buttons and font): the code
 * grid on the left, the buffer and the daemon sequences on the right, the timer bar on top. The server decides everything: a click
 * sends the pick ({@code cyberblock:act breach}) and the screen shows the data the server answers; the cells the rule allows next
 * ({@link BreachRules#allowed}, the highlighted line) are the only active buttons. Disconnect (or Esc) before the end aborts.
 */
public final class BreachScreen extends Screen {
	static final int CELL = 22, BUTTON = 20, SLOT = 18, SIDE = 152, PAD = 8, TOP = 34, BOTTOM = 30;
	private BreachWire.Screen data;
	/** When the timer runs out (System.currentTimeMillis), or -1 while it has not started. */
	private long deadline = -1;

	BreachScreen(final BreachWire.Screen data) {
		super(Component.translatableWithFallback("cyberblock.breach.title", "Breach Protocol"));
		this.data = data;
		this.deadline = deadline(data);
	}

	int id() {
		return this.data.id();
	}

	void update(final BreachWire.Screen next) {
		this.data = next;
		this.deadline = deadline(next);
		this.rebuildWidgets();
	}

	/** The timer runs once a pick was made and while the breach runs. */
	private static long deadline(final BreachWire.Screen d) {
		return d.picks().length == 0 || d.done() ? -1 : System.currentTimeMillis() + Math.round(d.left() * 1000.0);
	}

	static int width(final int n) {
		return PAD + n * CELL + PAD + SIDE + PAD;
	}

	static int height(final int n) {
		return TOP + Math.max(n * CELL, 3 * 26 + 44) + BOTTOM;
	}

	private int left() {
		return (this.width - width(this.data.size())) / 2;
	}

	private int top() {
		return (this.height - height(this.data.size())) / 2;
	}

	private int[] buffer() {
		return Arrays.stream(this.data.picks()).map(cell -> this.data.grid()[cell]).toArray();
	}

	@Override
	protected void init() {
		int n = this.data.size();
		int left = this.left(), top = this.top();
		int[] picks = this.data.picks();
		boolean playing = !this.data.done() && picks.length < this.data.buffer();
		for (int cell = 0; cell < n * n; cell++) {
			int c = cell;
			boolean used = Arrays.stream(picks).anyMatch(p -> p == c);
			Component label = Component.literal(used ? "[ ]" : BreachPuzzle.CODES.get(this.data.grid()[cell]));
			Button b = Button.builder(label, x -> this.pick(c)).bounds(left + PAD + cell % n * CELL, top + TOP + cell / n * CELL, BUTTON, BUTTON).build();
			b.active = playing && !used && BreachRules.allowed(n, picks, picks.length, cell);
			this.addRenderableWidget(b);
		}

		Component action = this.data.done() ? Component.translatableWithFallback("cyberblock.breach.close", "Close")
			: Component.translatableWithFallback("cyberblock.breach.disconnect", "Disconnect");
		this.addRenderableWidget(Button.builder(action, x -> this.onClose()).bounds(left + width(n) - PAD - 90, top + height(n) - BOTTOM + 4, 90, 20).build());
	}

	private void pick(final int cell) {
		send(BreachWire.pick(this.data.id(), cell));
	}

	private static void send(final String json) {
		if (ClientPlayNetworking.canSend(Payloads.Act.TYPE)) {
			ClientPlayNetworking.send(new Payloads.Act(BreachWire.T, json));
		}
	}

	@Override
	public void onClose() {
		BreachClient.closed(this.data.id());
		if (!this.data.done()) {
			send(BreachWire.abort(this.data.id()));
		}

		super.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** The timer bar's fill 0..1: full before the first pick, empty once the breach ended. */
	static float timeLeft(final long deadline, final long now, final int seconds, final boolean done) {
		return done ? 0.0F : deadline < 0 ? 1.0F : Math.clamp((deadline - now) / (seconds * 1000.0F), 0.0F, 1.0F);
	}

	@Override
	public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractBackground(graphics, mouseX, mouseY, a);
		int n = this.data.size();
		int left = this.left(), top = this.top();
		McTheme.panel(graphics, left, top, width(n), height(n));
		int[] picks = this.data.picks();
		if (this.data.done() || picks.length == this.data.buffer()) {
			return;
		}

		// the line the next pick comes from: the top row first, then the last pick's column, then its row, ...
		int k = picks.length, x = left + PAD - 1, y = top + TOP - 1, band = McTheme.fade(McTheme.YELLOW, 0.5F);
		if (k % 2 == 0) {
			int row = k == 0 ? 0 : picks[k - 1] / n;
			graphics.fill(x, y + row * CELL, x + n * CELL, y + row * CELL + CELL, band);
		} else {
			int col = picks[k - 1] % n;
			graphics.fill(x + col * CELL, y, x + col * CELL + CELL, y + n * CELL, band);
		}
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int n = this.data.size();
		int left = this.left(), top = this.top();
		graphics.text(this.font, this.title, left + PAD, top + PAD, McTheme.PANEL_TEXT, false);
		long now = System.currentTimeMillis();
		int barWidth = width(n) - 2 * PAD - 44;
		McTheme.bossBar(graphics, left + PAD, top + 22, barWidth, timeLeft(this.deadline, now, this.data.total(), this.data.done()));
		double seconds = this.data.done() ? this.data.left() : this.deadline < 0 ? this.data.total() : Math.max(0, this.deadline - now) / 1000.0;
		graphics.text(this.font, String.format(Locale.ROOT, "%.1f s", seconds), left + PAD + barWidth + 6, top + 20, McTheme.PANEL_TEXT, false);

		int x = left + PAD + n * CELL + PAD;
		int y = top + TOP;
		graphics.text(this.font, Component.translatableWithFallback("cyberblock.breach.buffer", "Buffer"), x, y, McTheme.PANEL_TEXT, false);
		int[] buffer = this.buffer();
		for (int i = 0; i < this.data.buffer(); i++) {
			McTheme.slot(graphics, x + i * SLOT, y + 10, SLOT, SLOT);
			if (i < buffer.length) {
				graphics.centeredText(this.font, Component.literal(BreachPuzzle.CODES.get(buffer[i])), x + i * SLOT + SLOT / 2, y + 15, McTheme.WHITE);
			}
		}

		y += 34;
		graphics.text(this.font, Component.translatableWithFallback("cyberblock.breach.daemons", "Daemons"), x, y, McTheme.PANEL_TEXT, false);
		for (int d = 0; d < this.data.seqs().size(); d++) {
			this.daemon(graphics, this.data.seqs().get(d), buffer, x, y + 10 + d * 26);
		}

		if (this.data.done()) {
			graphics.text(this.font, this.result(), left + PAD, top + height(n) - BOTTOM + 10, this.data.state().equals(BreachWire.SOLVED) ? McTheme.GREEN
				: this.data.state().equals("aborted") ? McTheme.PANEL_TEXT : McTheme.RED, false);
		}
	}

	/** One daemon row: its name (green once uploaded, red when the breach ended without it) and its codes, the started ones lit. */
	private void daemon(final GuiGraphicsExtractor graphics, final BreachWire.Seq seq, final int[] buffer, final int x, final int y) {
		int colour = seq.ok() ? McTheme.GREEN : this.data.done() ? McTheme.RED : McTheme.PANEL_TEXT;
		graphics.text(this.font, this.font.plainSubstrByWidth(BreachWire.daemonName(seq.id()).getString(), SIDE), x, y, colour, false);
		int lit = seq.ok() ? seq.codes().length : BreachRules.matched(buffer, buffer.length, seq.codes());
		for (int i = 0; i < seq.codes().length; i++) {
			int sx = x + i * SLOT;
			McTheme.slot(graphics, sx, y + 9, SLOT, 14);
			graphics.centeredText(this.font, Component.literal(BreachPuzzle.CODES.get(seq.codes()[i])), sx + SLOT / 2, y + 12, i < lit ? McTheme.YELLOW : McTheme.WHITE);
		}
	}

	private Component result() {
		return switch (this.data.state()) {
			case BreachWire.SOLVED -> Component.translatableWithFallback("cyberblock.breach.result.solved", "Breach successful: strength %s", this.data.strength());
			case "timeout" -> Component.translatableWithFallback("cyberblock.breach.result.timeout", "Time is up: breach failed");
			case "failed" -> Component.translatableWithFallback("cyberblock.breach.result.failed", "Breach failed");
			default -> Component.translatableWithFallback("cyberblock.breach.result.aborted", "Breach aborted");
		};
	}
}
