package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.BlockEvents.Op;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/** One host's share of the block changes (plan 2026-10-04 task 1-W1): resync order and routing by distance. */
class BlockFeedTest {
	private static long cell(final int x, final int y, final int z) {
		return BlockPos.asLong(x, y, z);
	}

	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	private static List<String> types(final List<String> sent) {
		return sent.stream().map(m -> json(m).get("t").getAsString()).toList();
	}

	@Test
	void startSendsAlignImmediatelyFollowedByTheReset() {
		BlockFeed feed = new BlockFeed();
		List<String> sent = new ArrayList<>();
		feed.start(57, new long[] {cell(1, 64, 1), cell(200, 64, 0)}, 0, 64, 0, sent::add);
		assertEquals(List.of("align", "blocks"), types(sent), "align, then the reset, nothing between them");
		assertEquals(57, json(sent.get(0)).get("k").getAsInt());
		JsonObject reset = json(sent.get(1));
		assertTrue(reset.get("reset").getAsBoolean());
		assertEquals(1, reset.getAsJsonArray("c").get(0).getAsInt(), "the cell nearest to Steve first");
	}

	@Test
	void helloWaitsForTheHostToBeActiveThenAllInOneTick() {
		BlockFeed feed = new BlockFeed();
		feed.hello();
		assertFalse(feed.resyncWanted(false), "a host still loading or paused cannot drain it");
		assertTrue(feed.resyncWanted(true));
		List<String> sent = new ArrayList<>();
		feed.start(3, new long[0], 0, 64, 0, sent::add);
		feed.tick(true, 0, 0, sent::add);
		assertEquals(List.of("align", "blocks"), types(sent));
		assertFalse(feed.resyncWanted(true), "one resync per hello");
	}

	/**
	 * Plan section 1.3: past the DOWN queue's bound the oldest droppable message goes and a blocks resync is scheduled. WorldBridge.tick
	 * turns HostSession.takeResync() into hello(): a resync that is under way, and the live changes queued, start over from align and
	 * the reset on the next active tick.
	 */
	@Test
	void aDownQueueOverflowRestartsTheResyncOnTheNextActiveTick() {
		long[] world = new long[BlockSync.PER_MESSAGE * 3];
		for (int i = 0; i < world.length; i++) {
			world[i] = cell(i, 64, 0);
		}

		BlockFeed feed = new BlockFeed();
		List<String> sent = new ArrayList<>();
		feed.tick(true, 0, 0, sent::add);
		feed.start(9, world.clone(), 0, 64, 0, sent::add);
		feed.change(cell(1, 70, 1), Op.ADD, true, 0, 0);
		feed.tick(true, 0, 0, sent::add);
		assertFalse(feed.resyncWanted(true), "under way, nothing lost yet");

		feed.hello(); // the session's queue dropped some of those messages
		assertFalse(feed.resyncWanted(false), "it waits for the host to be active");
		assertTrue(feed.resyncWanted(true));
		sent.clear();
		feed.start(9, world.clone(), 0, 64, 0, sent::add);
		feed.tick(true, 0, 0, sent::add);
		assertEquals(List.of("align", "blocks"), types(sent), "align then the reset again; the stale live change is not replayed");
		assertTrue(json(sent.get(1)).get("reset").getAsBoolean());
		assertFalse(feed.resyncWanted(true), "one resync per overflow");
	}

	@Test
	void nearChangesGoAtOnceFarOnesWhenThePlayerComesWithinRange() {
		BlockFeed feed = new BlockFeed();
		List<String> sent = new ArrayList<>();
		feed.tick(true, 0, 0, sent::add);
		feed.change(cell(BlockFeed.RANGE, 70, 0), Op.ADD, true, 0, 0);
		feed.change(cell(BlockFeed.RANGE + 1, 70, 0), Op.ADD, true, 0, 0);
		feed.tick(true, 0, 0, sent::add);
		assertEquals(1, sent.size(), "only the cell within " + BlockFeed.RANGE + " blocks");
		assertEquals(BlockFeed.RANGE, json(sent.get(0)).getAsJsonArray("pos").get(0).getAsInt());
		assertEquals(1, feed.kept());

		sent.clear();
		for (int i = 0; i < BlockFeed.FAR_CHECK_TICKS; i++) {
			feed.tick(true, 100, 0, sent::add); // Steve walked toward it
		}

		assertEquals(1, sent.size());
		assertEquals(BlockFeed.RANGE + 1, json(sent.get(0)).getAsJsonArray("pos").get(0).getAsInt());
		assertEquals(0, feed.kept());
	}

	@Test
	void distanceIsHorizontal() {
		assertTrue(BlockFeed.near(cell(0, -60, 0), 0, 0));
		assertTrue(BlockFeed.near(cell(181, 300, 181), 0, 0)); // 255.97 apart horizontally, whatever the height
		assertFalse(BlockFeed.near(cell(182, 64, 181), 0, 0));
	}

	@Test
	void aChangeWhileNotActiveOwesAResyncOnActivation() {
		BlockFeed feed = new BlockFeed();
		feed.tick(true, 0, 0, s -> {});
		feed.change(cell(1, 64, 1), Op.REMOVE, false, 0, 0);
		feed.tick(false, 0, 0, s -> {});
		assertFalse(feed.resyncWanted(false));
		assertTrue(feed.resyncWanted(true), "it never heard of the change");
	}

	@Test
	void queuedChangesAreDroppedForAResyncWhenTheHostStopsListening() {
		BlockFeed feed = new BlockFeed();
		List<String> sent = new ArrayList<>();
		feed.tick(true, 0, 0, sent::add);
		feed.change(cell(1, 64, 1), Op.ADD, true, 0, 0);
		feed.tick(false, 0, 0, sent::add);
		assertTrue(sent.isEmpty(), "no stale replay");
		assertTrue(feed.resyncWanted(true));
	}

	@Test
	void tooManyKeptChangesBecomeAResync() {
		BlockFeed feed = new BlockFeed();
		feed.tick(true, 0, 0, s -> {});
		for (int i = 0; i <= BlockFeed.MAX_FAR; i++) {
			feed.change(cell(10_000 + i % 1000, 64, i / 1000), Op.ADD, true, 0, 0);
		}

		assertEquals(0, feed.kept());
		assertTrue(feed.resyncWanted(true));
	}
}
