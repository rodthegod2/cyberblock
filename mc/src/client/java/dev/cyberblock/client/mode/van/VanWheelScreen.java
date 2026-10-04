package dev.cyberblock.client.mode.van;

import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Hack;
import dev.cyberblock.cyber.Hacking;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.Ram;
import dev.cyberblock.hack.actions.ActWire;
import dev.cyberblock.hack.actions.RemoteHack;
import dev.cyberblock.mode.van.VanRules;
import dev.cyberblock.mode.van.VanWire;
import dev.cyberblock.net.Permissions.DeviceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The Hack Wheel in the Van (megaplan task 8-V1: "the full Hack Wheel on anything they can see"): every action the target takes, in
 * a ring around it ({@link VanRules#options}): the netrunner's quickhack chips (his RAM and cooldowns; the chips he lacks are greyed),
 * the Vehicle Panel's hacks on a car, the area hacks around a person, a device's actions. A click fires it through the server, which
 * checks again that the Van sees the target and that the owner allows it; then the wheel closes (back to the drone's view or the Van
 * screen). Escape goes back without firing.
 */
final class VanWheelScreen extends Screen {
	static final int SLOT_W = 92, SLOT_H = 18, DEAD_ZONE = 18, RING_MAX = 8, ROW_H = SLOT_H + 4, COLUMN_GAP = 68;
	private final VanWire.Target target;
	private final boolean fromDrone;
	private final List<VanRules.Option> options;
	private int hovered = -1;

	VanWheelScreen(final VanWire.Target target, final boolean fromDrone) {
		super(Component.translatableWithFallback("cyberblock.van.wheel", "Hack Wheel"));
		this.target = target;
		this.fromDrone = fromDrone;
		DeviceType type = DeviceType.byId(target.type());
		this.options = VanRules.options(target.kind(), type == null ? DeviceType.CONTAINER : type);
	}

	/** One option as shown: its label, and why it cannot fire now (null: it can). */
	record Shown(Component label, @Nullable Component why) {
	}

	Shown shown(final VanRules.Option o) {
		LocalPlayer player = Objects.requireNonNull(this.minecraft.player);
		return switch (o) {
			case VanRules.Quickhack(Hack hack) -> {
				ItemStack chip = Hacking.findChip(player, hack);
				int cost = Ram.cost(hack, CyberData.has(player, Perk.CHEAP_HACKS));
				Component name = chip.isEmpty() ? Component.translatableWithFallback("item.cyberblock." + hack.item(), hack.wire()) : chip.getHoverName();
				Component why = chip.isEmpty() ? VanScreen.text("wheel.no_chip", "You carry no such chip")
					: !CyberData.hasDeck(player) ? VanScreen.text("wheel.no_deck", "No cyberdeck")
					: player.getCooldowns().isOnCooldown(chip) ? VanScreen.text("wheel.cooldown", "Recharging")
					: CyberData.ram(player) < cost ? VanScreen.text("wheel.no_ram", "Needs %s RAM", cost) : null;
				yield new Shown(Component.empty().append(name).append(" (" + cost + ")"), why);
			}
			case VanRules.Vehicle(RemoteHack hack) -> new Shown(Component.translatableWithFallback(hack.langKey(), hack.english()), null);
			case VanRules.Area(RemoteHack hack) -> new Shown(Component.translatableWithFallback(hack.langKey(), hack.english()), null);
			case VanRules.DeviceAct(String act) -> new Shown(VanScreen.text("act." + act, Character.toUpperCase(act.charAt(0)) + act.substring(1)), null);
		};
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int[] slot(final int i) {
		return slot(i, this.options.size(), this.width, this.height);
	}

	private int at(final double mx, final double my) {
		return at(mx, my, this.options.size(), this.width, this.height);
	}

	/**
	 * The slot centre of option i of n on a w x h screen: in a ring around the target when its slots do not touch (at most
	 * {@link #RING_MAX}); else (a car or a person with every chip, a small screen) in two columns either side of it. Pure.
	 */
	static int[] slot(final int i, final int n, final int w, final int h) {
		if (ring(n, w, h)) {
			return ringSlot(i, n, w, h);
		}

		int rows = (n + 1) / 2, row = i % rows, side = i < rows ? -1 : 1;
		double y = h / 2.0 + (row - (rows - 1) / 2.0) * ROW_H;
		return new int[] {(int) Math.round(w / 2.0 + side * columnOffset(w)), (int) Math.round(y)};
	}

	private static int[] ringSlot(final int i, final int n, final int w, final int h) {
		double angle = 2.0 * Math.PI * i / n - Math.PI / 2.0, r = Math.clamp(Math.min(w, h) * 0.32, 64.0, 120.0);
		return new int[] {(int) Math.round(w / 2.0 + Math.cos(angle) * r * 1.25), (int) Math.round(h / 2.0 + Math.sin(angle) * r)};
	}

	/** A ring holds these options: at most {@link #RING_MAX} and no two slots touch on this screen. */
	static boolean ring(final int n, final int w, final int h) {
		if (n > RING_MAX) {
			return false;
		}

		for (int i = 0; i < n; i++) {
			int[] a = ringSlot(i, n, w, h);
			for (int j = i + 1; j < n; j++) {
				int[] b = ringSlot(j, n, w, h);
				if (Math.abs(a[0] - b[0]) < SLOT_W && Math.abs(a[1] - b[1]) < SLOT_H) {
					return false;
				}
			}
		}

		return true;
	}

	/** How far the two columns' centres stand from the middle: room for the target's name, inside the screen. */
	static double columnOffset(final int w) {
		return Math.min(COLUMN_GAP, w / 2.0 - SLOT_W - 4.0) + SLOT_W / 2.0;
	}

	/** The option under the mouse (-1: none): in a ring the nearest slot by angle once the mouse left the middle; in columns the slot hit. Pure. */
	static int at(final double mx, final double my, final int n, final int w, final int h) {
		if (n == 0) {
			return -1;
		}

		if (!ring(n, w, h)) {
			for (int i = 0; i < n; i++) {
				int[] p = slot(i, n, w, h);
				if (Math.abs(mx - p[0]) <= SLOT_W / 2.0 && Math.abs(my - p[1]) <= SLOT_H / 2.0 + 1) {
					return i;
				}
			}

			return -1;
		}

		double dx = (mx - w / 2.0) / 1.25, dy = my - h / 2.0;
		if (Math.hypot(dx, dy) < DEAD_ZONE) {
			return -1;
		}

		double angle = Math.atan2(dy, dx) + Math.PI / 2.0;
		return Math.floorMod((int) Math.round(angle / (2.0 * Math.PI) * n), n);
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		graphics.fill(0, 0, this.width, this.height, 0x90000000);
		this.hovered = this.at(mouseX, mouseY);
		int cx = this.width / 2, cy = this.height / 2;
		// between the two columns the name gets the room they leave
		int room = !ring(this.options.size(), this.width, this.height) ?(int) (2 * (columnOffset(this.width) - SLOT_W / 2.0)) - 8 : this.width;
		Component name = Component.literal(this.font.plainSubstrByWidth(VanScreen.name(this.target).getString(), room))
			.withStyle(this.target.hostile() ? ChatFormatting.RED : ChatFormatting.WHITE);
		graphics.centeredText(this.font, name, cx, cy - 10, McTheme.WHITE);
		Component from = this.target.seen() == VanRules.Seen.DRONE ? VanScreen.text("seen.drone", "Seen by your drone") : VanScreen.text("seen.screen", "In the host's area");
		graphics.centeredText(this.font, from, cx, cy + 2, McTheme.AQUA);
		if (this.options.isEmpty()) {
			graphics.centeredText(this.font, VanScreen.text("wheel.none", "Nothing the Van can do to this"), cx, cy + 16, McTheme.GRAY);
		}

		Component tip = null;
		for (int i = 0; i < this.options.size(); i++) {
			Shown s = this.shown(this.options.get(i));
			int[] p = this.slot(i);
			boolean over = i == this.hovered;
			int x0 = p[0] - SLOT_W / 2, y0 = p[1] - SLOT_H / 2;
			graphics.fill(x0, y0, x0 + SLOT_W, y0 + SLOT_H, over ? 0xE02A4A5A : 0xC0101820);
			graphics.outline(x0, y0, SLOT_W, SLOT_H, over ? McTheme.YELLOW : s.why() == null ? McTheme.AQUA : McTheme.DARK_GRAY);
			String label = this.font.plainSubstrByWidth(s.label().getString(), SLOT_W - 6);
			graphics.centeredText(this.font, Component.literal(label), p[0], p[1] - 4, s.why() == null ? McTheme.WHITE : McTheme.DARK_GRAY);
			if (over) {
				tip = s.why();
			}
		}

		graphics.centeredText(this.font, VanScreen.text("wheel.hint", "Click to fire, Esc to go back"), cx, this.height - 16, McTheme.GRAY);
		super.extractRenderState(graphics, mouseX, mouseY, a);
		if (tip != null) {
			graphics.setComponentTooltipForNextFrame(this.font, List.of(tip.copy().withStyle(ChatFormatting.RED)), mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		int i = this.at(event.x(), event.y());
		if (i < 0) {
			return super.mouseClicked(event, doubleClick);
		}

		if (this.shown(this.options.get(i)).why() == null) {
			fire(this.target, this.options.get(i));
			this.back();
		}

		return true;
	}

	/** Escape (vanilla's close) goes back instead. */
	@Override
	public void onClose() {
		this.back();
	}

	/** Back to where the wheel came from: the drone's view, or the Van screen. */
	private void back() {
		this.minecraft.gui.setScreen(this.fromDrone || VanClient.state() == null ? null : new VanScreen());
	}

	/** Sends one option at a target: the Van's own vhack for chips and device actions, 6-I1's rhack for car and area hacks. */
	static boolean fire(final VanWire.Target t, final VanRules.Option o) {
		return switch (o) {
			case VanRules.Quickhack(Hack hack) -> t.kind() == VanRules.Kind.DEVICE
				? t.host() != null && VanClient.send(VanWire.T_VHACK, VanWire.deviceChip(t.host(), t.dev()))
				: VanClient.send(VanWire.T_VHACK, VanWire.figureHack(t.eid(), hack));
			case VanRules.Vehicle(RemoteHack hack) -> VanClient.send(ActWire.T_RHACK, ActWire.vehicle(hack, t.eid()));
			case VanRules.Area(RemoteHack hack) -> VanClient.send(ActWire.T_RHACK, ActWire.point(hack, t.p()));
			case VanRules.DeviceAct(String act) -> t.host() != null && VanClient.send(VanWire.T_VHACK, VanWire.deviceAct(t.host(), t.dev(), act));
		};
	}

	/** The labels in ring order (the screenshot tour's log). */
	List<String> labels() {
		List<String> out = new ArrayList<>();
		this.options.forEach(o -> out.add(this.shown(o).label().getString()));
		return out;
	}
}
