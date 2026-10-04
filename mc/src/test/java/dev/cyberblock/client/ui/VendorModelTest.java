package dev.cyberblock.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Megaplan 7-V1: a host's Cyberpunk vendor reply as the Minecraft vendor screen sees it, and the fast-travel points. */
class VendorModelTest {
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	private static final String GUN_SHOP = "{'t':'reply','kind':'vendor','id':7,"
		+ "'pins':[{'p':[10,64,-20],'kind':'vendor','name':'Gun Shop','i':-1}],'groups':["
		+ "{'hash':0,'title':'Weapons','kind':'other','tracked':false,'done':false,'lines':[{'hash':4000000000,'text':'Lexington','state':1250},{'hash':12,'text':'Unity','state':2300}]},"
		+ "{'hash':4,'title':'Consumables','kind':'other','tracked':false,'done':false,'lines':[{'hash':13,'text':'MaxDoc x3','state':80}]},"
		+ "{'hash':6,'title':'Other','kind':'other','tracked':false,'done':true,'lines':[{'hash':21,'text':'Junk x12','state':3}]}]}";

	@Test
	void vendorReplyKeepsPricesAndSplitsBuyFromSell() {
		UiModel.Reply reply = UiModel.parseReply(json(GUN_SHOP));
		assertNotNull(reply);
		VendorModel.Vendor v = VendorModel.of(reply);
		assertNotNull(v);
		assertEquals(7, v.session());
		assertEquals("Gun Shop", v.name());
		assertFalse(v.ripperdoc());
		assertEquals(List.of(new VendorModel.Item(0, 4000000000L, "Lexington", "Weapons", 1250), new VendorModel.Item(1, 12, "Unity", "Weapons", 2300),
			new VendorModel.Item(2, 13, "MaxDoc x3", "Consumables", 80)), v.buy());
		// the sell side counts its own indices from 0
		assertEquals(List.of(new VendorModel.Item(0, 21, "Junk x12", "Other", 3)), v.sell());
	}

	@Test
	void ripperdocAndNameFromTheFirstPin() {
		UiModel.Reply reply = UiModel.parseReply(json("{'kind':'vendor','id':1,'pins':[{'p':[0,0,0],'kind':'ripperdoc','name':'Viktor','i':-1}],'groups':[]}"));
		VendorModel.Vendor v = VendorModel.of(reply);
		assertTrue(v.ripperdoc());
		assertEquals("Viktor", v.name());
		assertTrue(v.buy().isEmpty() && v.sell().isEmpty());
		VendorModel.Vendor bare = VendorModel.of(UiModel.parseReply(json("{'kind':'vendor','id':2,'groups':[]}")));
		assertEquals("", bare.name());
		assertFalse(bare.ripperdoc());
	}

	@Test
	void onlyVendorRepliesAreVendors() {
		assertNull(VendorModel.of(UiModel.parseReply(json("{'kind':'journal','id':1,'groups':[],'pins':[]}"))));
	}

	@Test
	void vendorPricesMayExceedTheJournalStates() {
		// a price is any whole number of eddies; the journal's 0..2 state rule stays for the other kinds
		UiModel.Reply vendor = UiModel.parseReply(json("{'kind':'vendor','id':1,'groups':[{'hash':0,'title':'W','kind':'other','tracked':false,'done':false,"
			+ "'lines':[{'hash':1,'text':'a','state':2147483647},{'hash':2,'text':'b','state':-5},{'hash':3,'text':'c','state':1.5}]}]}"));
		assertEquals(1, vendor.groups().get(0).lines().size());
		assertEquals(2147483647, vendor.groups().get(0).lines().get(0).state());
		UiModel.Reply journal = UiModel.parseReply(json("{'kind':'journal','id':1,'groups':[{'hash':0,'title':'W','kind':'main','tracked':false,'done':false,"
			+ "'lines':[{'hash':1,'text':'a','state':1250}]}]}"));
		assertTrue(journal.groups().get(0).lines().isEmpty());
	}

	@Test
	void buyAndSellActions() {
		VendorModel.Item lexington = VendorModel.of(UiModel.parseReply(json(GUN_SHOP))).buy().get(0);
		JsonObject m = json(UiModel.uiact("buy", Integer.toString(VendorModel.quantity(false)), lexington.hash(), lexington.index()));
		assertEquals("buy", m.get("a").getAsString());
		assertEquals("1", m.get("arg").getAsString());
		assertEquals(4000000000L, m.get("hash").getAsLong());
		assertEquals(0, m.get("id").getAsInt());
		assertEquals(VendorModel.BULK, VendorModel.quantity(true));
		assertEquals("sell", json(UiModel.uiact("sell", "10", 21, 0)).get("a").getAsString());
	}

	@Test
	void affordabilityUsesTheHostsCyberpunkMoney() {
		VendorModel.Item unity = new VendorModel.Item(1, 12, "Unity", "Weapons", 2300);
		assertTrue(VendorModel.affordable(unity, null));
		assertTrue(VendorModel.affordable(unity, new UiModel.Stats(2300, 0, "", 0, 0, -1)));
		assertFalse(VendorModel.affordable(unity, new UiModel.Stats(2299, 0, "", 0, 0, -1)));
		assertEquals("12,450 €$", VendorModel.eddies(12450));
	}

	@Test
	void fastTravelPointsSortedAndAvailability() {
		UiModel.Reply reply = UiModel.parseReply(json("{'kind':'atlas','id':3,'groups':[],'pins':["
			+ "{'p':[0,0,0],'kind':'fasttravel','name':'Wollesen St','i':4,'d':'Watson'},"
			+ "{'p':[0,0,0],'kind':'quest','name':'Job','i':-1},"
			+ "{'p':[0,0,0],'kind':'fasttravel','name':'Corpo Plaza','i':0,'d':'City Center'},"
			+ "{'p':[0,0,0],'kind':'fasttravel','name':'Afterlife','i':2,'d':'Watson'}]}"));
		List<UiModel.ReplyPin> points = FastTravelPoints.of(reply);
		assertEquals(List.of("Corpo Plaza", "Afterlife", "Wollesen St"), points.stream().map(UiModel.ReplyPin::name).toList());
		assertTrue(FastTravelPoints.available(points));
		UiModel.Reply off = UiModel.parseReply(json("{'kind':'atlas','id':3,'groups':[],'pins':[{'p':[0,0,0],'kind':'fasttravel','name':'A','i':-1}]}"));
		assertFalse(FastTravelPoints.available(FastTravelPoints.of(off)));
	}
}
