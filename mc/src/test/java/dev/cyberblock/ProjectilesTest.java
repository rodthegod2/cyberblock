package dev.cyberblock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProjectilesTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void gravityInMetresPerSecondSquared() {
		assertEquals(12.0, Projectiles.gravity(0.03), 1e-9); // ender pearl, snowball
		assertEquals(20.0, Projectiles.gravity(0.05), 1e-9); // arrow, trident, potion
		assertEquals(0.0, Projectiles.gravity(0.0)); // wind charge, rocket
		assertEquals(40.0, Projectiles.gravity(1.0)); // the protocol's cap
		assertEquals(0.0, Projectiles.gravity(-0.05));
		assertEquals(0.0, Projectiles.gravity(Double.NaN));
	}

	@Test
	void projJson() {
		List<Projectiles.Flying> all = new ArrayList<>();
		for (int i = 20; i > 0; i--) {
			all.add(new Projectiles.Flying(i, i, 70.0, -i, 30.0, 1.5, 0.0, 12.0, i * i));
		}

		JsonObject m = json(Wire.proj(Projectiles.nearest(all)));
		assertEquals("proj", m.get("t").getAsString());
		JsonArray list = m.getAsJsonArray("list");
		assertEquals(16, list.size());
		JsonObject first = list.get(0).getAsJsonObject();
		assertEquals(1, first.get("id").getAsInt());
		assertEquals(70.0, first.getAsJsonArray("p").get(1).getAsDouble());
		assertEquals(30.0, first.getAsJsonArray("v").get(0).getAsDouble());
		assertEquals(12.0, first.get("g").getAsDouble());
		assertEquals(16, list.get(15).getAsJsonObject().get("id").getAsInt());
		assertEquals("{\"t\":\"proj\",\"list\":[]}", Wire.proj(List.of()));
	}

	@Test
	void projJsonStaysInTheProtocolLimits() {
		JsonObject e = json(Wire.proj(List.of(new Projectiles.Flying(3, 0.0, 0.0, 0.0, 0.0, -900.0, 0.0, 99.0, 0.0)))).getAsJsonArray("list").get(0).getAsJsonObject();
		assertEquals(-200.0, e.getAsJsonArray("v").get(1).getAsDouble());
		assertEquals(40.0, e.get("g").getAsDouble());
	}

	@Test
	void teleportJson() {
		JsonObject m = json(Wire.teleport(10.5, 80.0, -3.25, 135.0));
		assertEquals("teleport", m.get("t").getAsString());
		assertEquals(80.0, m.getAsJsonArray("p").get(1).getAsDouble());
		assertEquals(135.0, m.get("yaw").getAsDouble());
	}

	@Test
	void pcellsParse() {
		assertArrayEquals(new int[] {1, 64, -2, 3, 65, 4}, Projectiles.cells(json("{'t':'pcells','c':[1,64,-2,3,65,4]}")));
		assertArrayEquals(new int[0], Projectiles.cells(json("{'c':[]}")));
		// a bad triple is skipped, the rest stays; a trailing partial one too
		assertArrayEquals(new int[] {3, 65, 4}, Projectiles.cells(json("{'c':[1.5,64,-2,3,65,4,7]}")));
		assertArrayEquals(new int[] {3, 65, 4}, Projectiles.cells(json("{'c':['1',64,-2,3,65,4]}")));
		assertArrayEquals(new int[0], Projectiles.cells(json("{'c':[3e7,64,0,0,NaN,0]}")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{'t':'pcells'}", "{'c':5}", "{'c':{}}"})
	void pcellsWithoutAListIsDropped(final String m) {
		assertNull(Projectiles.cells(json(m)));
	}

	@Test
	void pcellsOver64CellsIsDropped() {
		StringBuilder b = new StringBuilder("{'c':[0,0,0");
		for (int i = 1; i < 65; i++) {
			b.append(",").append(i).append(",0,0");
		}

		assertNull(Projectiles.cells(json(b.append("]}").toString())));
		assertEquals(64 * 3, Projectiles.cells(json(b.toString().replace(",64,0,0]}", "]}"))).length);
	}

	@Test
	void pcellsBarriersLastThreeSeconds() {
		TempCells cells = new TempCells();
		long a = BlockPos.asLong(1, 64, 1), b = BlockPos.asLong(2, 64, 1);
		cells.put(a, 100);
		cells.put(b, 120);
		assertArrayEquals(new long[0], cells.expire(159));
		assertArrayEquals(new long[] {a}, cells.expire(160));
		assertFalse(cells.contains(a));
		cells.put(b, 150); // still on a path: kept longer
		assertArrayEquals(new long[0], cells.expire(180));
		assertArrayEquals(new long[] {b}, cells.expire(210));
		assertEquals(0, cells.size());
	}

	@Test
	void groundTheProbesReportStays() {
		TempCells cells = new TempCells();
		long a = BlockPos.asLong(1, 64, 1);
		cells.put(a, 0);
		assertTrue(cells.promote(a));
		assertFalse(cells.promote(a));
		assertArrayEquals(new long[0], cells.expire(1000));
	}

	@Test
	void clearTakesThemAll() {
		TempCells cells = new TempCells();
		cells.put(1L, 0);
		cells.put(2L, 0);
		long[] all = cells.clear();
		assertEquals(2, all.length);
		assertEquals(0, cells.size());
	}
}
