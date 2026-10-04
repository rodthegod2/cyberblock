package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Megaplan task 5-F1: a person who dies right next to an explosion that hurt V died in it (explosion particles on the body). */
class ProxyBlastTest {
	@Test
	void aNearAndRecentExplosionIsTheCause() {
		ProxyManager.Blast blast = new ProxyManager.Blast(new Vec3(10, 64, 10), 1_000L);
		assertTrue(ProxyManager.blownBy(blast, new Vec3(15, 64, 10), 1_000L + 1_500_000_000L));
		assertFalse(ProxyManager.blownBy(blast, new Vec3(25, 64, 10), 1_000L), "too far");
		assertFalse(ProxyManager.blownBy(blast, new Vec3(10, 64, 10), 1_000L + ProxyManager.BLAST_NANOS + 1L), "too long ago");
		assertFalse(ProxyManager.blownBy(null, new Vec3(10, 64, 10), 1_000L), "no explosion at all");
	}

	/** ProxyEntity.die's own test: only an explosion that reached the proxy within 40 ticks makes it blown. */
	@Test
	void aProxyNeverHitByAnExplosionIsNotBlown() {
		for (int now : new int[] {0, 100, 72_000, Integer.MAX_VALUE}) {
			assertFalse(ProxyEntity.blastRecent(now, ProxyEntity.NO_BLAST), "never hit, tick " + now);
		}
		assertTrue(ProxyEntity.blastRecent(500, 500), "the same tick");
		assertTrue(ProxyEntity.blastRecent(500 + ProxyEntity.BLAST_TICKS, 500), "40 ticks later");
		assertFalse(ProxyEntity.blastRecent(500 + ProxyEntity.BLAST_TICKS + 1, 500), "41 ticks later");
		assertTrue(ProxyEntity.blastRecent(10, 0), "hit on its first tick");
	}
}
