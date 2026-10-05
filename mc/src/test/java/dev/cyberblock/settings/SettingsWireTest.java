package dev.cyberblock.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import dev.cyberblock.mode.overlord.Intensity;
import dev.cyberblock.net.Permissions;
import dev.cyberblock.net.ServerConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Megaplan 9-S1 / section 1.13: the settings snapshot the server answers the screen with. */
class SettingsWireTest {
	private static final SettingsWire.World WORLD = new SettingsWire.World(true, 2, 4, true, 1.5, 160, false, true, 7, 5, false, true, true);
	private static final SettingsWire.Snapshot FULL = new SettingsWire.Snapshot(5, 3, "female", true, true, true, true, true, false,
		Intensity.BRUTAL, 150, WORLD);

	@Test
	void aSnapshotRoundTrips() {
		assertEquals(FULL, SettingsWire.parse(SettingsWire.json(FULL)));
		SettingsWire.Snapshot guest = new SettingsWire.Snapshot(15, 2047, "male", false, false, false, false, false, false, Intensity.NORMAL, 100, null);
		assertEquals(guest, SettingsWire.parse(SettingsWire.json(guest)));
		assertFalse(JsonParser.parseString(SettingsWire.json(guest)).getAsJsonObject().has("world"), "server.json's rows go to admins only");
	}

	@Test
	void theShapeIsFixed() {
		assertEquals(JsonParser.parseString("{'perms':5,'devices':3,'body':'female','pvp':true,'hidePin':true,'host':true,'admin':true,'erase':true,"
			+ "'eraseForced':false,'overlord':'brutal','heat':150,'world':{'allowHosts':true,'hosts':2,'maxHosts':4,'requireMod':true,'rate':1.5,"
			+ "'sanityRadius':160,'pvpDefault':false,'backups':{'daily':true,'keep':7,'hour':5},'webMap':false,'joinPassword':true,'joinHelp':true}}"),
			JsonParser.parseString(SettingsWire.json(FULL)));
	}

	@Test
	void theJoinPasswordIsOnlyEverOnOrOff() {
		String secret = "hunter2-very-secret";
		ServerConfig config = new ServerConfig(true, List.of("CbHost1"), 4, true, "ask Rod", 1.0, 160, false, false, ServerConfig.Backups.DEFAULT, false, secret);
		SettingsWire.World w = ServerSettings.world(config);
		assertTrue(w.joinPassword());
		assertEquals(1, w.hosts());
		assertTrue(w.joinHelp());
		String json = SettingsWire.json(new SettingsWire.Snapshot(15, 2047, "male", false, false, true, true, false, false, Intensity.NORMAL, 100, w));
		assertFalse(json.contains(secret), "the join password never leaves the server");
		assertFalse(json.contains("ask Rod"), "only whether a join help text is set");
		assertFalse(ServerSettings.world(ServerConfig.DEFAULT).joinPassword());
	}

	@Test
	void badOrMissingFieldsReadAsDefaults() {
		assertNull(SettingsWire.parse("[1,2]"));
		assertNull(SettingsWire.parse("not json {"));
		SettingsWire.Snapshot s = SettingsWire.parse("{'perms':'all','body':'robot','overlord':'insane','heat':9999,'admin':1}");
		assertNotNull(s);
		assertEquals(Permissions.DEFAULT.bits(), s.perms());
		assertEquals(Permissions.ALL_DEVICES, s.devices());
		assertEquals("male", s.body());
		assertSame(Intensity.NORMAL, s.overlord());
		assertEquals(100, s.heat());
		assertFalse(s.admin(), "admin must be a JSON boolean");
		assertNull(s.world());
	}

	@Test
	void bitsAreMaskedLikePermissions() {
		SettingsWire.Snapshot s = new SettingsWire.Snapshot(-1, -1, "male", false, false, false, false, false, false, Intensity.CHILL, 25, null);
		assertEquals(Permissions.ALL, s.perms());
		assertEquals(Permissions.ALL_DEVICES, s.devices());
		assertEquals(new Permissions(Permissions.ALL, Permissions.ALL_DEVICES), s.permissions());
	}
}
