package dev.cyberblock.client.ui;

import com.google.gson.JsonObject;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.HostLink;
import dev.cyberblock.client.ui.screen.VendorScreen;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * The Night City UI messages (plan 2026-10-03-retest3-polish section 1.1) from the host link: HostLink hands every host message
 * to its client listeners on the link thread; these listeners parse it into UiModel and move anything that touches the game (screens,
 * toasts, waypoints) to the client thread. Also: {@code npcs} for the radar, {@code hello} and {@code state} for resets.
 */
public final class UiLink {
	/**
	 * A host quiet for longer than this hides the HUD, but the data stays: Cyberpunk sends quest, pins and stats only when they
	 * change, so a quiet spell (a long quest-teleport load) must not blank them. Only {@code hello} and {@code state active:false}
	 * drop the data.
	 */
	private static final long QUIET_NANOS = 10_000_000_000L;
	/** The loading cover's safety limit: it stays up through a whole load and goes only on screen.pass "", hello or state off. */
	private static final long LOADING_NANOS = 120_000_000_000L;
	private static final long LINKED_NANOS = 2_000_000_000L;
	private static volatile long lastHostNanos;
	private static final AtomicInteger warned = new AtomicInteger();
	private static final AtomicInteger nextRequest = new AtomicInteger(1);
	/** Screens waiting for a {@code reply}, by request id. */
	private static final Map<Integer, Consumer<UiModel.Reply>> waiting = new ConcurrentHashMap<>();

	private UiLink() {
	}

	static void register() {
		HostLink.listen("cam", m -> lastHostNanos = System.nanoTime());
		HostLink.listen("hello", m -> {
			// a new host session: nothing it said before holds
			heard();
			UiModel.reset();
			waiting.clear();
			onClient(UiLink::onReset);
		});
		HostLink.listen("state", m -> {
			heard();
			if (m.has("active") && !m.get("active").getAsBoolean()) {
				// Cyberblock off (F9) or lost: Cyberpunk shows its own UI again
				UiModel.reset();
				onClient(UiLink::onReset);
			}
		});
		HostLink.listen("npcs", m -> {
			var npcs = UiModel.parseNpcs(m);
			if (npcs != null) {
				UiModel.npcs = npcs;
			}
		});
		HostLink.listen("stats", m -> {
			UiModel.Stats v = valid(UiModel.parseStats(m), "stats");
			if (v != null) {
				UiModel.stats = v;
			}
		});
		HostLink.listen("quest", m -> {
			UiModel.Quest v = valid(UiModel.parseQuest(m), "quest");
			if (v != null) {
				UiModel.quest = v.isEmpty() ? null : v;
			}
		});
		HostLink.listen("pins", m -> {
			UiModel.Pins v = valid(UiModel.parsePins(m), "pins");
			if (v != null) {
				UiModel.pins = v;
			}
		});
		HostLink.listen("prompt", m -> {
			UiModel.Prompt v = valid(UiModel.parsePrompt(m), "prompt");
			if (v != null) {
				UiModel.prompt = v;
			}
		});
		HostLink.listen("choices", m -> {
			UiModel.Choices v = valid(UiModel.parseChoices(m, System.nanoTime()), "choices");
			if (v != null) {
				UiModel.choices = v;
			}
		});
		HostLink.listen("sub", m -> {
			UiModel.Subtitle v = valid(UiModel.parseSub(m, System.nanoTime()), "sub");
			if (v != null) {
				UiModel.Subtitle shown = UiModel.subtitle;
				if (v.show()) {
					UiModel.subtitle = v;
				} else if (shown != null && (v.key().isEmpty() || v.key().equals(shown.key()))) {
					UiModel.subtitle = null;
				}
			}
		});
		HostLink.listen("call", m -> {
			UiModel.Call v = valid(UiModel.parseCall(m), "call");
			if (v != null) {
				onClient(() -> UiEvents.call(v));
			}
		});
		HostLink.listen("notify", m -> {
			UiModel.Notify v = valid(UiModel.parseNotify(m), "notify");
			if (v != null) {
				onClient(() -> UiEvents.notify(v));
			}
		});
		HostLink.listen("screen", m -> {
			UiModel.ScreenMsg v = valid(UiModel.parseScreen(m), "screen");
			if (v == null) {
				return;
			}

			if (v.pass() != null) {
				Cyberblock.LOG.info("ui: pass-through {}", v.pass().isEmpty() ? "ended" : v.pass());
				UiModel.pass = v.pass();
			} else {
				onClient(() -> UiEvents.open(v.open()));
			}
		});
		HostLink.listen("reply", m -> {
			UiModel.Reply v = valid(UiModel.parseReply(m), "reply");
			VendorModel.Vendor vendor = v != null ? VendorModel.of(v) : null;
			if (vendor != null) {
				// unrequested: the host opened a Night City vendor (Vendors.reds), or a purchase refreshed it
				onClient(() -> VendorScreen.show(vendor));
			} else if (v != null) {
				Consumer<UiModel.Reply> to = waiting.remove(v.id());
				if (to != null) {
					onClient(() -> to.accept(v));
				}
			}
		});
	}

