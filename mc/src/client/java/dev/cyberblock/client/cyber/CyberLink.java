package dev.cyberblock.client.cyber;

import com.google.gson.JsonObject;
import dev.cyberblock.Bridge;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Track C's host message listeners (plan section 1.6): HostLink hands them every `hello`, `state`, `key`, `scaninfo`,
 * `hackresult` and `killed` on the link thread, after its own handling. Each moves its work to the client or the
 * integrated server thread.
 */
final class CyberLink {
	private CyberLink() {
	}

	static void register() {
		listen("hello", m -> onClient(CyberKeys::sendBinds));
		listen("key", CyberLink::key);
	}

	private static void listen(final String type, final Consumer<JsonObject> listener) {
		Bridge.listeners.put(type, listener);
	}

	private static void onClient(final Runnable task) {
		Minecraft.getInstance().execute(task);
	}

	/** {"t":"key","k":...,"down":bool}: track C handles hack and skills (sprint arrives with the Sandevistan, task C7). */
	private static void key(final JsonObject m) {
		String k = m.get("k").getAsString();
		boolean down = !m.has("down") || m.get("down").getAsBoolean();
		KeyMapping mapping = switch (k) {
			case "hack" -> CyberKeys.hack;
			case "skills" -> CyberKeys.skills;
			default -> null;
		};
		if (mapping != null) {
			Minecraft minecraft = Minecraft.getInstance();
			onClient(() -> CyberKeys.onHostKey(minecraft, mapping, down));
		}
	}
}
