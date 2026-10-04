package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * The world track on per-host sessions (plan 2026-10-04 task 1-W1): owner-scoped proxy ids, per-session barriers and their end
 * cleanup, the guest toggle gate and the explosion reach.
 */
class SessionsTest {
	private static final UUID HOST1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID HOST2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
	private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-000000000003");

	private static long cell(final int x, final int y, final int z) {
		return BlockPos.asLong(x, y, z);
	}

	@Test
	void twoOwnersCanUseTheSameNightCityId() {
		ProxyFleet.ByOwner<String> fleets = new ProxyFleet.ByOwner<>();
		fleets.of(HOST1).proxies.put(7L, "host1's NPC 7");
		fleets.of(HOST2).proxies.put(7L, "host2's NPC 7");
		assertEquals("host1's NPC 7", fleets.get(HOST1, 7L));
		assertEquals("host2's NPC 7", fleets.get(HOST2, 7L));
		assertNull(fleets.get(GUEST, 7L), "a guest owns no Night City ids");
		assertNull(fleets.get(null, 7L), "an id without its owner means nothing");
	}

	@Test
	void anOwnersEndTakesOnlyItsOwnProxies() {
		ProxyFleet.ByOwner<String> fleets = new ProxyFleet.ByOwner<>();
		fleets.of(HOST1).proxies.put(7L, "a");
		fleets.of(HOST2).proxies.put(7L, "b");
		ProxyFleet<String> gone = fleets.remove(HOST1);
		assertNotNull(gone);
		assertEquals("a", gone.proxies.get(7L));
		assertNull(fleets.get(HOST1, 7L));
		assertEquals("b", fleets.get(HOST2, 7L));
		assertNull(fleets.remove(HOST1), "ended once");
	}

	@Test
	void aSharedBarrierGoesOnlyWhenTheLastHostLetsGo() {
		Claims claims = new Claims();
		long c = cell(10, 64, -3);
		assertTrue(claims.claim(HOST1, c), "a new barrier");
		assertFalse(claims.claim(HOST2, c), "already standing: no second block");
		assertFalse(claims.release(HOST1, c), "host2 still reports it");
		assertTrue(claims.claimed(c));
		assertFalse(claims.release(HOST1, c), "host1 had let go already");
		assertTrue(claims.release(HOST2, c), "the last one: the barrier goes");
		assertFalse(claims.claimed(c));
		assertFalse(claims.release(GUEST, c));
	}

	@Test
	void endCleanupRemovesOnlyTheCellsNoOtherHostReports() {
		Claims claims = new Claims();
		long mine = cell(0, 64, 0), shared = cell(1, 64, 0), theirs = cell(2, 64, 0);
		claims.claim(HOST1, mine);
		claims.claim(HOST1, shared);
		claims.claim(HOST2, shared);
		claims.claim(HOST2, theirs);
		assertArrayEquals(new long[] {mine}, claims.releaseAll(HOST1));
		assertTrue(claims.claimed(shared));
		assertTrue(claims.claimed(theirs));
		assertFalse(claims.claimed(mine));
		assertEquals(0, claims.releaseAll(HOST1).length, "ended once");
		long[] rest = claims.releaseAll(HOST2);
		Arrays.sort(rest);
		long[] expected = {shared, theirs};
		Arrays.sort(expected);
		assertArrayEquals(expected, rest);
		assertEquals(0, claims.size());
	}

	@Test
	void clearForgetsEveryClaim() {
		Claims claims = new Claims();
		claims.claim(HOST1, cell(0, 64, 0));
		claims.claim(HOST2, cell(0, 64, 0));
		claims.claim(HOST2, cell(5, 64, 0));
		assertEquals(2, claims.clear().length);
		assertEquals(0, claims.size());
		assertEquals(0, claims.releaseAll(HOST2).length);
	}

	@Test
	void onlyAnotherPlayersActionIsAGuestsAction() {
		assertFalse(WorldModule.byGuest(HOST1, null), "a mob, a lone creeper, lava: no guest behind it");
		assertFalse(WorldModule.byGuest(HOST1, HOST1), "the host's own actions are always allowed");
		assertTrue(WorldModule.byGuest(HOST1, GUEST));
		assertTrue(WorldModule.byGuest(HOST1, HOST2), "to host1, host2 is a guest");
	}

	@Test
	void explosionsReachHostsWithin128Blocks() {
		assertTrue(ExplosionEvents.reaches(128.0, 0.0, 0.0));
		assertTrue(ExplosionEvents.reaches(-90.0, 0.0, 90.0));
		assertFalse(ExplosionEvents.reaches(90.0, 20.0, 90.0));
		assertFalse(ExplosionEvents.reaches(0.0, 0.0, 128.5));
	}

	@Test
	void pteleportIsTheProtocolsShape() {
		var m = JsonParser.parseString(Wire.pteleport(1.5, 64.0, -2.25)).getAsJsonObject();
		assertEquals("pteleport", m.get("t").getAsString());
		assertEquals(-2.25, m.getAsJsonArray("pos").get(2).getAsDouble());
		assertTrue(Wire.pteleport(Double.NaN, 0.0, 0.0).contains("[0.000,"), "never a NaN on the wire");
	}
}
