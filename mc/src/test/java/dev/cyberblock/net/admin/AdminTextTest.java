package dev.cyberblock.net.admin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminTextTest {
	@Test
	void startupLineMatchesThePlan() {
		assertEquals("cyberblock server: ready (protocol 1, maxHosts 1, requireMod, whitelist on)", AdminText.startupLine(1, 1, true, true));
		assertEquals("cyberblock server: ready (protocol 1, maxHosts 4, modOptional, whitelist OFF)", AdminText.startupLine(1, 4, false, false));
	}

	@Test
	void joinHelpIsAppendedOnce() {
		assertEquals("Cyberblock: another Cyberpunk host is already active (max 1). Ask Rod.",
			AdminText.withJoinHelp("Cyberblock: another Cyberpunk host is already active (max 1).", "Ask Rod."));
		assertEquals("refused", AdminText.withJoinHelp("refused", ""));
		assertEquals("refused", AdminText.withJoinHelp("refused", "   "));
		assertEquals("This server needs the Cyberblock modpack: Ask Rod.", AdminText.withJoinHelp("This server needs the Cyberblock modpack: Ask Rod.", "Ask Rod."));
		assertEquals("", AdminText.withJoinHelp("", "Ask Rod."));
		assertNull(AdminText.withJoinHelp(null, "Ask Rod."));
	}

	@Test
	void joinHelpKeepsTheReasonCap() {
		String reason = "r".repeat(200);
		String out = AdminText.withJoinHelp(reason, "h".repeat(200));
		assertEquals(AdminText.REASON_CAP, out.length());
		assertTrue(out.startsWith(reason + " h"));
		String full = "x".repeat(AdminText.REASON_CAP);
		assertEquals(full, AdminText.withJoinHelp(full, "help"));
	}

	@Test
	void statusListsSessionsAndContent() {
		Map<String, Integer> content = new LinkedHashMap<>();
		content.put("material_palette", 12);
		content.put("test", 1);
		List<String> lines = AdminText.status(List.of(
			new AdminText.Row("CbHost1", "host", "active", "mod 1.0.0 dll 1.0.0", "up 12/s, dropped 0"),
			new AdminText.Row("CbGuest1", "guest", "", "mod 1.0.0", "")), 1, true, true, List.of("CbHost1"), content);
		assertEquals(List.of(
			"Cyberblock status: 2 online, 1 host (max 1), requireMod, whitelist on",
			"hosts allowed: CbHost1",
			"- CbHost1: host (active), mod 1.0.0 dll 1.0.0, up 12/s, dropped 0",
			"- CbGuest1: guest, mod 1.0.0",
			"content: material_palette 12, test 1"), lines);
	}

	@Test
	void statusSaysWhoMayHostWhenTheListIsEmpty() {
		List<String> lines = AdminText.status(List.of(), 1, false, false, List.of(), Map.of());
		assertEquals(List.of(
			"Cyberblock status: 0 online, 0 hosts (max 1), modOptional, whitelist OFF",
			"hosts allowed: any whitelisted player",
			"content: none loaded"), lines);
	}
}
