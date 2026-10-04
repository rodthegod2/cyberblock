package dev.cyberblock.client.settings;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Client-only debug switches, set with {@code /cyberblock debug aim|view on|off} (plan Q-B3). They live on the client because
 * what they show does: the aim log is this client's pick, the view overlay is this client's frame. Off at every start. Any thread
 * reads them.
 */
public final class DebugFlags {
	/** One parsed {@code /cyberblock debug <flag> <on|off>}; {@code on} null: report the current state. */
	public record Switch(String flag, @Nullable Boolean on) {
	}

	/** The switches, in the order the help line names them. */
	static final List<String> FLAGS = List.of("aim", "view");
	private static volatile boolean aim, view;

	private DebugFlags() {
	}

	/** {@code aim:} log lines every client tick (Q-B1): the pick, the host's aim distance, the limit, the attack key, destroy progress. */
	public static boolean aim() {
		return aim;
	}

	/** The view overlay and its 5 s log line (QV reads this and asks the DLL for {@code cam.vdbg} while it is on). */
	public static boolean view() {
		return view;
	}

	public static boolean get(final String flag) {
		return flag.equals("aim") ? aim : view;
	}

	public static void set(final String flag, final boolean on) {
		switch (flag) {
			case "aim" -> aim = on;
			case "view" -> view = on;
			default -> throw new IllegalArgumentException("no debug flag " + flag);
		}
	}

	/**
	 * The command as the chat screen sends it (no slash): {@code cyberblock debug aim on}. Null when it is not a debug switch this
	 * client knows (it then goes to the server as usual).
	 */
	public static @Nullable Switch parse(final String command) {
		String[] words = command.trim().split("\\s+");
		if (words.length < 3 || words.length > 4 || !words[0].equals("cyberblock") || !words[1].equals("debug") || !FLAGS.contains(words[2])) {
			return null;
		}

		if (words.length == 3) {
			return new Switch(words[2], null);
		}

		return switch (words[3]) {
			case "on" -> new Switch(words[2], true);
			case "off" -> new Switch(words[2], false);
			default -> null;
		};
	}
}
