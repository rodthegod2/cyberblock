package dev.cyberblock.net;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cyberblock.Cyberblock;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The players who passed the join password on this world ({@code cyberblock:logins}, in the HostsData pattern), with a salted
 * fingerprint of the password they passed (PBKDF2, never the password). When the configured password no longer matches the
 * fingerprint, every record is dropped and everyone must /login again. Server thread only.
 */
public final class LoginsData extends SavedData {
	static final int ITERATIONS = 120_000;
	private static final HexFormat HEX = HexFormat.of();

	static final Codec<LoginsData> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.optionalFieldOf("salt", "").forGetter(d -> d.salt),
		Codec.STRING.optionalFieldOf("fingerprint", "").forGetter(d -> d.fingerprint),
		UUIDUtil.STRING_CODEC.listOf().optionalFieldOf("verified", List.of()).forGetter(d -> List.copyOf(d.verified))
	).apply(i, LoginsData::new));
	/** No data fixer: Fabric API's SavedDataStorageMixin reads a type without one as it is. */
	static final SavedDataType<LoginsData> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Cyberblock.ID, "logins"), LoginsData::new, CODEC, null);

	private String salt;
	private String fingerprint;
	private final Set<UUID> verified;

	LoginsData() {
		this("", "", List.of());
	}

	LoginsData(final String salt, final String fingerprint, final List<UUID> verified) {
		this.salt = salt;
		this.fingerprint = fingerprint;
		this.verified = new HashSet<>(verified);
	}

	/**
	 * Server start and config reload: the password differs from the one the records were made with, so they are dropped; true
	 * when there was an earlier password or record (everyone must /login again). With no join password nothing changes, so
	 * turning the same password on again keeps the records.
	 */
	boolean sync(final String password) {
		if (password.isEmpty()) {
			return false;
		}

		if (this.salt.isEmpty()) {
			byte[] bytes = new byte[16];
			new SecureRandom().nextBytes(bytes);
			this.salt = HEX.formatHex(bytes);
		}

		String now = fingerprint(this.salt, password);
		if (now.equals(this.fingerprint)) {
			return false;
		}

		boolean had = !this.fingerprint.isEmpty() || !this.verified.isEmpty();
		this.fingerprint = now;
		this.verified.clear();
		this.setDirty();
		return had;
	}

	boolean verified(final UUID player) {
		return this.verified.contains(player);
	}

	void add(final UUID player) {
		if (this.verified.add(player)) {
			this.setDirty();
		}
	}

	int count() {
		return this.verified.size();
	}

	/** {@code /cyberblock password reset}: every record goes; returns how many there were. */
	int reset() {
		int n = this.verified.size();
		if (n > 0) {
			this.verified.clear();
			this.setDirty();
		}

		return n;
	}

	/** PBKDF2-HMAC-SHA256 of the password with the world's salt, as hex. */
	static String fingerprint(final String salt, final String password) {
		PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), HEX.parseHex(salt), ITERATIONS, 256);
		try {
			return HEX.formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("PBKDF2WithHmacSHA256 is missing from this Java", e);
		} finally {
			spec.clearPassword();
		}
	}
}
