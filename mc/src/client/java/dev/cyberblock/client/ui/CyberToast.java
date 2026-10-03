package dev.cyberblock.client.ui;

import dev.cyberblock.client.AllowedToast;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Cyberpunk's notifications as Minecraft toasts (F12, section 1.11): every {@code notify} (an item icon per kind on vanilla's
 * system-toast sprite), the phone call (one toast that stays while it rings, "[T] Answer / hold [T] Reject") and the save
 * prompts ("Saving Night City..." then "Saved", one toast updated in place). It implements AllowedToast, the marker that lets it
 * past ToastManagerMixin, which still cancels every vanilla toast.
 */
public final class CyberToast implements Toast, AllowedToast {
	private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/system"); // nine-sliced, so the toast can grow (gui/sprites/toast/system.png.mcmeta)
	private static final Object CALL = new Object();
	private static final Object SAVE = new Object();
	private static final long DEFAULT_MS = 5000L;
	private static final long ENDED_MS = 2500L;
	/** The phone key Cyberpunk keeps while a call rings (Cyberblock_SetPhonePrompt, plan section 1.2). */
	private static final String PHONE_KEY = "T";
	/** Text starts here (after the icon); the toast grows up to MAX_WIDTH for long lines, never under vanilla's 160. */
	private static final int TEXT_X = 30;
	private static final int MAX_WIDTH = 280;

	private final Object token;
	private ItemStack icon;
	private Component title;
	private int titleColour;
	private Component text;
	/** How long it shows from the last update; Long.MAX_VALUE while a call rings. */
	private long showMs;
	/** fullyVisibleForMs at the last update (the manager's clock for this toast). */
	private long updatedAt = -1L;
	private boolean changed = true;
	private Toast.Visibility visibility = Toast.Visibility.SHOW;
	private int width = Toast.DEFAULT_WIDTH;

	private CyberToast(final Object token, final ItemStack icon, final Component title, final int titleColour, final Component text, final long showMs) {
		this.token = token;
		this.icon = icon;
		this.title = title;
		this.titleColour = titleColour;
		this.text = text;
		this.showMs = showMs;
		this.measure();
	}

	/** Wide enough for the longer of title and text, within 160..280. */
	private void measure() {
		Font font = Minecraft.getInstance().font;
		this.width = Math.clamp(TEXT_X + Math.max(font.width(this.title), font.width(this.text)) + 8, Toast.DEFAULT_WIDTH, MAX_WIDTH);
	}

	@Override
	public int width() {
		return this.width;
	}

	/** A {@code notify}: a new toast, except {@code save}, which updates the save toast in place. */
	static void notify(final ToastManager toasts, final UiModel.Notify notify) {
		long ms = notify.secs() > 0.0 ? Math.round(notify.secs() * 1000.0) : DEFAULT_MS;
		Component title = notify.title().isEmpty() ? kindTitle(notify.kind()) : Component.literal(notify.title());
		Component text = Component.literal(notify.text());
		if (notify.kind().equals("save")) {
			show(toasts, SAVE, icon("save"), title, McTheme.YELLOW, text, ms);
		} else {
			toasts.addToast(new CyberToast(new Object(), icon(notify.kind()), title, colour(notify.kind()), text, ms));
		}
	}

	/** A {@code call}: incoming rings until the phase changes; start/active shows "On call", end "Call ended" briefly. */
	static void call(final ToastManager toasts, final UiModel.Call call) {
		Component who = Component.literal(call.who());
		switch (call.phase()) {
			case "incoming" -> show(toasts, CALL, new ItemStack(Items.BELL),
				Component.translatableWithFallback("cyberblock.ui.call.incoming", "Incoming call: %s", who), McTheme.AQUA,
				Component.translatableWithFallback("cyberblock.ui.call.answer", "[%s] Answer / hold [%s] Reject", PHONE_KEY, PHONE_KEY), Long.MAX_VALUE);
			case "start", "active" -> show(toasts, CALL, new ItemStack(Items.BELL),
				Component.translatableWithFallback("cyberblock.ui.call.active", "On call: %s", who), McTheme.AQUA, Component.empty(), DEFAULT_MS);
			default -> {
				CyberToast ringing = toasts.getToast(CyberToast.class, CALL);
				if (ringing != null) {
					ringing.update(new ItemStack(Items.BELL), Component.translatableWithFallback("cyberblock.ui.call.ended", "Call ended"), McTheme.GRAY, who, ENDED_MS);
				}
			}
		}
	}

