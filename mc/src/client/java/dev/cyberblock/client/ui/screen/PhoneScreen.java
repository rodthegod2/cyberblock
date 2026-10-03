package dev.cyberblock.client.ui.screen;

import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.client.ui.UiLink;
import dev.cyberblock.client.ui.UiModel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * V's messages as a Minecraft container screen (F12, section 1.11): contacts on the left in inventory slots, the selected thread
 * on the right (the contact's lines left-aligned, V's right-aligned), and the replies V can choose as vanilla buttons, each sending
 * {@code uiact smsreply} with the reply's hash; the thread is asked for again afterwards.
 */
public final class PhoneScreen extends Screen {
	private static final int W = 320;
	private static final int H = 196;
	private static final int CONTACTS_W = 104;
	private static final int ROW = 14;
	private static final int LINE = 10;
	private static final int REPLIES = 3;
	private static final int V_TEXT = 0xFF1E3C8C;

	private final @Nullable Screen parent;
	private UiModel.@Nullable Reply reply;
	private int requestId = -1;
	private long selectedHash = -1L;
	private final List<Button> replyButtons = new ArrayList<>();

	public PhoneScreen(final @Nullable Screen parent) {
		super(NightCityPauseScreen.ui("phone.title", "Phone"));
		this.parent = parent;
	}

	private int left() {
		return (this.width - W) / 2;
	}

	private int top() {
		return Math.max(2, (this.height - H) / 2);
	}

	@Override
	protected void init() {
		if (this.requestId < 0 && this.reply == null) {
			this.request();
		}

		this.replyButtons.clear();
		int x = this.left() + CONTACTS_W + 12, w = W - CONTACTS_W - 20;
		for (int i = 0; i < REPLIES; i++) {
			int n = i;
			this.replyButtons.add(this.addRenderableWidget(Button.builder(Component.empty(), b -> this.sendReply(n))
				.bounds(x, this.top() + H - 8 - (REPLIES - i) * 22, w, 20).build()));
		}

		this.updateReplies();
	}

	private void request() {
		this.requestId = UiLink.request("phone", r -> {
			this.reply = r;
			if (this.selected() == null && !r.groups().isEmpty()) {
				this.selectedHash = r.groups().getFirst().hash();
			}

			this.updateReplies();
		});
	}

	private UiModel.@Nullable Group selected() {
		UiModel.Reply r = this.reply;
		if (r != null) {
			for (UiModel.Group group : r.groups()) {
				if (group.hash() == this.selectedHash) {
					return group;
				}
			}
		}

		return null;
	}

	/** The replies V can send in the selected thread (state 2). */
	private List<UiModel.Line> choices() {
		UiModel.Group group = this.selected();
		return group == null ? List.of() : group.lines().stream().filter(l -> l.state() == 2).limit(REPLIES).toList();
	}

	private void updateReplies() {
		List<UiModel.Line> choices = this.choices();
		for (int i = 0; i < this.replyButtons.size(); i++) {
			Button button = this.replyButtons.get(i);
			button.visible = i < choices.size();
			if (button.visible) {
				button.setMessage(Component.literal(this.font.plainSubstrByWidth(choices.get(i).text(), button.getWidth() - 8)));
			}
		}
	}

	private void sendReply(final int i) {
		List<UiModel.Line> choices = this.choices();
		if (i < choices.size() && UiLink.act("smsreply", "", choices.get(i).hash(), 0)) {
			this.request();
		}
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		int left = this.left(), top = this.top();
		McTheme.panel(graphics, left, top, W, H);
		graphics.text(this.font, this.title, left + 8, top + 6, McTheme.PANEL_TEXT, false);
		graphics.text(this.font, NightCityPauseScreen.ui("phone.contacts", "Contacts"), left + 8, top + 18, McTheme.PANEL_TEXT, false);
		UiModel.Reply r = this.reply;
		int listTop = top + 30;
		McTheme.slot(graphics, left + 7, listTop - 1, CONTACTS_W, H - 38);
		McTheme.slot(graphics, left + CONTACTS_W + 11, top + 17, W - CONTACTS_W - 18, H - 24);
		if (r == null) {
			Component status = this.requestId < 0 ? NightCityPauseScreen.ui("screen.unavailable", "Night City is not connected") : Component.literal("...");
			graphics.text(this.font, status, left + CONTACTS_W + 16, top + 22, this.requestId < 0 ? McTheme.RED : McTheme.WHITE, false);
		} else if (r.groups().isEmpty()) {
			graphics.text(this.font, NightCityPauseScreen.ui("phone.empty", "No messages"), left + CONTACTS_W + 16, top + 22, McTheme.GRAY, false);
		} else {
			int y = listTop;
			for (UiModel.Group group : r.groups()) {
				if (y + ROW > top + H - 8) {
					break;
				}

				boolean selected = group.hash() == this.selectedHash;
				boolean hovered = mouseX >= left + 8 && mouseX < left + 7 + CONTACTS_W - 1 && mouseY >= y && mouseY < y + ROW;
				if (selected || hovered) {
					graphics.fill(left + 8, y, left + 7 + CONTACTS_W - 1, y + ROW, selected ? 0x80FFFFFF : 0x40FFFFFF);
				}

				graphics.text(this.font, this.font.plainSubstrByWidth(group.title(), CONTACTS_W - 8), left + 11, y + 3, selected ? McTheme.YELLOW : McTheme.WHITE, true);
				y += ROW;
			}

			this.drawThread(graphics);
		}

		super.extractRenderState(graphics, mouseX, mouseY, a);
	}

	/** The selected thread's last lines that fit above the reply buttons. */
	private void drawThread(final GuiGraphicsExtractor graphics) {
		UiModel.Group group = this.selected();
		if (group == null) {
			return;
		}

		int x = this.left() + CONTACTS_W + 15, w = W - CONTACTS_W - 26;
		int top = this.top() + 21;
		int bottom = this.top() + H - 10 - this.choices().size() * 22;
		record Row(FormattedCharSequence text, boolean fromV) {
		}

		List<Row> rows = new ArrayList<>();
		for (UiModel.Line line : group.lines()) {
			if (line.state() == 2) {
				continue;
			}

			for (FormattedCharSequence part : this.font.split(Component.literal(line.text()), w - 16)) {
				rows.add(new Row(part, line.state() == 1));
			}
		}

		int fit = Math.max(0, (bottom - top) / LINE);
		int y = top;
		for (Row row : rows.subList(Math.max(0, rows.size() - fit), rows.size())) {
			int rx = row.fromV() ? x + w - this.font.width(row.text()) : x;
			graphics.text(this.font, row.text(), rx, y, row.fromV() ? McTheme.AQUA : McTheme.WHITE, true);
			y += LINE;
		}
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		UiModel.Reply r = this.reply;
		int left = this.left(), y = this.top() + 30;
		if (r != null && event.x() >= left + 8 && event.x() < left + 7 + CONTACTS_W) {
			for (UiModel.Group group : r.groups()) {
				if (event.y() >= y && event.y() < y + ROW) {
					this.selectedHash = group.hash();
					this.updateReplies();
					return true;
				}

				y += ROW;
			}
		}

		return false;
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
