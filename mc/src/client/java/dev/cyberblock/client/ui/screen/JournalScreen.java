package dev.cyberblock.client.ui.screen;

import dev.cyberblock.client.ui.UiLink;
import dev.cyberblock.client.ui.UiModel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Night City's journal as an open Minecraft book (F12, section 1.11: J opens it). The vanilla book texture
 * (BookViewScreen.BOOK_LOCATION, drawn at runtime) twice, the left page mirrored: jobs by kind on the left (Main Jobs, Side Jobs,
 * Gigs, Other; the tracked one marked, finished ones ticked; scroll with the wheel), the selected job's objectives on the right
 * with a vanilla Track button ({@code uiact track}, arg "quest", hash = the job's hash).
 */
public final class JournalScreen extends Screen {
	private static final int PAGE = 192;
	/** Where the page art's spine is: the two pages overlap here, so the spread is 2 x (192 - 20) wide. */
	private static final int SPINE = 20;
	private static final int TEXT_IN = 36;
	private static final int TEXT_W = 114;
	private static final int TEXT_TOP = 16;
	private static final int TEXT_BOTTOM = 160;
	private static final int LINE = 10;
	private static final int INK = 0xFF000000;
	private static final int FADED = 0xFF707070;
	private static final int DARK_GOLD = 0xFFAA6600;
	private static final int DARK_GREEN = 0xFF00802A;
	private static final int DARK_RED = 0xFFAA0000;
	private static final int SELECTED = 0xFF2040A0;
	private static final List<String> KINDS = List.of("main", "side", "gig", "other");

	private final @Nullable Screen parent;
	private UiModel.@Nullable Reply reply;
	private int requestId = -1;
	private long selectedHash = -1L;
	private int scroll;
	/** Left-page rows of the last frame: the group each one selects (null for headers). */
	private final List<UiModel.@Nullable Group> rows = new ArrayList<>();
	private @Nullable Button track;

	public JournalScreen(final @Nullable Screen parent) {
		super(NightCityPauseScreen.ui("journal.title", "Journal"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (this.requestId < 0 && this.reply == null) {
			this.request();
		}

		this.track = this.addRenderableWidget(Button.builder(Component.empty(), b -> this.trackSelected())
			.bounds(this.rightPageText(), this.top() + TEXT_BOTTOM + 4, TEXT_W, 20).build());
		this.updateTrack();
	}

	private void request() {
		this.requestId = UiLink.request("journal", r -> {
			this.reply = r;
			if (this.selected() == null) {
				// the tracked job first, else the first one
				this.selectedHash = r.groups().stream().filter(UiModel.Group::tracked).findFirst().or(() -> r.groups().stream().findFirst())
					.map(UiModel.Group::hash).orElse(-1L);
			}

			this.updateTrack();
		});
	}

	private int centre() {
		return this.width / 2;
	}

	private int top() {
		return Math.max(2, (this.height - PAGE - 24) / 2);
	}

	private int leftPageText() {
		return this.centre() - (PAGE - SPINE) + (PAGE - TEXT_IN - TEXT_W);
	}

	private int rightPageText() {
		return this.centre() - SPINE + TEXT_IN;
	}

	private UiModel.@Nullable Group selected() {
		UiModel.Reply r = this.reply;
		if (r == null) {
			return null;
		}

		for (UiModel.Group group : r.groups()) {
			if (group.hash() == this.selectedHash) {
				return group;
			}
		}

		return null;
	}

	private void updateTrack() {
		UiModel.Group group = this.selected();
		if (this.track != null) {
			this.track.visible = group != null && !group.done();
			this.track.active = group != null && !group.tracked();
			this.track.setMessage(group != null && group.tracked() ? NightCityPauseScreen.ui("journal.tracked", "Tracked")
				: NightCityPauseScreen.ui("journal.track", "Track"));
		}
	}

	private void trackSelected() {
		UiModel.Group group = this.selected();
		if (group != null && UiLink.act("track", "quest", group.hash(), 0)) {
			// ask again: the reply shows the new tracked job
			this.request();
		}
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		int top = this.top(), centre = this.centre();
		// right page as drawn, left page mirrored (u from right to left) so the spines meet in the middle
		graphics.blit(BookViewScreen.BOOK_LOCATION, centre - SPINE, top, centre - SPINE + PAGE, top + PAGE, 0.0F, PAGE / 256.0F, 0.0F, PAGE / 256.0F);
		graphics.blit(BookViewScreen.BOOK_LOCATION, centre + SPINE - PAGE, top, centre + SPINE, top + PAGE, PAGE / 256.0F, 0.0F, 0.0F, PAGE / 256.0F);
		graphics.centeredText(this.font, this.title, centre, Math.max(0, top - 10), 0xFFFFFFFF);
		UiModel.Reply r = this.reply;
		if (r == null) {
			Component status = this.requestId < 0 ? NightCityPauseScreen.ui("screen.unavailable", "Night City is not connected") : Component.literal("...");
			graphics.text(this.font, status, this.leftPageText(), top + TEXT_TOP, this.requestId < 0 ? DARK_RED : INK, false);
		} else {
			this.drawList(graphics, r, mouseX, mouseY);
			this.drawSelected(graphics);
		}

		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	private void drawList(final GuiGraphicsExtractor graphics, final UiModel.Reply r, final int mouseX, final int mouseY) {
		this.rows.clear();
		List<Component> lines = new ArrayList<>();
		for (String kind : KINDS) {
			List<UiModel.Group> groups = r.groups().stream().filter(g -> g.kind().equals(kind)).toList();
			if (groups.isEmpty()) {
				continue;
			}

			lines.add(NightCityPauseScreen.ui("journal." + kind, switch (kind) {
				case "main" -> "Main Jobs";
				case "side" -> "Side Jobs";
				case "gig" -> "Gigs";
				default -> "Other";
			}).withStyle(ChatFormatting.BOLD, ChatFormatting.UNDERLINE));
			this.rows.add(null);
			for (UiModel.Group group : groups) {
				MutableComponent line = Component.literal(group.tracked() ? "» " : group.done() ? "✔ " : "  ");
				line.append(group.title());
				lines.add(line);
				this.rows.add(group);
			}
		}

		int x = this.leftPageText(), y = this.top() + TEXT_TOP;
		if (lines.isEmpty()) {
			graphics.text(this.font, NightCityPauseScreen.ui("journal.empty", "No jobs"), x, y, FADED, false);
			return;
		}

		int visible = (TEXT_BOTTOM + 24 - TEXT_TOP) / LINE;
		this.scroll = Math.clamp(this.scroll, 0, Math.max(0, lines.size() - visible));
		for (int i = this.scroll; i < lines.size() && i < this.scroll + visible; i++) {
			UiModel.Group group = this.rows.get(i);
			int colour = group == null ? INK : group.hash() == this.selectedHash ? SELECTED : group.done() ? FADED : group.tracked() ? DARK_GOLD : INK;
			if (group != null && mouseX >= x && mouseX < x + TEXT_W && mouseY >= y && mouseY < y + LINE) {
				graphics.fill(x - 1, y - 1, x + TEXT_W, y + LINE - 1, 0x20000000);
			}

			graphics.text(this.font, this.font.substrByWidth(lines.get(i), TEXT_W).getString(), x, y, colour, false);
			y += LINE;
		}
	}

	private void drawSelected(final GuiGraphicsExtractor graphics) {
		UiModel.Group group = this.selected();
		if (group == null) {
			return;
		}

		int x = this.rightPageText(), y = this.top() + TEXT_TOP, bottom = this.top() + TEXT_BOTTOM;
		for (FormattedCharSequence line : this.font.split(Component.literal(group.title()).withStyle(ChatFormatting.BOLD), TEXT_W)) {
			graphics.text(this.font, line, x, y, INK, false);
			y += LINE;
		}

		if (group.done()) {
			graphics.text(this.font, NightCityPauseScreen.ui("journal.done", "Completed"), x, y, DARK_GREEN, false);
			y += LINE;
		} else if (group.tracked()) {
			graphics.text(this.font, NightCityPauseScreen.ui("journal.tracked", "Tracked"), x, y, DARK_GOLD, false);
			y += LINE;
		}

		y += 3;
		for (UiModel.Line objective : group.lines()) {
			String mark = switch (objective.state()) {
				case 1 -> "✔";
				case 2 -> "✘";
				default -> "☐";
			};
			int colour = switch (objective.state()) {
				case 1 -> DARK_GREEN;
				case 2 -> DARK_RED;
				default -> INK;
			};
			List<FormattedCharSequence> lines = this.font.split(Component.literal(objective.text()), TEXT_W - 10);
			graphics.text(this.font, mark, x, y, colour, false);
			for (FormattedCharSequence line : lines) {
				if (y + LINE > bottom) {
					return;
				}

				graphics.text(this.font, line, x + 10, y, objective.state() == 0 ? INK : FADED, false);
				y += LINE;
			}
		}
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		int x = this.leftPageText();
		int row = (int) Math.floor((event.y() - this.top() - TEXT_TOP) / LINE) + this.scroll;
		if (event.x() >= x && event.x() < x + TEXT_W && row >= this.scroll && row < this.rows.size()) {
			UiModel.Group group = this.rows.get(row);
			if (group != null) {
				this.selectedHash = group.hash();
				this.updateTrack();
				return true;
			}
		}

		return false;
	}

	@Override
	public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
		if (x < this.centre()) {
			this.scroll -= (int) Math.signum(scrollY);
			return true;
		}

		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public void removed() {
		UiLink.forget(this.requestId);
		super.removed();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return this.parent != null;
	}
}
