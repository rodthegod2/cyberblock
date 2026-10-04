package dev.cyberblock.client.cyber;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.cyber.CyberActs;
import dev.cyberblock.cyber.Hacking;
import dev.cyberblock.cyber.ScanInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The hack key held (task C4, plan retest2 C1, megaplan task 1-C1): every 4 ticks the camera ray goes to the server
 * (`cyberblock:act t:"scan"`), which cuts it at 48 blocks (80 with Kiroshi optics) and at the Night City surface its host
 * aims at, picks the proxy along it and asks the proxy's owner about it with a `scan` (a guest's scan too); the owner's
 * `scaninfo` comes back through the server (`cyberblock:ui`) and is drawn as a tooltip beside the crosshair and a glowing
 * box around the target in the world. A target found stays locked (Hacking) and shown for 5 s after the last answer that found it, dimmed with a
 * countdown once the key is up; an answer that finds nothing clears it.
 */
final class ScanClient {
	static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "scan_tooltip");
	private static final int PICK_EVERY = 4;
	/** Answers to scans are taken while the key is held and this long after (a scan's answer is on its way). */
	private static final long LINGER_MS = 1000L;
	/** As long as the server keeps the lock. */
	private static final long LOCK_MS = Hacking.LOCK_NANOS / 1_000_000L;
	private static int ticks;
	private static boolean held;
	private static long answersUntilMs;
	private static long lockedUntilMs;
	private static @Nullable ScanInfo shown;
	/** The proxy entity the shown scan picked (the server says which), -1: none. */
	private static int shownEntity = -1;

	private ScanClient() {
	}

	/** Every client tick. */
	static void tick(final Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		long now = Util.getMillis();
		// a tap whose press and release both arrived between two ticks never shows as isDown: its click still scans once
		boolean clicked = false;
		while (CyberKeys.hack.consumeClick()) {
			clicked = true;
		}

		boolean down = player != null && (CyberKeys.hack.isDown() || clicked);
		if (down) {
			answersUntilMs = now + LINGER_MS;
			if (ticks++ % PICK_EVERY == 0) {
				send(minecraft);
			}
		} else if (held) {
			// the server sees the gap in requests and treats the next hold as new (Hacking.HOLD_GAP_MS)
			ticks = 0;
		}

		held = down;
		if (shown != null && now > lockedUntilMs) {
			shown = null;
		}

		outline(minecraft);
	}

	/**
	 * The scan request: the host camera (or Steve's, without a host) and its forward. The server picks the proxy from its own
	 * positions (plan retest2 R2: never from the client's copy that trails the NPC); the proxy under the crosshair goes along
	 * only as a hint.
	 */
	private static void send(final Minecraft minecraft) {
		Camera camera = minecraft.gameRenderer.mainCamera();
		Integer hint = minecraft.crosshairPickEntity instanceof ProxyEntity proxy ? proxy.getId() : null;
		CyberLink.act(CyberActs.SCAN, CyberActs.scan(hint, camera.position(), new Vec3(camera.forwardVector()).normalize()));
	}

	/**
	 * A valid `scaninfo` from the server (its lock already follows it: Hacking.onScanInfo), with the picked proxy's entity id. A
	 * target found is shown for 5 s from now; nothing found clears it. An answer more than a second after the key was let go is
	 * ignored (the server drops those too).
	 */
	static void onInfo(final ScanInfo info, final int entity) {
		Minecraft.getInstance().execute(() -> {
			long now = Util.getMillis();
			if (now > answersUntilMs) {
				return;
			}

			shown = info.ok() ? info : null;
			shownEntity = info.ok() ? entity : -1;
			lockedUntilMs = info.ok() ? now + LOCK_MS : 0L;
		});
	}

	/** Whole seconds the shown target stays locked (at least 1 while it is shown). */
	private static long lockSeconds() {
		return Math.max(1L, (lockedUntilMs - Util.getMillis() + 999L) / 1000L);
	}

	/** The box drawn around the scanned target: the proxy's own box, or the reported base and size (devices). */
	private static @Nullable AABB box(final Minecraft minecraft, final ScanInfo info) {
		Entity entity = shownEntity < 0 || minecraft.level == null ? null : minecraft.level.getEntity(shownEntity);
		if (entity instanceof ProxyEntity && !entity.isRemoved()) {
			return entity.getBoundingBox();
		}

		if (info.w() <= 0.0F || info.h() <= 0.0F) {
			return null;
		}

		double half = info.w() / 2.0;
		return new AABB(info.x() - half, info.y(), info.z() - half, info.x() + half, info.y() + info.h(), info.z() + half);
	}

	/**
	 * A per-tick gizmo: drawn in the world pass, so the compositor puts it at the target's place in Night City (depth tested).
	 * Half as bright once the key is up and the target is only locked.
	 */
	private static void outline(final Minecraft minecraft) {
		ScanInfo info = shown;
		AABB box = info == null || minecraft.level == null ? null : box(minecraft, info);
		if (box != null) {
			int rgb = threatColour(info) & 0x00FFFFFF;
			int stroke = rgb | (held ? 0xFF000000 : 0x80000000);
			int fill = rgb | (held ? 0x30000000 : 0x18000000);
			Gizmos.cuboid(box.inflate(0.05), GizmoStyle.strokeAndFill(stroke, 3.0F, fill));
		}
	}

	static int threatColour(final ScanInfo info) {
		// ChatFormatting colours (plan retest3 section 1.11): aqua devices, green / yellow / gold / red threats, grey none
		if (info.device()) {
			return 0xFF55FFFF;
		}

		return switch (info.threat()) {
			case "low" -> 0xFF55FF55;
			case "medium" -> 0xFFFFFF55;
			case "high" -> 0xFFFFAA00;
			case "extreme" -> 0xFFFF5555;
			default -> 0xFFAAAAAA;
		};
	}

	/**
	 * HUD element: the tooltip right of the crosshair (name, faction, threat, an HP bar for people and vehicles). Once the key
	 * is up the locked target is drawn dimmed, with how long the lock lasts. While the key is held it also names the chip the
	 * wheel has picked (HackWheel), with or without a target.
	 */
	static void draw(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		ScanInfo info = shown;
		List<Component> head = new ArrayList<>();
		List<Component> tail = new ArrayList<>();
		boolean dim = !held;
		if (info != null) {
			int threatRgb = dim ? 0xAAAAAA : threatColour(info) & 0xFFFFFF;
			head.add(Component.literal(info.name().isEmpty() ? "?" : info.name()).withStyle(dim ? ChatFormatting.GRAY : ChatFormatting.WHITE, ChatFormatting.BOLD));
			if (!info.faction().isEmpty()) {
				head.add(Component.literal(info.faction()).withStyle(dim ? ChatFormatting.DARK_GRAY : ChatFormatting.GRAY));
			}

			if (info.device()) {
				head.add(Component.translatable("cyberblock.scan.device").withColor(threatRgb));
			} else {
				Component threat = Component.translatable("cyberblock.scan.threat." + info.threat().toLowerCase(Locale.ROOT));
				head.add(Component.translatable("cyberblock.scan.threat", threat).withColor(threatRgb));
			}

			if (dim) {
				tail.add(Component.translatable("cyberblock.scan.locked", lockSeconds()).withStyle(ChatFormatting.AQUA));
			}
		}

		if (held && minecraft.player != null) {
			tail.addAll(HackWheel.lines(minecraft.player));
		}

		if (head.isEmpty() && tail.isEmpty()) {
			return;
		}

		Font font = minecraft.font;
		int width = 60;
		for (Component line : head) {
			width = Math.max(width, font.width(line));
		}

		for (Component line : tail) {
			width = Math.max(width, font.width(line));
		}

		int barHeight = info == null || info.device() ? 0 : 5;
		int height = (head.size() + tail.size()) * 10 - 2 + barHeight;
		int x = graphics.guiWidth() / 2 + 16;
		int y = graphics.guiHeight() / 2 - height / 2;
		TooltipRenderUtil.extractTooltipBackground(graphics, x, y, width, height, null);
		int lineY = y;
		for (Component line : head) {
			graphics.text(font, line, x, lineY, 0xFFFFFFFF, true);
			lineY += 10;
		}

		if (barHeight > 0) {
			int alpha = dim ? 0x80000000 : 0xFF000000;
			graphics.fill(x, lineY, x + width, lineY + 3, alpha | 0x555555);
			int fill = Math.round(width * Math.clamp(info.hp(), 0.0F, 1.0F));
			int hpRgb = info.hp() > 0.5F ? 0x55FF55 : info.hp() > 0.25F ? 0xFFFF55 : 0xFF5555;
			graphics.fill(x, lineY, x + fill, lineY + 3, alpha | hpRgb);
			lineY += barHeight;
		}

		for (Component line : tail) {
			graphics.text(font, line, x, lineY, 0xFFFFFFFF, true);
			lineY += 10;
		}
	}
}