	/** The toast with this token updated in place, or a new one. */
	private static void show(final ToastManager toasts, final Object token, final ItemStack icon, final Component title, final int colour, final Component text,
		final long ms) {
		CyberToast shown = toasts.getToast(CyberToast.class, token);
		if (shown != null) {
			shown.update(icon, title, colour, text, ms);
		} else {
			toasts.addToast(new CyberToast(token, icon, title, colour, text, ms));
		}
	}

	private void update(final ItemStack icon, final Component title, final int colour, final Component text, final long ms) {
		this.icon = icon;
		this.title = title;
		this.titleColour = colour;
		this.text = text;
		this.showMs = ms;
		this.changed = true;
		this.measure();
	}

	@Override
	public Object getToken() {
		return this.token;
	}

	@Override
	public Toast.Visibility getWantedVisibility() {
		return this.visibility;
	}

	@Override
	public void update(final ToastManager manager, final long fullyVisibleForMs) {
		if (this.changed) {
			this.changed = false;
			this.updatedAt = fullyVisibleForMs;
		}

		long shownFor = fullyVisibleForMs - this.updatedAt;
		this.visibility = this.showMs == Long.MAX_VALUE || shownFor < this.showMs * manager.getNotificationDisplayTimeMultiplier()
			? Toast.Visibility.SHOW : Toast.Visibility.HIDE;
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final Font font, final long fullyVisibleForMs) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, this.width(), this.height());
		graphics.fakeItem(this.icon, 8, 8);
		int room = this.width - TEXT_X - 6;
		if (this.text.getString().isEmpty()) {
			graphics.text(font, font.substrByWidth(this.title, room).getString(), TEXT_X, 12, this.titleColour, false);
		} else {
			graphics.text(font, font.substrByWidth(this.title, room).getString(), TEXT_X, 7, this.titleColour, false);
			graphics.text(font, font.substrByWidth(this.text, room).getString(), TEXT_X, 18, McTheme.WHITE, false);
		}
	}

	/** Section 1.12 toast titles per kind, for a notification that came without one. */
	private static Component kindTitle(final String kind) {
		String fallback = switch (kind) {
			case "quest" -> "Job";
			case "item" -> "Item";
			case "money" -> "Eddies";
			case "xp" -> "Experience";
			case "level" -> "Level Up";
			case "sms" -> "Message";
			case "shard" -> "Shard";
			case "zone" -> "Zone";
			case "wanted" -> "Wanted";
			case "warning" -> "Warning";
			case "save" -> "Save";
			default -> "Night City";
		};
		return Component.translatableWithFallback("cyberblock.ui.notify." + kind, fallback);
	}

	/** A vanilla item as each kind's icon (drawn from the player's own game at runtime). */
	static ItemStack icon(final String kind) {
		return new ItemStack(switch (kind) {
			case "quest" -> Items.WRITABLE_BOOK;
			case "item" -> Items.CHEST;
			case "money" -> Items.GOLD_NUGGET;
			case "xp" -> Items.EXPERIENCE_BOTTLE;
			case "level" -> Items.NETHER_STAR;
			case "sms" -> Items.PAPER;
			case "shard" -> Items.AMETHYST_SHARD;
			case "zone" -> Items.COMPASS;
			case "wanted" -> Items.IRON_BARS;
			case "warning" -> Items.REDSTONE_TORCH;
			case "save" -> Items.ENDER_CHEST;
			default -> Items.OAK_SIGN;
		});
	}

	/** Title colours: quest-important gold, hostile red, money gold, the rest yellow like vanilla toast titles. */
	private static int colour(final String kind) {
		return switch (kind) {
			case "quest", "money", "level" -> McTheme.GOLD;
			case "wanted", "warning" -> McTheme.RED;
			case "sms" -> McTheme.AQUA;
			case "xp", "shard" -> McTheme.GREEN;
			default -> McTheme.YELLOW;
		};
	}

	/** For the reset path: a ringing call toast is dropped with the rest of the UI data. */
	static void hideCall(final ToastManager toasts) {
		@Nullable CyberToast ringing = toasts.getToast(CyberToast.class, CALL);
		if (ringing != null) {
			ringing.showMs = 0L;
			ringing.changed = true;
		}
	}
}
