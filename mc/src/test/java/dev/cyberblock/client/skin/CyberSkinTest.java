package dev.cyberblock.client.skin;

import static org.junit.jupiter.api.Assertions.*;

import dev.cyberblock.client.net.Roster;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Megaplan 5-K1 and section 1.12: which skin and model a linked player wears, and the fallbacks. */
class CyberSkinTest {
	private static final Identifier MALE_FILE = Identifier.fromNamespaceAndPath("cyberblock", "textures/entity/cyber_skin/v_male.png");
	private static final Identifier FEMALE_FILE = Identifier.fromNamespaceAndPath("cyberblock", "textures/entity/cyber_skin/v_female.png");
	private static final Identifier CLASSIC_FILE = Identifier.fromNamespaceAndPath("cyberblock", "textures/cyber_skin_fallback/v_female_classic.png");

	private static Predicate<Identifier> files(final Identifier... present) {
		return Set.of(present)::contains;
	}

	@AfterEach
	void clear() {
		Roster.clear();
	}

	@Test
	void textureFilesAreTheSection112Paths() {
		assertEquals(MALE_FILE, CyberSkin.texturePath(CyberSkin.MALE));
		assertEquals(FEMALE_FILE, CyberSkin.texturePath(CyberSkin.FEMALE_SLIM));
		assertEquals(CLASSIC_FILE, CyberSkin.texturePath(CyberSkin.FEMALE_CLASSIC));
	}

	@Test
	void maleIsVMaleOnTheClassicModel() {
		CyberSkin.Choice c = CyberSkin.male(files(MALE_FILE, FEMALE_FILE, CLASSIC_FILE));
		assertEquals(new CyberSkin.Choice(CyberSkin.MALE, PlayerModelType.WIDE), c);
		assertEquals("v_male (classic model)", CyberSkin.describe(c));
	}

	@Test
	void femaleIsLucyOnTheSlimModel() {
		CyberSkin.Choice c = CyberSkin.female(files(MALE_FILE, FEMALE_FILE, CLASSIC_FILE));
		assertEquals(new CyberSkin.Choice(CyberSkin.FEMALE_SLIM, PlayerModelType.SLIM), c);
		assertEquals("v_female (slim model)", CyberSkin.describe(c));
	}

	@Test
	void withoutTheSlimTextureFemaleIsTheClassicTemplateOnTheClassicModel() {
		CyberSkin.Choice c = CyberSkin.female(files(MALE_FILE, CLASSIC_FILE));
		assertEquals(new CyberSkin.Choice(CyberSkin.FEMALE_CLASSIC, PlayerModelType.WIDE), c);
		assertTrue(CyberSkin.describe(c).startsWith("FALLBACK classic Lucy template"));
	}

	@Test
	void withNoTexturesAtAllEveryoneIsClassicSteve() {
		assertEquals(new CyberSkin.Choice(CyberSkin.STEVE, PlayerModelType.WIDE), CyberSkin.male(files()));
		assertEquals(new CyberSkin.Choice(CyberSkin.STEVE, PlayerModelType.WIDE), CyberSkin.female(files()));
		assertTrue(CyberSkin.describe(CyberSkin.male(files())).startsWith("FALLBACK classic Steve"));
	}

	@Test
	void theFallbackTemplateShipsInTheMod() {
		assertTrue(getClass().getResource("/assets/cyberblock/textures/cyber_skin_fallback/v_female_classic.png") != null);
	}

	@Test
	void onlyLinkedPlayersWearIt() {
		UUID host = UUID.randomUUID();
		UUID lucy = UUID.randomUUID();
		UUID guest = UUID.randomUUID();
		Roster.set(List.of(new Roster.Entry(host, "CbHost1", true, "male", -1, false, false, false),
			new Roster.Entry(lucy, "CbHost2", true, "female", -1, false, false, false),
			new Roster.Entry(guest, "CbGuest1", false, "female", -1, false, false, false)));
		assertEquals("male", CyberSkin.bodyOf(Roster.of(host)));
		assertEquals("female", CyberSkin.bodyOf(Roster.of(lucy)));
		assertNull(CyberSkin.bodyOf(Roster.of(guest)), "a guest keeps its own skin");
		assertNull(CyberSkin.bodyOf(null), "a player the roster does not know keeps its own skin");
		assertNull(CyberSkin.of(guest));
		assertNull(CyberSkin.of(UUID.randomUUID()));
	}
}
