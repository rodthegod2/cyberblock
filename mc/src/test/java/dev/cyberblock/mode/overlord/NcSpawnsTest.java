package dev.cyberblock.mode.overlord;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Megaplan 8-O1 with Phase 0 (d) unconfirmed: the runtime check per host and its Minecraft-squad fallback, with one log line each. */
class NcSpawnsTest {
	private final List<String> log = new ArrayList<>();
	private final NcSpawns nc = new NcSpawns(this.log::add);
	private final UUID host = UUID.randomUUID();
	private final AtomicInteger fallbacks = new AtomicInteger(), placed = new AtomicInteger();

	private void send(final int id, final long now) {
		this.nc.sent(this.host, "Rod", id, now, this.fallbacks::incrementAndGet, this.placed::addAndGet);
	}

	@Test
	void aHeavyNcpdEntryGoesAsTheNcpdHeavyRecordAndHeavyChangesNoOtherRecord() {
		assertEquals("ncpd_heavy", NcSpawns.wireRec("ncpd", true));
		assertEquals("ncpd", NcSpawns.wireRec("ncpd", false));
		assertEquals("ganger", NcSpawns.wireRec("ganger", true));
		assertEquals("maxtac", NcSpawns.wireRec("maxtac", false));
	}

	@Test
	void anOkAnswerMarksTheHostWorking() {
		assertEquals(NcSpawns.Mode.UNKNOWN, this.nc.mode(this.host));
		this.send(1, 0);
		assertTrue(this.log.getFirst().contains("trying spawn DOWN"), this.log.toString());
		assertTrue(this.nc.answered(this.host, "Rod", 1, true, 3, ""));
		assertEquals(NcSpawns.Mode.WORKS, this.nc.mode(this.host));
		assertEquals(3, this.placed.get());
		assertEquals(0, this.fallbacks.get());
		assertTrue(this.log.getLast().contains("spawn DOWN active"));
		this.send(2, 1000);
		this.nc.answered(this.host, "Rod", 2, true, 2, "");
		assertEquals(2, this.log.size(), "one line per change of path, not per spawn");
	}

	@Test
	void noAnswerIn5SecondsFallsBackForThatHost() {
		this.send(1, 0);
		this.nc.tick(4_999, u -> "Rod");
		assertEquals(0, this.fallbacks.get());
		this.nc.tick(5_000, u -> "Rod");
		assertEquals(1, this.fallbacks.get(), "the waiting spawn becomes its Minecraft squad");
		assertEquals(NcSpawns.Mode.FALLBACK, this.nc.mode(this.host));
		assertFalse(this.nc.usable(this.host));
		assertTrue(this.log.getLast().contains("-> Minecraft squads (fallback)"), this.log.toString());
		assertFalse(this.nc.answered(this.host, "Rod", 1, true, 3, ""), "a late answer matches nothing");
	}

	@Test
	void unsupportedFallsBackButOtherRefusalsOnlyCostThatSpawn() {
		this.send(1, 0);
		this.nc.answered(this.host, "Rod", 1, false, 0, "no navmesh");
		assertEquals(1, this.fallbacks.get());
		assertTrue(this.nc.usable(this.host), "one bad spot does not switch the host off");
		this.send(2, 0);
		this.nc.answered(this.host, "Rod", 2, false, 0, "unsupported");
		assertEquals(2, this.fallbacks.get());
		assertFalse(this.nc.usable(this.host));
		assertTrue(this.log.getLast().contains("unsupported"));
	}

	@Test
	void aNewSessionAsksAgain() {
		this.send(1, 0);
		this.nc.tick(10_000, u -> "Rod");
		assertFalse(this.nc.usable(this.host));
		this.nc.reset(this.host);
		assertEquals(NcSpawns.Mode.UNKNOWN, this.nc.mode(this.host));
		this.send(2, 20_000);
		this.nc.reset(this.host);
		assertEquals(0, this.nc.waiting(), "an ended session's waiting spawns are dropped");
	}

	@Test
	void aWorkingHostIsNotSwitchedOffByOneLostAnswer() {
		this.send(1, 0);
		this.nc.answered(this.host, "Rod", 1, true, 3, "");
		this.send(2, 1_000);
		this.nc.tick(7_000, u -> "Rod");
		assertEquals(1, this.fallbacks.get());
		assertEquals(NcSpawns.Mode.WORKS, this.nc.mode(this.host));
	}

	@Test
	void answersAreKeptPerHost() {
		UUID other = UUID.randomUUID();
		this.send(1, 0);
		assertFalse(this.nc.answered(other, "Kim", 1, true, 3, ""), "the same id from another host is not this spawn's answer");
		assertEquals(1, this.nc.waiting());
	}
}
