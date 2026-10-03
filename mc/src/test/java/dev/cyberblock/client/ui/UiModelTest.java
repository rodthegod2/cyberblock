package dev.cyberblock.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Plan 2026-10-03-retest3-polish section 1.1: every UI message parser (valid, each limit, unknown enums, bad types). */
class UiModelTest {
	/** Gson's parseString is lenient, like the host link's: NaN, Infinity and single quotes all parse. */
	private static JsonObject json(final String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	private static String repeat(final char c, final int n) {
		return String.valueOf(c).repeat(n);
	}

	// ---- stats ----

	@Test
	void statsValid() {
		UiModel.Stats s = UiModel.parseStats(json("{'t':'stats','money':12500,'wanted':3,'district':'Watson','time':[21,7],'speed':88.5}"));
		assertEquals(new UiModel.Stats(12500, 3, "Watson", 21, 7, 88.5), s);
		assertTrue(s.inVehicle());
		UiModel.Stats onFoot = UiModel.parseStats(json("{'money':0,'wanted':0,'district':'','time':[0,0],'speed':-1}"));
		assertFalse(onFoot.inVehicle());
	}

	@Test
	void statsLimits() {
		assertNull(UiModel.parseStats(json("{'money':-1,'wanted':0,'district':'','time':[1,2],'speed':-1}")));
		assertNull(UiModel.parseStats(json("{'money':1,'wanted':6,'district':'','time':[1,2],'speed':-1}")));
		assertNull(UiModel.parseStats(json("{'money':1,'wanted':0,'district':'','time':[24,2],'speed':-1}")));
		assertNull(UiModel.parseStats(json("{'money':1,'wanted':0,'district':'','time':[1,60],'speed':-1}")));
		assertNull(UiModel.parseStats(json("{'money':1,'wanted':0,'district':'','time':[1],'speed':-1}")));
		assertNull(UiModel.parseStats(json("{'money':1,'wanted':0,'district':'','time':[1,2],'speed':-0.5}")));
		assertNull(UiModel.parseStats(json("{'money':1,'wanted':0,'district':'','time':[1,2],'speed':NaN}")));
		assertNull(UiModel.parseStats(json("{'money':1.5,'wanted':0,'district':'','time':[1,2],'speed':-1}")));
		assertNull(UiModel.parseStats(json("{'money':'lots','wanted':0,'district':'','time':[1,2],'speed':-1}")));
		assertNull(UiModel.parseStats(json("{'wanted':0,'district':'','time':[1,2],'speed':-1}")));
		// a district past 64 is cut, control characters are stripped
		UiModel.Stats s = UiModel.parseStats(json("{'money':1,'wanted':5,'district':'" + repeat('d', 70) + "','time':[23,59],'speed':0}"));
		assertEquals(64, s.district().length());
		assertEquals("ab", UiModel.parseStats(json("{'money':1,'wanted':5,'district':'a\\u0007b','time':[23,59],'speed':0}")).district());
	}

	// ---- quest ----

	@Test
	void questValid() {
		UiModel.Quest q = UiModel.parseQuest(json("{'t':'quest','title':'The Heist','kind':'main','obj':[{'text':'Go to Konpeki','cur':0,'tot':0,'opt':false,'on':true},"
			+ "{'text':'Collect chips','cur':2,'tot':5,'opt':true,'on':false}],'wp':[10.5,64,-3]}"));
		assertEquals("The Heist", q.title());
		assertEquals("main", q.kind());
		assertEquals(2, q.objectives().size());
		assertEquals(new UiModel.Objective("Collect chips", 2, 5, true, false), q.objectives().get(1));
		assertEquals(new UiModel.Pos(10.5, 64, -3), q.wp());
		assertFalse(q.isEmpty());
	}

	@Test
	void questNullWaypointAndUnknownKind() {
		UiModel.Quest q = UiModel.parseQuest(json("{'title':'Gig','kind':'heist','obj':[],'wp':null}"));
		assertEquals("other", q.kind());
		assertNull(q.wp());
		assertNull(UiModel.parseQuest(json("{'title':'Gig','kind':'gig','obj':[]}")).wp());
		// an empty title and no objectives: nothing tracked
		assertTrue(UiModel.parseQuest(json("{'title':'','kind':'other','obj':[],'wp':null}")).isEmpty());
	}

	@Test
	void questLimits() {
		StringBuilder obj = new StringBuilder();
		for (int i = 0; i < 15; i++) {
			obj.append(i == 0 ? "" : ",").append("{'text':'o").append(i).append("','cur':0,'tot':0,'opt':false,'on':false}");
		}

		UiModel.Quest q = UiModel.parseQuest(json("{'title':'" + repeat('t', 200) + "','kind':'side','obj':[" + obj + "],'wp':null}"));
		assertEquals(128, q.title().length());
		assertEquals(12, q.objectives().size());
		// a bad objective is dropped, the rest stays
		q = UiModel.parseQuest(json("{'title':'x','kind':'side','obj':[{'text':'a','cur':-1,'tot':0,'opt':false,'on':false},{'text':'b','cur':0,'tot':0,'opt':false,'on':false}],'wp':null}"));
		assertEquals(List.of(new UiModel.Objective("b", 0, 0, false, false)), q.objectives());
		assertEquals(256, UiModel.parseQuest(json("{'title':'x','kind':'side','obj':[{'text':'" + repeat('o', 300) + "','cur':0,'tot':0,'opt':false,'on':false}],'wp':null}"))
			.objectives().get(0).text().length());
		// a bad waypoint or a missing title make the message invalid
		assertNull(UiModel.parseQuest(json("{'title':'x','kind':'side','obj':[],'wp':[1,2]}")));
		assertNull(UiModel.parseQuest(json("{'title':'x','kind':'side','obj':[],'wp':[1,Infinity,2]}")));
		assertNull(UiModel.parseQuest(json("{'title':'x','kind':'side','obj':[],'wp':[3e7,2,2]}")));
		assertNull(UiModel.parseQuest(json("{'kind':'side','obj':[],'wp':null}")));
		assertNull(UiModel.parseQuest(json("{'title':'x','kind':'side','obj':'none','wp':null}")));
	}

	// ---- pins ----

	@Test
	void pinsValidUnknownKindAndLimit() {
		UiModel.Pins p = UiModel.parsePins(json("{'t':'pins','list':[{'p':[1,2,3],'kind':'vendor','tr':false},{'p':[4,5,6],'kind':'bar','tr':true}]}"));
		assertEquals(List.of(new UiModel.Pin(new UiModel.Pos(1, 2, 3), "vendor", false), new UiModel.Pin(new UiModel.Pos(4, 5, 6), "other", true)), p.list());
		StringBuilder list = new StringBuilder();
		for (int i = 0; i < 40; i++) {
			list.append(i == 0 ? "" : ",").append("{'p':[").append(i).append(",0,0],'kind':'quest','tr':false}");
		}

		assertEquals(32, UiModel.parsePins(json("{'list':[" + list + "]}")).list().size());
		// a bad entry is dropped
		assertEquals(1, UiModel.parsePins(json("{'list':[{'p':[1,2],'kind':'car','tr':false},{'p':[1,2,3],'kind':'car','tr':'yes'},{'p':[1,2,3],'kind':'car'}]}")).list().size());
		assertNull(UiModel.parsePins(json("{'list':5}")));
		assertNull(UiModel.parsePins(json("{}")));
	}

	// ---- prompt ----

	@Test
	void promptValidAndClear() {
		UiModel.Prompt p = UiModel.parsePrompt(json("{'t':'prompt','title':'Door','list':[{'text':'Open','key':'F','hold':false,'inactive':false},"
			+ "{'text':'Hack','key':'R','hold':true,'inactive':true}]}"));
		assertEquals("Door", p.title());
		assertEquals(new UiModel.PromptEntry("Hack", "R", true, true), p.list().get(1));
		UiModel.Prompt clear = UiModel.parsePrompt(json("{'title':'','list':[]}"));
		assertTrue(clear.isEmpty());
		assertTrue(UiModel.parsePrompt(json("{'list':[]}")).isEmpty());
	}

	@Test
	void promptLimits() {
		StringBuilder list = new StringBuilder();
		for (int i = 0; i < 9; i++) {
			list.append(i == 0 ? "" : ",").append("{'text':'t").append(i).append("','key':'F','hold':false,'inactive':false}");
		}

		assertEquals(6, UiModel.parsePrompt(json("{'title':'','list':[" + list + "]}")).list().size());
		UiModel.Prompt p = UiModel.parsePrompt(json("{'title':'','list':[{'text':'" + repeat('x', 200) + "','key':'LONG','hold':false,'inactive':false}]}"));
		assertEquals(128, p.list().get(0).text().length());
		assertEquals("LON", p.list().get(0).key());
		assertNull(UiModel.parsePrompt(json("{'title':'','list':null}")));
		assertNull(UiModel.parsePrompt(json("{'title':3,'list':[]}")));
	}

	// ---- choices ----

	@Test
	void choicesValid() {
		UiModel.Choices c = UiModel.parseChoices(json("{'t':'choices','hubs':[{'id':7,'title':'Jackie','list':[{'text':'Hey','f':[]},"
			+ "{'text':'Pay him','f':['pay','quest']},{'text':'[Body 6] Lift','f':['check_ok','bogus']}]}],'active':7,'sel':1,'left':-1}"), 100L);
		assertEquals(1, c.hubs().size());
		UiModel.Hub hub = c.hubs().get(0);
		assertEquals(7, hub.id());
		assertEquals(Set.of(UiModel.Flag.PAY, UiModel.Flag.QUEST), hub.list().get(1).flags());
		assertEquals(Set.of(UiModel.Flag.CHECK_OK), hub.list().get(2).flags()); // unknown flags are dropped
		assertEquals(7, c.active());
		assertEquals(1, c.sel());
		assertFalse(c.timed());
		assertEquals(100L, c.at());
		assertSame(hub, c.activeHub());
	}

	@Test
	void choicesTimerAndClear() {
		UiModel.Choices c = UiModel.parseChoices(json("{'hubs':[{'id':1,'title':'','list':[{'text':'a','f':['timed']}]}],'active':1,'sel':-1,'left':4.5}"), 0L);
		assertTrue(c.timed());
		assertEquals(4.5, c.left());
		assertEquals(-1, c.sel());
		assertTrue(UiModel.parseChoices(json("{'hubs':[],'active':0,'sel':-1,'left':-1}"), 0L).isEmpty());
	}

	@Test
	void choicesLimits() {
		StringBuilder hubs = new StringBuilder();
		for (int h = 0; h < 6; h++) {
			hubs.append(h == 0 ? "" : ",").append("{'id':").append(h).append(",'title':'h','list':[{'text':'a','f':[]},{'text':'b','f':[]},{'text':'c','f':[]},{'text':'d','f':[]}]}");
		}

		UiModel.Choices c = UiModel.parseChoices(json("{'hubs':[" + hubs + "],'active':0,'sel':0,'left':-1}"), 0L);
		assertEquals(3, c.hubs().size()); // 12 entries in all: the fourth hub would bring the 13th
		assertEquals(12, c.hubs().stream().mapToInt(h -> h.list().size()).sum());
		assertEquals(256, UiModel.parseChoices(json("{'hubs':[{'id':1,'title':'','list':[{'text':'" + repeat('c', 300) + "','f':[]}]}],'active':1,'sel':0,'left':-1}"), 0L)
			.hubs().get(0).list().get(0).text().length());
		assertNull(UiModel.parseChoices(json("{'hubs':[],'active':0,'sel':-2,'left':-1}"), 0L));
		assertNull(UiModel.parseChoices(json("{'hubs':[],'active':0,'sel':0,'left':-3}"), 0L));
		assertNull(UiModel.parseChoices(json("{'hubs':[],'active':0.5,'sel':0,'left':-1}"), 0L));
		assertNull(UiModel.parseChoices(json("{'hubs':{},'active':0,'sel':0,'left':-1}"), 0L));
		// a hub whose title is not a string is dropped
		assertTrue(UiModel.parseChoices(json("{'hubs':[{'id':1,'title':[],'list':[]}],'active':1,'sel':0,'left':-1}"), 0L).isEmpty());
	}

	// ---- sub ----

	@Test
	void subtitle() {
		UiModel.Subtitle s = UiModel.parseSub(json("{'t':'sub','show':true,'key':'line42','who':'Jackie','text':'Preem.','secs':3.5}"), 9L);
		assertEquals(new UiModel.Subtitle(true, "line42", "Jackie", "Preem.", 3.5, 9L), s);
		assertEquals(256, UiModel.parseSub(json("{'show':true,'key':'k','who':'','text':'" + repeat('s', 400) + "','secs':1}"), 0L).text().length());
		assertNull(UiModel.parseSub(json("{'show':true,'key':'k','who':'','text':'','secs':31}"), 0L));
		assertNull(UiModel.parseSub(json("{'show':'yes','key':'k','who':'','text':'','secs':1}"), 0L));
		assertNull(UiModel.parseSub(json("{'key':'k','who':'','text':'','secs':1}"), 0L));
	}

	// ---- call ----

	@Test
	void call() {
		assertEquals(new UiModel.Call("incoming", "Rogue", true, true), UiModel.parseCall(json("{'t':'call','phase':'incoming','who':'Rogue','audio':true,'reject':true}")));
		assertEquals("end", UiModel.parseCall(json("{'phase':'end','who':''}")).phase());
		assertNull(UiModel.parseCall(json("{'phase':'ringing','who':'Rogue','audio':true,'reject':true}")));
		assertEquals(64, UiModel.parseCall(json("{'phase':'active','who':'" + repeat('w', 80) + "','audio':false,'reject':false}")).who().length());
	}

	// ---- notify ----

	@Test
	void notifyMessage() {
		UiModel.Notify n = UiModel.parseNotify(json("{'t':'notify','kind':'money','title':'Eddies','text':'+500','secs':4}"));
		assertEquals(new UiModel.Notify("money", "Eddies", "+500", 4.0), n);
		assertEquals("info", UiModel.parseNotify(json("{'kind':'brand_new','title':'a','text':'','secs':2}")).kind());
		assertNull(UiModel.parseNotify(json("{'kind':'sms','title':'a','text':'','secs':-1}")));
		assertNull(UiModel.parseNotify(json("{'kind':'sms','title':'a','text':'','secs':31}")));
		assertEquals(128, UiModel.parseNotify(json("{'kind':'sms','title':'" + repeat('n', 140) + "','text':'','secs':1}")).title().length());
		assertEquals(256, UiModel.parseNotify(json("{'kind':'sms','title':'','text':'" + repeat('n', 300) + "','secs':1}")).text().length());
	}

	// ---- screen ----

	@Test
	void screen() {
		assertEquals(new UiModel.ScreenMsg("journal", null), UiModel.parseScreen(json("{'t':'screen','open':'journal'}")));
		assertEquals(new UiModel.ScreenMsg(null, "vendor"), UiModel.parseScreen(json("{'t':'screen','pass':'vendor'}")));
		assertEquals(new UiModel.ScreenMsg(null, ""), UiModel.parseScreen(json("{'t':'screen','pass':''}")));
		assertNull(UiModel.parseScreen(json("{'open':'settings'}")));
		assertNull(UiModel.parseScreen(json("{'pass':'casino'}")));
		assertNull(UiModel.parseScreen(json("{}")));
		assertNull(UiModel.parseScreen(json("{'open':3}")));
		for (String name : UiModel.PASS_NAMES) {
			assertEquals(name, UiModel.parseScreen(json("{'pass':'" + name + "'}")).pass());
		}

		assertEquals(14, UiModel.PASS_NAMES.size());
	}

	// ---- reply ----

	@Test
	void replyJournal() {
		UiModel.Reply r = UiModel.parseReply(json("{'t':'reply','kind':'journal','id':4,'groups':[{'hash':4294967295,'title':'The Heist','kind':'main','tracked':true,'done':false,"
			+ "'lines':[{'hash':1,'text':'Meet Dex','state':1},{'hash':2,'text':'Go','state':0}]},{'hash':3,'title':'Gig','kind':'weird','tracked':false,'done':true,'lines':[]}],'pins':[]}"));
		assertEquals("journal", r.kind());
		assertEquals(4, r.id());
		assertEquals(2, r.groups().size());
		assertEquals(4294967295L, r.groups().get(0).hash());
		assertEquals(new UiModel.Line(1, "Meet Dex", 1), r.groups().get(0).lines().get(0));
		assertEquals("other", r.groups().get(1).kind());
		assertTrue(r.groups().get(1).done());
	}

	@Test
	void replyAtlasAndPhone() {
		UiModel.Reply atlas = UiModel.parseReply(json("{'kind':'atlas','id':9,'groups':[],'pins':[{'p':[1,2,3],'kind':'fasttravel','name':'Japantown','i':3},{'p':[4,5,6],'kind':'quest','name':'Job','i':-1}]}"));
		assertEquals(new UiModel.ReplyPin(new UiModel.Pos(1, 2, 3), "fasttravel", "Japantown", 3), atlas.pins().get(0));
		assertEquals(-1, atlas.pins().get(1).index());
		UiModel.Reply phone = UiModel.parseReply(json("{'kind':'phone','id':2,'groups':[{'hash':5,'title':'Judy','kind':'sms','tracked':false,'done':false,'lines':[{'hash':6,'text':'Hi V','state':0},{'hash':7,'text':'Sure','state':2}]}]}"));
		assertEquals(2, phone.groups().get(0).lines().get(1).state());
		assertTrue(phone.pins().isEmpty());
	}

	@Test
	void replyLimits() {
		assertNull(UiModel.parseReply(json("{'kind':'inventory','id':1,'groups':[],'pins':[]}")));
		assertNull(UiModel.parseReply(json("{'kind':'atlas','groups':[],'pins':[]}")));
		// a bad hash or state drops the entry
		UiModel.Reply r = UiModel.parseReply(json("{'kind':'journal','id':1,'groups':[{'hash':-1,'title':'a','kind':'main','tracked':false,'done':false,'lines':[]},"
			+ "{'hash':4294967296,'title':'b','kind':'main','tracked':false,'done':false,'lines':[]},"
			+ "{'hash':1,'title':'c','kind':'main','tracked':false,'done':false,'lines':[{'hash':1,'text':'x','state':3},{'hash':2,'text':'y','state':2}]}],'pins':[]}"));
		assertEquals(1, r.groups().size());
		assertEquals(1, r.groups().get(0).lines().size());
		StringBuilder groups = new StringBuilder();
		for (int g = 0; g < 140; g++) {
			groups.append(g == 0 ? "" : ",").append("{'hash':").append(g).append(",'title':'g','kind':'side','tracked':false,'done':false,'lines':[{'hash':1,'text':'a','state':0},{'hash':2,'text':'b','state':0},{'hash':3,'text':'c','state':0},{'hash':4,'text':'d','state':0},{'hash':5,'text':'e','state':0}]}");
		}

		r = UiModel.parseReply(json("{'kind':'journal','id':1,'groups':[" + groups + "],'pins':[]}"));
		assertEquals(128, r.groups().size());
		assertEquals(512, r.groups().stream().mapToInt(g -> g.lines().size()).sum());
		StringBuilder pins = new StringBuilder();
		for (int i = 0; i < 300; i++) {
			pins.append(i == 0 ? "" : ",").append("{'p':[").append(i).append(",0,0],'kind':'other','name':'" + repeat('n', 70) + "','i':-1}");
		}

		r = UiModel.parseReply(json("{'kind':'atlas','id':1,'groups':[],'pins':[" + pins + "]}"));
		assertEquals(256, r.pins().size());
		assertEquals(64, r.pins().get(0).name().length());
	}

	// ---- npcs (radar) ----

	@Test
	void npcs() {
		List<UiModel.Npc> npcs = UiModel.parseNpcs(json("{'t':'npcs','list':[{'id':'1','p':[1,2,3],'yaw':0,'kind':'human','hostile':true},{'id':'2','p':[4,5,6],'yaw':0,'kind':'vehicle','hostile':false},{'id':'3','p':[1],'kind':'human'}]}"));
		assertEquals(List.of(new UiModel.Npc(new UiModel.Pos(1, 2, 3), false, true), new UiModel.Npc(new UiModel.Pos(4, 5, 6), true, false)), npcs);
		assertNull(UiModel.parseNpcs(json("{}")));
	}

	// ---- uiact (Minecraft to Cyberpunk) ----

	@Test
	void uiact() {
		JsonObject m = json(UiModel.uiact("track", "quest", 4294967295L, 12));
		assertEquals("uiact", m.get("t").getAsString());
		assertEquals("track", m.get("a").getAsString());
		assertEquals("quest", m.get("arg").getAsString());
		assertEquals(4294967295L, m.get("hash").getAsLong());
		assertEquals(12, m.get("id").getAsInt());
		assertEquals(64, json(UiModel.uiact("cpmenu", repeat('a', 90), 0, 0)).get("arg").getAsString().length());
		assertThrows(IllegalArgumentException.class, () -> UiModel.uiact("explode", "", 0, 0));
		assertThrows(IllegalArgumentException.class, () -> UiModel.uiact("save", "", -1, 0));
		for (String a : UiModel.UIACT_NAMES) {
			assertDoesNotThrow(() -> UiModel.uiact(a, "", 0, 0));
		}
	}

	// ---- the shared text helper ----

	@Test
	void cleanCutsByCodePointAndStripsControls() {
		assertEquals("ab", UiModel.clean("a\nb", 10));
		assertEquals("😀😀", UiModel.clean("😀😀😀", 2)); // never splits a surrogate pair
		assertEquals("abc", UiModel.clean("abcdef", 3));
	}
}
