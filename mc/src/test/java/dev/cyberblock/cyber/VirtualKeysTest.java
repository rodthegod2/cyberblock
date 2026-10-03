package dev.cyberblock.cyber;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.client.cyber.VirtualKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VirtualKeysTest {
	// SDL scancodes, as InputConstants.KEY_* in 26.3
	private static final int SC_A = 4, SC_B = 5, SC_G = 10, SC_K = 14, SC_L = 15, SC_U = 24, SC_Y = 28, SC_1 = 30, SC_9 = 38, SC_0 = 39;
	private static final int SC_F1 = 58, SC_F5 = 62, SC_F9 = 66, SC_F12 = 69, SC_ENTER = 40, SC_ESCAPE = 41, SC_SPACE = 44, SC_F13 = 104;
	private static final int SC_LCTRL = 224, SC_LSHIFT = 225, SC_RCTRL = 228, SC_RSHIFT = 229;
	/** The keys the hack and skill-tree keys may be rebound to (the final key table): everything else stands for the default. */
	private static final Set<Integer> ALLOWED = Set.of((int) 'G', (int) 'K', (int) 'L', (int) 'O', (int) 'U', (int) 'Y', (int) '0',
		0x70, 0x71, 0x72, 0x73, 0x75, 0x76, 0x77, 0x79, 0x7A, 0x7B);

	@Test
	void letters() {
		assertEquals(0x47, VirtualKeys.of(true, SC_G));
		assertEquals(0x4B, VirtualKeys.of(true, SC_K));
		assertEquals(0x4C, VirtualKeys.of(true, SC_L));
		assertEquals(0x59, VirtualKeys.of(true, SC_Y));
	}

	@Test
	void digits() {
		assertEquals(0x30, VirtualKeys.of(true, SC_0));
	}

	@Test
	void functionKeys() {
		assertEquals(0x70, VirtualKeys.of(true, SC_F1));
		assertEquals(0x7B, VirtualKeys.of(true, SC_F12));
	}

	/** K5: W A S D C E Q T X F I M J H N B P V Z R, 1-9, F5 and F9 are refused; G K L O U Y 0 F1-F4 F6-F8 F10-F12 are not. */
	@Test
	void exactlyTheKeyTablesKeysAreBindable() {
		int checked = 0;
		for (int vk : named()) {
			assertEquals(ALLOWED.contains(vk) ? vk : -1, VirtualKeys.of(true, scancode(vk)), "virtual key 0x" + Integer.toHexString(vk));
			checked++;
		}

		assertEquals(48, checked);
	}

	@Test
	void keysTheDllRefusesCannotBeForwarded() {
		for (int sc : new int[]{SC_A, SC_B, SC_1, SC_9, SC_F5, SC_F9, scancode('X'), scancode('F'), scancode('R')}) {
			assertEquals(-1, VirtualKeys.of(true, sc), "scancode " + sc);
		}
	}

	@Test
	void nothingElseCanBeForwarded() {
		for (int sc : new int[]{SC_ENTER, SC_ESCAPE, SC_SPACE, SC_F13, SC_LSHIFT, SC_RSHIFT, SC_LCTRL, SC_RCTRL, 0, -1, 3}) {
			assertEquals(-1, VirtualKeys.of(true, sc), "scancode " + sc);
		}

		// a mouse button bound to the mapping: Cyberpunk cannot forward it either
		assertEquals(-1, VirtualKeys.of(false, SC_G));
		assertEquals(-1, VirtualKeys.of(false, 1));
	}

	private static String pair(final int hack, final int skills) {
		int[] keys = VirtualKeys.binds(hack, skills);
		return VirtualKeys.name(keys[0]) + VirtualKeys.name(keys[1]);
	}

	/** keys-8c: what `binds` sends. A key Cyberpunk cannot forward is its default; a pair that would clash is G and K. */
	@Test
	void bindsUseTheDefaultsForRefusedOrClashingKeys() {
		int g = VirtualKeys.VK_G, k = VirtualKeys.VK_K, l = 0x4C, o = 0x4F, f10 = 0x79;
		assertEquals("GK", pair(g, k));
		assertEquals("LO", pair(l, o));
		assertEquals("KG", pair(k, g)); // swapped is fine: still two keys
		assertEquals("F10K", pair(f10, -1));
		assertEquals("GL", pair(-1, l));
		assertEquals("GK", pair(-1, -1));
		// clashes: the same key twice, or a refused hack key whose default G is the skill key (and the other way round)
		assertEquals("GK", pair(l, l));
		assertEquals("GK", pair(-1, g));
		assertEquals("GK", pair(k, -1));
		assertTrue(VirtualKeys.clash(l, l) && VirtualKeys.clash(-1, g) && VirtualKeys.clash(k, -1));
		assertTrue(!VirtualKeys.clash(-1, -1) && !VirtualKeys.clash(k, g) && !VirtualKeys.clash(-1, l));
	}

	private static String four(final int hack, final int skills, final int cyberware, final int activate) {
		StringBuilder s = new StringBuilder();
		for (int vk : VirtualKeys.binds(hack, skills, cyberware, activate)) {
			s.append(VirtualKeys.name(vk));
		}

		return s.toString();
	}

	/**
	 * Plan retest2 section 1.8, the same cases as the DLL's keys test: each key Cyberpunk cannot forward (-1) stands for its own
	 * default (G K U Y); if any two of the four resolved keys are equal, all four go back to G K U Y.
	 */
	@Test
	void fourKeysFallBackOneByOneAndAllOnAClash() {
		int g = VirtualKeys.VK_G, k = VirtualKeys.VK_K, u = VirtualKeys.VK_U, y = VirtualKeys.VK_Y, l = 0x4C, o = 0x4F, zero = 0x30, f10 = 0x79;
		assertEquals("GKUY", four(g, k, u, y));
		assertEquals("GKUY", four(-1, -1, -1, -1));
		assertEquals("LO0F10", four(l, o, zero, f10));
		assertEquals("YUKG", four(y, u, k, g)); // a permutation of the defaults is still four keys
		// every single refusal: that key alone becomes its default
		assertEquals("GO0F10", four(-1, o, zero, f10));
		assertEquals("LK0F10", four(l, -1, zero, f10));
		assertEquals("LOUF10", four(l, o, -1, f10));
		assertEquals("LO0Y", four(l, o, zero, -1));
		// clashes: two equal keys, or a refused key whose default another key uses
		assertEquals("GKUY", four(l, o, l, f10));
		assertEquals("GKUY", four(l, o, zero, zero));
		assertEquals("GKUY", four(y, k, u, -1)); // activate falls back to Y, which the hack key already is
		assertEquals("GKUY", four(-1, g, u, y));
		assertTrue(VirtualKeys.clash(l, o, l, f10) && VirtualKeys.clash(y, k, u, -1));
		assertTrue(!VirtualKeys.clash(l, o, zero, f10) && !VirtualKeys.clash(-1, -1, -1, -1) && !VirtualKeys.clash(y, u, k, g));
	}

	private static String six(final int... keys) {
		StringBuilder s = new StringBuilder();
		for (int vk : VirtualKeys.binds(keys)) {
			s.append(VirtualKeys.name(vk));
		}

		return s.toString();
	}

	/** Plan retest3 section 1.7: six keys G K U Y L O; the same fallback and the all-or-nothing clash over all six. */
	@Test
	void sixKeysFallBackOneByOneAndAllOnAClash() {
		int g = VirtualKeys.VK_G, k = VirtualKeys.VK_K, u = VirtualKeys.VK_U, y = VirtualKeys.VK_Y, l = VirtualKeys.VK_L, o = VirtualKeys.VK_O;
		int zero = 0x30, f10 = 0x79, f11 = 0x7A;
		assertArrayEquals(new int[]{g, k, u, y, l, o}, VirtualKeys.DEFAULTS);
		assertEquals("GKUYLO", six(g, k, u, y, l, o));
		assertEquals("GKUYLO", six(-1, -1, -1, -1, -1, -1));
		assertEquals("GKUY0F10", six(g, k, u, y, zero, f10)); // arms and camo moved
		assertEquals("GKUYF11O", six(g, k, u, y, f11, -1)); // camo refused: its own default
		// L and O used to be free for the hack and skill keys: now they clash with the arms and camo keys
		assertEquals("GKUYLO", six(l, o, u, y, l, o));
		assertTrue(VirtualKeys.clash(l, k, u, y, l, o));
		assertEquals("GKUYLO", six(g, k, u, y, -1, l)); // refused arms falls back to L, which camo already is
	}

	/** One `binds_apply` case: a name missing from `given` keeps the current key. */
	@Test
	void applyKeepsTheCurrentKeyForAMissingName() {
		int[] current = {0x30, VirtualKeys.VK_K, VirtualKeys.VK_U, VirtualKeys.VK_Y, VirtualKeys.VK_L, VirtualKeys.VK_O};
		VirtualKeys.Applied a = VirtualKeys.apply(new Integer[]{null, null, null, null, 0x79, null}, current);
		assertArrayEquals(new int[]{0x30, VirtualKeys.VK_K, VirtualKeys.VK_U, VirtualKeys.VK_Y, 0x79, VirtualKeys.VK_O}, a.keys());
		assertTrue(!a.clash());
		VirtualKeys.Applied refused = VirtualKeys.apply(new Integer[]{(int) 'W'}, current);
		assertEquals(VirtualKeys.VK_G, refused.keys()[0]);
		VirtualKeys.Applied clash = VirtualKeys.apply(new Integer[]{null, null, null, null, null, 0x30}, current);
		assertTrue(clash.clash());
		assertArrayEquals(VirtualKeys.DEFAULTS, clash.keys());
	}

	/** shared/vectors.json `binds_apply` (D2): the DLL's keys::apply_binds and VirtualKeys.apply agree on every case. */
	@Test
	void bindsApplyMatchesTheSharedVectors() throws IOException {
		JsonObject vectors = JsonParser.parseString(Files.readString(Path.of("..", "shared", "vectors.json"))).getAsJsonObject();
		JsonArray cases = vectors.getAsJsonArray("binds_apply");
		assertTrue(cases != null && cases.size() >= 8, "the vectors have a binds_apply set");
		for (JsonElement c : cases) {
			JsonObject o = c.getAsJsonObject();
			JsonObject given = o.getAsJsonObject("given");
			Integer[] g = new Integer[CyberWire.BIND_NAMES.size()];
			for (int i = 0; i < g.length; i++) {
				JsonElement e = given.get(CyberWire.BIND_NAMES.get(i));
				g[i] = e == null || e.isJsonNull() ? null : e.getAsInt();
			}

			VirtualKeys.Applied applied = VirtualKeys.apply(g, six(o.get("current")));
			assertArrayEquals(six(o.get("expect")), applied.keys(), o.toString());
			assertEquals(o.get("clash").getAsBoolean(), applied.clash(), o.toString());
		}
	}

	/** Six keys in `binds` order, from {"hack":vk,...} or [vk, ...]. */
	private static int[] six(final JsonElement e) {
		int[] out = new int[CyberWire.BIND_NAMES.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = e.isJsonArray() ? e.getAsJsonArray().get(i).getAsInt() : e.getAsJsonObject().get(CyberWire.BIND_NAMES.get(i)).getAsInt();
		}

		return out;
	}

	/** U and Y are bindable (the DLL forwards them while Cyberblock is active). */
	@Test
	void theNewDefaultsAreForwardable() {
		assertEquals(VirtualKeys.VK_U, VirtualKeys.of(true, SC_U));
		assertEquals(VirtualKeys.VK_Y, VirtualKeys.of(true, SC_Y));
	}

	/** A..Z, 0..9 and F1..F12 as virtual keys. */
	private static int[] named() {
		int[] vks = new int[48];
		int i = 0;
		for (int vk = 0x41; vk <= 0x5A; vk++) {
			vks[i++] = vk;
		}

		for (int vk = 0x30; vk <= 0x39; vk++) {
			vks[i++] = vk;
		}

		for (int vk = 0x70; vk <= 0x7B; vk++) {
			vks[i++] = vk;
		}

		return vks;
	}

	/** The SDL scancode of a virtual key: letters, digits, F1-F12, and the named keys the shared vectors may list. */
	private static int scancode(final int vk) {
		if (vk >= 0x41 && vk <= 0x5A) {
			return SC_A + vk - 0x41;
		}

		if (vk == 0x30) {
			return SC_0;
		}

		if (vk >= 0x31 && vk <= 0x39) {
			return SC_1 + vk - 0x31;
		}

		if (vk >= 0x70 && vk <= 0x7B) {
			return SC_F1 + vk - 0x70;
		}

		return switch (vk) {
			case 0x1B -> SC_ESCAPE;
			case 0x20 -> SC_SPACE;
			case 0x10, 0xA0 -> SC_LSHIFT; // VK_SHIFT, VK_LSHIFT
			case 0xA1 -> SC_RSHIFT;
			case 0x11, 0xA2 -> SC_LCTRL; // VK_CONTROL, VK_LCONTROL
			case 0xA3 -> SC_RCTRL;
			default -> throw new IllegalArgumentException("no scancode for virtual key 0x" + Integer.toHexString(vk));
		};
	}

	/**
	 * shared/vectors.json `binds`, which the DLL's keys::bindable is checked against too: the two lists agree. Package A
	 * regenerates the vectors for the final key table (K5); until it is merged this test fails on the newly refused letters.
	 */
	@Test
	void forwardableKeysMatchTheSharedBindsVectors() throws IOException {
		JsonObject vectors = JsonParser.parseString(Files.readString(Path.of("..", "shared", "vectors.json"))).getAsJsonObject();
		int cases = 0;
		for (JsonElement c : vectors.getAsJsonArray("binds")) {
			int vk = c.getAsJsonObject().get("vk").getAsInt();
			boolean bindable = c.getAsJsonObject().get("bindable").getAsBoolean();
			assertEquals(bindable ? vk : -1, VirtualKeys.of(true, scancode(vk)), "virtual key 0x" + Integer.toHexString(vk));
			cases++;
		}

		assertTrue(cases >= 48, "the vectors cover every letter, digit and F-key");
	}

	@Test
	void bindsJson() {
		assertEquals("{\"t\":\"binds\",\"keys\":{\"hack\":71,\"skills\":75,\"cyberware\":85,\"activate\":89,\"arms\":76,\"camo\":79}}",
			CyberWire.binds(VirtualKeys.DEFAULTS));
	}
}
