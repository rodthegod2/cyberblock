package dev.cyberblock.client.cyber;

import dev.cyberblock.Bridge;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.client.HostState;
import dev.cyberblock.cyber.CyberWire;
import dev.cyberblock.cyber.Hacking;
import dev.cyberblock.cyber.ScanInfo;
import dev.cyberblock.cyber.ScanThrottle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The hack key held (task C4): every 4 ticks the proxy under the crosshair (within 48 blocks, never past the Night City
 * surface the host aims at) is asked about with a `scan`; Cyberpunk's `scaninfo` is drawn as a tooltip beside the
 * crosshair and a glowing box around the target in the world. A target found stays locked (Hacking) and shown for 5 s
 * after the last answer that found it, dimmed with a countdown once the key is up; an answer that finds nothing clears it.
 */
final class ScanClient {
	static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(Cyberblock.ID, "scan_tooltip");
	private static final double RANGE = 48.0;
	private static final int PICK_EVERY = 4;
	/** Answers to scans are taken while the key is held and this long after (a scan's answer is on its way). */
	private static final long LINGER_MS = 1000L;
	/** As long as the server keeps the lock. */
	private static final long LOCK_MS = Hacking.LOCK_NANOS / 1_000_000L;
	private static final ScanThrottle throttle = new ScanThrottle();
	/** Which client entity a scanned Cyberpunk id is (filled on the server thread when a scan is sent): its box is the outline. */
	private static final Map<Long, Integer> entityOf = new ConcurrentHashMap<>();
	private static int ticks;
	private static boolean held;
	private static long answersUntilMs;
	private static long lockedUntilMs;
	private static @Nullable ScanInfo shown;

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
				int entity = pick(minecraft, player);
				if (throttle.shouldSend(now, entity)) {
					send(minecraft.getSingleplayerServer(), entity);
				}
			}
		} else if (held) {
			throttle.reset();
			ticks = 0;
		}

		held = down;
		if (shown != null && now > lockedUntilMs) {
			shown = null;
		}

		outline(minecraft);
	}

	/** The network id of the proxy under the crosshair, or -1. Invisibility does not matter: every proxy is invisible. */
	private static int pick(final Minecraft minecraft, final LocalPlayer player) {
		Camera camera = minecraft.gameRenderer.mainCamera();
		Vec3 from = camera.position();
		Vec3 dir = new Vec3(camera.forwardVector()).normalize();
		double reach = HostState.aimLimit(RANGE);
		Vec3 to = from.add(dir.scale(reach));
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, from, to, new AABB(from, to).inflate(1.0),
			e -> e instanceof ProxyEntity && !e.isRemoved(), reach * reach);
		return hit == null ? -1 : hit.getEntity().getId();
	}

	/** Server thread: the proxy's Cyberpunk id (Bridge.proxyIdOf), then the `scan`. Singleplayer: the ids are the server's too. */
	private static void send(final @Nullable IntegratedServer server, final int entity) {
		if (server == null) {
			return;
		}

		int id = Hacking.nextId();
		server.execute(() -> {
			long target = 0L;
			if (entity >= 0) {
				Entity e = server.overworld().getEntity(entity);
				target = e == null ? 0L : Bridge.proxyIdOf.applyAsLong(e);
				if (target != 0L) {
					entityOf.put(target, entity);
				}
			}

			Cyberblock.events.accept(CyberWire.scan(id, target));
		});
	}

	/**
	 * Link thread: a valid `scaninfo`. A target found is shown and locked on the server (Hacking) for 5 s from now; nothing found
	 * clears both. An answer more than a second after the key was let go is ignored by both, so the server never locks or drops a
	 * target the tooltip does not show.
	 */
	static void onInfo(final ScanInfo info) {
		Minecraft.getInstance().execute(() -> {
			long now = Util.getMillis();
			if (now > answersUntilMs) {
				return;
			}

			shown = info.ok() ? info : null;
			lockedUntilMs = info.ok() ? now + LOCK_MS : 0L;
			CyberLink.onServer(info.ok() ? () -> Hacking.lock(info.target(), info.device()) : Hacking::unlock);
		});
	}

	/** Whole seconds the shown target stays locked (at least 1 while it is shown). */
	private static long lockSeconds() {
		return Math.max(1L, (lockedUntilMs - Util.getMillis() + 999L) / 1000L);
	}

	/** The box drawn around the scanned target: the proxy's own box, or the reported base and size (devices). */
	private static @Nullable AABB box(final Minecraft minecraft, final ScanInfo info) {
		Integer id = entityOf.get(info.target());
		Entity entity = id == null || minecraft.level == null ? null : minecraft.level.getEntity(id);
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
		if (info.device()) {
			return 0xFF33E6FF;
		}

		return switch (info.threat()) {
			case "low" -> 0xFF7CFF5A;
			case "medium" -> 0xFFFFE14D;
			case "high" -> 0xFFFF8A2B;
			case "extreme" -> 0xFFFF2B45;
			default -> 0xFFB8C4D6;
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
			int threatRgb = dim ? 0x707A8A : threatColour(info) & 0xFFFFFF;
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
			graphics.fill(x, lineY, x + width, lineY + 3, alpha | 0x2A0F14);
			int fill = Math.round(width * Math.clamp(info.hp(), 0.0F, 1.0F));
			int hpRgb = info.hp() > 0.5F ? 0x4DFF6A : info.hp() > 0.25F ? 0xFFD84D : 0xFF3B3B;
			graphics.fill(x, lineY, x + fill, lineY + 3, alpha | hpRgb);
			lineY += barHeight;
		}

		for (Component line : tail) {
			graphics.text(font, line, x, lineY, 0xFFFFFFFF, true);
			lineY += 10;
		}
	}
}
