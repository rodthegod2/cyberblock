package dev.cyberblock.device;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Plan 4-M1: vending shops (4A's schema), the price in eddies and chips, row limits and restocks. */
class ShopsTest {
	private static final Identifier DRINKS = Identifier.fromNamespaceAndPath("cyberblock", "vending_drinks"),
		SNACKS = Identifier.fromNamespaceAndPath("cyberblock", "vending_snacks");
	private static final String DRINKS_FILE = "{\"schema\": 1, \"title_key\": \"cyberblock.shop.vending_drinks\", \"restock\": 600, \"stock\": ["
		+ "{\"item\": \"minecraft:melon_slice\", \"count\": 4, \"price\": 3, \"limit\": 16}, {\"item\": \"minecraft:golden_carrot\", \"count\": 1, "
		+ "\"price\": 25, \"limit\": 4}, {\"item\": \"minecraft:honey_bottle\", \"count\": 1, \"price\": 8}]}";

	@AfterEach
	void noShops() {
		Shops.use(Map.of());
	}

	@Test
	void fourAsShopFileParses() {
		Shops.Shop s = Shops.parse(DRINKS, JsonParser.parseString(DRINKS_FILE).getAsJsonObject());
		assertEquals("cyberblock.shop.vending_drinks", s.titleKey());
		assertEquals(600, s.restockSeconds());
		assertEquals(new Shops.Row("minecraft:golden_carrot", 1, 25, 4), s.stock().get(1));
		assertEquals(0, s.stock().get(2).limit(), "no limit");
	}

	@Test
	void badShopsAreRefused() {
		String[] bad = {"{\"schema\":1,\"title_key\":\"x\",\"stock\":[]}", "{\"schema\":1,\"title_key\":\"Bad Key\",\"stock\":[{\"item\":\"minecraft:apple\",\"count\":1,\"price\":1}]}",
			"{\"schema\":1,\"title_key\":\"x\",\"stock\":[{\"item\":\"minecraft:apple\",\"count\":65,\"price\":1}]}",
			"{\"schema\":1,\"title_key\":\"x\",\"stock\":[{\"item\":\"minecraft:apple\",\"count\":1,\"price\":0}]}",
			"{\"schema\":1,\"title_key\":\"x\",\"stock\":[{\"item\":\"apple\",\"count\":1,\"price\":1}]}"};
		for (String s : bad) {
			assertThrows(IllegalArgumentException.class, () -> Shops.parse(DRINKS, JsonParser.parseString(s).getAsJsonObject()), s);
		}
	}

	@Test
	void aShopWireLineIdIsItsRow() {
		assertEquals(3, Vending.row("3"));
		assertEquals(23, Vending.row("23"));
		assertEquals(-1, Vending.row("24"), "past the last row");
		assertEquals(-1, Vending.row("nicola"));
		assertEquals(-1, Vending.row(null));
	}

	@Test
	void eddiesFirstThenChipsWithChangeBack() {
		assertEquals(new Shops.Payment(25, 0, 0), Shops.Payment.of(64, 3, 25));
		assertEquals(new Shops.Payment(10, 1, 85), Shops.Payment.of(10, 3, 25), "10 eddies and one chip, 85 back");
		assertEquals(new Shops.Payment(0, 2, 50), Shops.Payment.of(0, 2, 150));
		assertNull(Shops.Payment.of(20, 0, 25), "not enough");
		assertNull(Shops.Payment.of(Integer.MAX_VALUE, 0, 0), "nothing is free");
	}

	@Test
	void aMachineSellsAFixedShopAndCountsItsLimitsUntilItRestocks() {
		Shops.Shop drinks = Shops.parse(DRINKS, JsonParser.parseString(DRINKS_FILE).getAsJsonObject());
		Shops.use(Map.of(DRINKS, drinks));
		assertEquals(DRINKS, Shops.pick(List.of("cyberblock:vending_snacks", "cyberblock:vending_drinks"), 12345L), "a missing shop is skipped");
		assertNull(Shops.pick(List.of("cyberblock:vending_snacks"), 1L));
		Shops.use(Map.of(DRINKS, drinks, SNACKS, drinks));
		for (long id = 0; id < 20; id++) {
			assertEquals(Shops.pick(List.of("cyberblock:vending_snacks", "cyberblock:vending_drinks"), id),
				Shops.pick(List.of("cyberblock:vending_snacks", "cyberblock:vending_drinks"), id), "the same machine, the same shop");
		}

		Shops sales = new Shops();
		assertEquals(4, sales.left("m1", drinks, 1, 0));
		sales.sold("m1", drinks, 1, 3, 0);
		assertEquals(1, sales.left("m1", drinks, 1, 1000));
		assertEquals(4, sales.left("m2", drinks, 1, 1000), "each machine counts its own");
		assertEquals(Integer.MAX_VALUE, sales.left("m1", drinks, 2, 1000));
		assertEquals(4, sales.left("m1", drinks, 1, 600_000), "restocked after 600 s");
	}
}
