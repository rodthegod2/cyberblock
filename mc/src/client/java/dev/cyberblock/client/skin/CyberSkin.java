package dev.cyberblock.client.skin;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.net.Roster;
import dev.cyberblock.skin.SkinRules;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

/**
 * Megaplan 5-K1 (section 1.12): every player the server's roster flags as linked wears the cyber skin on every client, the local
 * player's own view included (it replaces the Phase Q classic-Steve rule and {@code -Dcyberblock.ownSkin}): male = {@code v_male}
 * on the classic model, female = Lucy, {@code v_female} on the slim model. The textures ship in the mod (offline-safe). When the
 * slim texture is missing the classic Lucy template is drawn on the classic model; when the male texture is missing, classic
 * Steve. One log line says which path is active.
 */
public final class CyberSkin {
	/** Section 1.12 (package 5A ships these two). */
	static final Identifier MALE = Identifier.fromNamespaceAndPath(Cyberblock.ID, "entity/cyber_skin/v_male");
	static final Identifier FEMALE_SLIM = Identifier.fromNamespaceAndPath(Cyberblock.ID, "entity/cyber_skin/v_female");
	/** 5K's fallback: the committed classic Lucy template ({@code art/skins/v_female_lucy_classic_template.png}). */
	static final Identifier FEMALE_CLASSIC = Identifier.fromNamespaceAndPath(Cyberblock.ID, "cyber_skin_fallback/v_female_classic");
	/** Vanilla's classic Steve, the last resort. */
	static final Identifier STEVE = Identifier.withDefaultNamespace("entity/player/wide/steve");

	/** A texture asset id and the model it is drawn on. */
	record Choice(Identifier asset, PlayerModelType model) {
	}

	/** The two skins as resolved against the loaded resources, built once. */
	private record Resolved(PlayerSkin male, PlayerSkin female) {
	}

	private static volatile @Nullable Resolved resolved;

	private CyberSkin() {
	}

	/** The texture file of an asset id, as vanilla's ResourceTexture names it ({@code textures/<path>.png}). */
	static Identifier texturePath(final Identifier asset) {
		return new ClientAsset.ResourceTexture(asset).texturePath();
	}

	/** The male skin: v_male on the classic model, or classic Steve when the texture is missing. */
	static Choice male(final Predicate<Identifier> present) {
		return new Choice(present.test(texturePath(MALE)) ? MALE : STEVE, PlayerModelType.WIDE);
	}

	/** The female skin: v_female on the slim model; without it the classic Lucy template on the classic model; else the male one. */
	static Choice female(final Predicate<Identifier> present) {
		if (present.test(texturePath(FEMALE_SLIM))) {
			return new Choice(FEMALE_SLIM, PlayerModelType.SLIM);
		}

		return present.test(texturePath(FEMALE_CLASSIC)) ? new Choice(FEMALE_CLASSIC, PlayerModelType.WIDE) : male(present);
	}

	/** Which body a roster entry wears: null when the player is not linked (it keeps its own skin). */
	static @Nullable String bodyOf(final Roster.@Nullable Entry entry) {
		if (entry == null || !entry.linked()) {
			return null;
		}

		return SkinRules.FEMALE.equals(entry.body()) ? SkinRules.FEMALE : SkinRules.MALE;
	}

	/** The cyber skin for this player, or null when the roster does not flag it as linked. Any thread; cheap per frame. */
	public static @Nullable PlayerSkin of(final UUID uuid) {
		String body = bodyOf(Roster.of(uuid));
		if (body == null) {
			return null;
		}

		Resolved r = resolve();
		return SkinRules.FEMALE.equals(body) ? r.female() : r.male();
	}

	private static Resolved resolve() {
		Resolved r = resolved;
		if (r == null) {
			Predicate<Identifier> present = path -> Minecraft.getInstance().getResourceManager().getResource(path).isPresent();
			Choice male = male(present);
			Choice female = female(present);
			Cyberblock.LOG.info("skin: cyber skins active: male={} female={}", describe(male), describe(female));
			r = new Resolved(skin(male), skin(female));
			resolved = r;
		}

		return r;
	}

	/** The log line's words for a choice. */
	static String describe(final Choice choice) {
		if (choice.asset().equals(MALE)) {
			return "v_male (classic model)";
		} else if (choice.asset().equals(FEMALE_SLIM)) {
			return "v_female (slim model)";
		} else if (choice.asset().equals(FEMALE_CLASSIC)) {
			return "FALLBACK classic Lucy template (classic model; v_female.png missing)";
		}

		return "FALLBACK classic Steve (v_male.png missing)";
	}

	/** V wears no Minecraft cape (null cape and elytra: vanilla's defaults). */
	private static PlayerSkin skin(final Choice choice) {
		return PlayerSkin.insecure(new ClientAsset.ResourceTexture(choice.asset()), null, null, choice.model());
	}
}
