package dev.cyberblock.net.admin;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WhitelistWarningTest {
	@Test
	void warnsAtOnceThenEveryMinute() {
		WhitelistWarning w = new WhitelistWarning();
		assertTrue(w.due(1_000, true, false, false));
		assertFalse(w.due(1_050, true, false, false));
		assertFalse(w.due(60_999, true, false, false));
		assertTrue(w.due(61_000, true, false, false));
		assertFalse(w.due(120_999, true, false, false));
		assertTrue(w.due(121_000, true, false, false));
	}

	@Test
	void quietWithTheWhitelistOnOrOffADedicatedServer() {
		WhitelistWarning w = new WhitelistWarning();
		assertFalse(w.due(0, true, true, false));
		assertFalse(w.due(0, false, false, false));
		assertFalse(w.due(500_000, false, false, false));
	}

	@Test
	void turningItOffAgainWarnsImmediately() {
		WhitelistWarning w = new WhitelistWarning();
		assertTrue(w.due(0, true, false, false));
		assertFalse(w.due(10_000, true, true, false));
		assertTrue(w.due(20_000, true, false, false));
		assertFalse(w.due(30_000, true, false, false));
	}

	@Test
	void aJoinPasswordMakesItADeliberatePublicServer() {
		WhitelistWarning w = new WhitelistWarning();
		assertFalse(w.due(0, true, false, true), "whitelist off but a join password: silent");
		assertFalse(w.due(120_000, true, false, true));
		assertTrue(w.due(130_000, true, false, false), "the password removed: warns at once");
		assertFalse(w.due(140_000, true, false, true));
	}

	@Test
	void theWarningSaysWhatToType() {
		assertTrue(WhitelistWarning.TEXT.contains("whitelist on"));
		assertTrue(WhitelistWarning.TEXT.contains("OFF"));
	}
}
