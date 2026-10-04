package dev.cyberblock.net.admin;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class HostNamesTest {
	@Test
	void acceptsPlayerNamesAsTyped() {
		assertEquals("CbHost1", HostNames.normalize("CbHost1"));
		assertEquals("abc", HostNames.normalize("  abc "));
		assertEquals("a_b_c_d_e_f_g_h1", HostNames.normalize("a_b_c_d_e_f_g_h1"));
	}

	@Test
	void acceptsUuidsInCanonicalLowerCase() {
		assertEquals("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0", HostNames.normalize("0F1E2D3C-4B5A-6978-8796-A5B4C3D2E1F0"));
	}

	@Test
	void refusesEverythingElse() {
		for (String bad : new String[] {null, "", "ab", "seventeen_chars_x", "bad name", "évé", "a-b", "../x", "\"x\"", "0f1e2d3c4b5a69788796a5b4c3d2e1f0",
			"0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1fz"}) {
			assertNull(HostNames.normalize(bad), String.valueOf(bad));
		}
	}

	@Test
	void sameEntryIgnoresNameCase() {
		assertTrue(HostNames.same("CbHost1", "cbhost1"));
		assertFalse(HostNames.same("CbHost1", "CbHost2"));
	}
}