	/** The host spoke within the last 2 s. */
	public static boolean linked() {
		return heardWithin(LINKED_NANOS);
	}

	/** The host spoke within the last 10 s: the HUD shows; quieter than that it hides (the data is kept). */
	public static boolean recentlyLinked() {
		return heardWithin(QUIET_NANOS);
	}

	/** Cyberpunk said it is loading ({@code screen.pass:"loading"}) and has not ended it; 120 s of silence is the safety limit. */
	public static boolean loading() {
		return UiModel.pass.equals("loading") && heardWithin(LOADING_NANOS);
	}

	private static boolean heardWithin(final long nanos) {
		long last = lastHostNanos;
		return last != 0L && System.nanoTime() - last < nanos;
	}

	/** Tests only: no host has spoken. */
	static void forgetHost() {
		lastHostNanos = 0L;
	}

	private static void heard() {
		lastHostNanos = System.nanoTime();
	}

	/** Client thread: the UI data was dropped. */
	private static void onReset() {
		UiEvents.reset();
	}

	/** Sends {"t":"uiact",...} to Cyberpunk (section 1.1). Returns false when no host is linked. */
	public static boolean act(final String a, final String arg, final long hash, final int id) {
		if (!linked()) {
			return false;
		}

		Cyberblock.LOG.info("ui: act {} {}", a, arg);
		HostLink.send(UiModel.uiact(a, arg, hash, id));
		return true;
	}

	/**
	 * Asks Cyberpunk for a journal, phone or atlas reply ({@code uiact journalreq/phonereq/atlasreq}); {@code to} gets it on the client
	 * thread. Returns the request id, or -1 when no host is linked.
	 */
	public static int request(final String kind, final Consumer<UiModel.Reply> to) {
		return request(kind, "", to);
	}

	/** As {@link #request(String, Consumer)}, with an {@code arg} (atlas {@code "fasttravel"}: only the fast-travel points). */
	public static int request(final String kind, final String arg, final Consumer<UiModel.Reply> to) {
		int id = nextRequest.getAndIncrement();
		waiting.put(id, to);
		if (!act(kind + "req", arg, 0L, id)) {
			waiting.remove(id);
			return -1;
		}

		return id;
	}

	/** A Minecraft toast like Cyberpunk's notifications (kind as {@code notify.kind}), from any thread. */
	public static void toast(final String kind, final String title, final String text, final double seconds) {
		UiModel.Notify notify = new UiModel.Notify(UiModel.NOTIFY_KINDS.contains(kind) ? kind : "info", title, text, seconds);
		onClient(() -> UiEvents.notify(notify));
	}

	/** A screen that closed no longer wants its reply. */
	public static void forget(final int id) {
		waiting.remove(id);
	}

	private static <T> @Nullable T valid(final @Nullable T parsed, final String type) {
		if (parsed == null) {
			warn(type);
		} else {
			heard();
		}

		return parsed;
	}

	/** A hostile host could make this a log flood: say it once, then every 100th time. */
	private static void warn(final String type) {
		UiModel.bad.incrementAndGet();
		if (warned.getAndIncrement() % 100 == 0) {
			Cyberblock.LOG.warn("ui: bad {} dropped (seen {} bad UI messages)", type, UiModel.bad.get());
		}
	}

	private static void onClient(final Runnable task) {
		Minecraft.getInstance().execute(task);
	}
}
