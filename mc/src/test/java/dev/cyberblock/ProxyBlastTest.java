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
}
