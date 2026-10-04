package dev.cyberblock.net;

import org.jspecify.annotations.Nullable;

/**
 * The handshake rules of megaplan section 1.5, as a pure decision (ServerNet applies it). The texts are the plan's; the server
 * builds them (the client shows {@code reason} as given).
 */
public final class Handshake {
	/** Ticks a joining client has to send hs before a server with requireMod kicks it (10 s). */
	public static final int TIMEOUT_TICKS = 200;

	private Handshake() {
	}

	/** Why a player may not host, checked by the caller from the server's whitelist and the config (null = may host). */
	public enum HostRefusal {
		NOT_ALLOWED("Cyberblock: this server does not allow Cyberpunk hosts"),
		NOT_WHITELISTED("Cyberblock: only whitelisted players can link a Cyberpunk game on this server"),
		NOT_LISTED("Cyberblock: you are not on this server's list of Cyberpunk hosts");

		private final String reason;

		HostRefusal(final String reason) {
			this.reason = reason;
		}

		public String reason() {
			return this.reason;
		}
	}

	/**
	 * @param refusal why this player may not host (null: allowed by the whitelist, the hosts list and allowHosts)
	 * @param otherHosts players other than this one that hold the host role now
	 */
	public record Input(int proto, String mod, String dll, boolean wantHost, String serverVersion, @Nullable HostRefusal refusal, int otherHosts,
		int maxHosts) {
	}

	/** ok false: the server disconnects the player with reason. */
	public record Result(boolean ok, String role, String reason) {
		public boolean host() {
			return Payloads.ROLE_HOST.equals(this.role);
		}
	}

	public static Result decide(final Input in) {
		if (in.proto() != Payloads.PROTO || !majorMinor(in.mod()).equals(majorMinor(in.serverVersion()))) {
			return new Result(false, Payloads.ROLE_GUEST, mismatch(in.serverVersion(), in.mod()));
		}

		if (!in.dll().isEmpty() && !in.dll().equals(in.mod())) {
			return new Result(true, Payloads.ROLE_GUEST, "Cyberblock: your Cyberpunk plugin is " + in.dll() + " but your mod is " + in.mod()
				+ "; you can play as a guest. Reinstall both from the same release.");
		}

		if (!in.wantHost() || in.dll().isEmpty()) {
			return new Result(true, Payloads.ROLE_GUEST, "");
		}

		if (in.refusal() != null) {
			return new Result(true, Payloads.ROLE_GUEST, in.refusal().reason());
		}

		if (in.otherHosts() >= in.maxHosts()) {
			return new Result(true, Payloads.ROLE_GUEST, "Cyberblock: another Cyberpunk host is already active (max " + in.maxHosts() + ")");
		}

		return new Result(true, Payloads.ROLE_HOST, "");
	}

	/** The disconnect text for a protocol or major.minor mismatch. */
	public static String mismatch(final String server, final String mod) {
		return "Cyberblock version mismatch: server " + server + ", your mod " + mod + ". Update from the modpack.";
	}

	/** The kick text for a client that sent no hs within 10 s while requireMod is on. */
	public static String needsMod(final String joinHelp) {
		return joinHelp.isBlank() ? "This server needs the Cyberblock modpack." : "This server needs the Cyberblock modpack: " + joinHelp;
	}

	/** "1.0.3" -> "1.0"; a version without two dot-separated parts is compared whole. */
	static String majorMinor(final String version) {
		String[] parts = version.split("\\.", 3);
		return parts.length >= 2 ? parts[0] + "." + parts[1] : version;
	}
}
