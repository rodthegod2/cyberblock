package dev.cyberblock.device.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Megaplan 4-B1: the screens' devact request and the vending machine's shop message (DeviceHooks.parse, ShopWire). */
class DeviceWireTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	@Test
	void aScreenMayOnlyAskToBuyAtACell() {
		assertEquals(new DeviceHooks.Request(1, 64, -3, "buy"), DeviceHooks.parse(json("{\"pos\":[1,64,-3],\"act\":\"buy\",\"line\":\"x\"}")));
		// block uses come through the block's own use path, never from a screen
		assertNull(DeviceHooks.parse(json("{\"pos\":[1,64,-3],\"act\":\"open\"}")));
		assertNull(DeviceHooks.parse(json("{\"pos\":[1,64],\"act\":\"buy\"}")));
		assertNull(DeviceHooks.parse(json("{\"pos\":[1.5,64,0],\"act\":\"buy\"}")));
		assertNull(DeviceHooks.parse(json("{\"pos\":[1,64,1e9],\"act\":\"buy\"}")));
		assertNull(DeviceHooks.parse(json("{\"pos\":\"1 64 0\",\"act\":\"buy\"}")));
		assertNull(DeviceHooks.parse(json("{\"pos\":[1,64,0],\"act\":7}")));
		assertNull(DeviceHooks.parse(json("{\"act\":\"buy\"}")));
		assertNull(DeviceHooks.parse(json("{\"pos\":[\"a\",64,0],\"act\":\"buy\"}")));
	}

	@Test
	void theShopGoesThereAndBack() {
		ShopWire.Shop shop = new ShopWire.Shop(4, 70, -2, "Nicola corner", 135, List.of(
			new ShopWire.Line("nicola", "minecraft:honey_bottle", 1, 15, -1),
			new ShopWire.Line("chips", "minecraft:cookie", 4, 5, 0)), "poor");
		assertEquals(shop, ShopWire.parseShop(json(ShopWire.shop(shop))));
		String buy = ShopWire.buy(4, 70, -2, "nicola");
		assertEquals("nicola", ShopWire.buyLine(json(buy)));
		assertEquals(new DeviceHooks.Request(4, 70, -2, "buy"), DeviceHooks.parse(json(buy)));
	}

	@Test
	void aShopOutOfItsLimitsIsDropped() {
		String good = "{\"pos\":[0,0,0],\"eddies\":0,\"lines\":[{\"id\":\"a\",\"item\":\"minecraft:apple\",\"n\":1,\"price\":0}]}";
		ShopWire.Shop parsed = ShopWire.parseShop(json(good));
		assertNotNull(parsed);
		assertEquals("", parsed.title());
		assertEquals(-1, parsed.lines().getFirst().stock());
		for (String bad : List.of(
			good.replace("\"n\":1", "\"n\":65"),
			good.replace("\"n\":1", "\"n\":0"),
			good.replace("\"price\":0", "\"price\":-1"),
			good.replace("\"price\":0", "\"price\":1000001"),
			good.replace("\"price\":0", "\"price\":0,\"stock\":-2"),
			good.replace("minecraft:apple", "Apple"),
			good.replace("\"id\":\"a\"", "\"id\":\"\""),
			good.replace("\"id\":\"a\"", "\"id\":\"" + "a".repeat(33) + "\""),
			good.replace("\"eddies\":0", "\"eddies\":-5"),
			good.replace("[0,0,0]", "[0,0]"),
			good.replace("{\"pos\"", "{\"result\":\"Not OK\",\"pos\""),
			good.replace("{\"pos\"", "{\"title\":\"" + "t".repeat(65) + "\",\"pos\""),
			"{\"pos\":[0,0,0],\"eddies\":0}")) {
			assertNull(ShopWire.parseShop(json(bad)), bad);
		}

		StringBuilder many = new StringBuilder("{\"pos\":[0,0,0],\"eddies\":0,\"lines\":[");
		for (int i = 0; i <= ShopWire.MAX_LINES; i++) {
			many.append(i == 0 ? "" : ",").append("{\"id\":\"l").append(i).append("\",\"item\":\"minecraft:apple\",\"n\":1,\"price\":1}");
		}

		assertNull(ShopWire.parseShop(json(many + "]}")));
	}

	@Test
	void aBuyNeedsALine() {
		assertNull(ShopWire.buyLine(json("{\"act\":\"buy\"}")));
		assertNull(ShopWire.buyLine(json("{\"line\":\"\"}")));
		assertNull(ShopWire.buyLine(json("{\"line\":3}")));
		assertNull(ShopWire.buyLine(json("{\"line\":\"" + "x".repeat(33) + "\"}")));
	}
}
