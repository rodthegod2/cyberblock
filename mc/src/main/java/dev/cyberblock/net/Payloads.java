package dev.cyberblock.net;

import dev.cyberblock.Cyberblock;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The Minecraft custom-payload protocol between the Cyberblock client and server (megaplan section 1.3): one record with a
 * StreamCodec per payload id. Common init registers every type ({@link #register()}); the server receivers are in ServerNet, the
 * client receivers in 1L's ClientNet.init().
 *
 * <p>Verified against Minecraft 26.3 and Fabric API 0.161.0+26.3 (fabric-networking-api-v1 6.3.8, javap on the Loom cached jars):
 * <ul>
 * <li>Types: {@code CustomPacketPayload.Type<T>(Identifier)} registered with {@code PayloadTypeRegistry.serverboundPlay()} (C2S) and
 * {@code PayloadTypeRegistry.clientboundPlay()} (S2C), {@code register(Type, StreamCodec<? super RegistryFriendlyByteBuf, T>)}. The
 * codecs here only need a plain ByteBuf.</li>
 * <li>Receivers: {@code ServerPlayNetworking.registerGlobalReceiver(Type, (payload, context) -> ...)} with {@code context.player()},
 * {@code ClientPlayNetworking.registerGlobalReceiver(Type, (payload, context) -> ...)}. Senders: {@code ServerPlayNetworking.send(
 * ServerPlayer, payload)}, {@code ClientPlayNetworking.send(payload)}.</li>
 * <li>Threads: Fabric's ServerPlayNetworkAddon and ClientPlayNetworkAddon re-schedule every play payload handler onto the main
 * thread ({@code MinecraftServer.execute} / {@code Minecraft.execute}) unless it already runs there, so server receivers run on the
 * server thread and client receivers on the client (render) thread.</li>
 * <li>Size limits: vanilla {@code ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE} = 32767 bytes and
 * {@code ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE} = 1048576 bytes; Fabric's PayloadTypeRegistryImpl uses the same two
 * numbers as its split threshold (registerLarge is not used). The section 1.3 caps fit unchanged: an {@code up} is at most
 * 5 (seq) + 17 (t) + 3 + 30000 bytes, a {@code down} at most 3 + 1000000 bytes.</li>
 * </ul>
 * Strings are UTF-8 with a byte cap (not vanilla's char cap): an over-cap string is refused at encode (EncoderException) and at
 * decode (DecoderException). Senders check {@link #fits} first, because a failed encode would drop the connection.
 */
public final class Payloads {
	public static final int PROTO = 1;
	/** Serverbound custom payload limit of 26.3 (bytes). */
	public static final int C2S_LIMIT = 32_767;
	/** Clientbound custom payload limit of 26.3 (bytes). */
	public static final int S2C_LIMIT = 1_048_576;
	public static final int VERSION_CAP = 32;
	public static final int REASON_CAP = 256;
	public static final int T_CAP = 16;
	public static final int UP_CAP = 30_000;
	public static final int DOWN_CAP = 1_000_000;
	public static final int ACT_CAP = 8_000;
	public static final int KIND_CAP = 32;
	public static final int ROSTER_CAP = 200;
	public static final int NAME_CAP = 64;

	/** hs_ack perms bits (section 1.3, the order of section 1.6's toggles). */
	public static final int PERM_EXPLOSIONS = 1, PERM_DAMAGE = 2, PERM_HACKS = 4, PERM_DEVICES = 8, PERM_SPAWNS = 16;
	/** hs_ack features bits: the phase features this server has. */
	public static final int FEATURE_REPLICA = 1, FEATURE_DEVICES = 2, FEATURE_FIGURES = 4, FEATURE_HACKING = 8, FEATURE_HUB = 16, FEATURE_MODES = 32;
	public static final String ROLE_HOST = "host", ROLE_GUEST = "guest";
	private static final Set<String> ROLES = Set.of(ROLE_HOST, ROLE_GUEST);
	private static final Set<String> BODIES = Set.of("male", "female");

	private Payloads() {
	}

	/** True when the string's UTF-8 form is at most maxBytes. */
	public static boolean fits(final String s, final int maxBytes) {
		return s != null && (s.length() <= maxBytes / 3 || s.getBytes(StandardCharsets.UTF_8).length <= maxBytes);
	}

	/** A UTF-8 string with a byte cap, both ways. */
	public static StreamCodec<ByteBuf, String> utf8(final int maxBytes) {
		return new StreamCodec<>() {
			@Override
			public String decode(final ByteBuf buf) {
				int n = VarInt.read(buf);
				if (n < 0 || n > maxBytes) {
					throw new DecoderException("string of " + n + " bytes over the cap of " + maxBytes);
				}

				if (n > buf.readableBytes()) {
					throw new DecoderException("string of " + n + " bytes, only " + buf.readableBytes() + " left");
				}

				String s = buf.toString(buf.readerIndex(), n, StandardCharsets.UTF_8);
				buf.skipBytes(n);
				return s;
			}

			@Override
			public void encode(final ByteBuf buf, final String s) {
				byte[] b = s.getBytes(StandardCharsets.UTF_8);
				if (b.length > maxBytes) {
					throw new EncoderException("string of " + b.length + " bytes over the cap of " + maxBytes);
				}

				VarInt.write(buf, b.length);
				buf.writeBytes(b);
			}
		};
	}

	/** A string that must be one of the allowed values (role, body). */
	private static StreamCodec<ByteBuf, String> oneOf(final Set<String> allowed) {
		StreamCodec<ByteBuf, String> s = utf8(16);
		return new StreamCodec<>() {
			@Override
			public String decode(final ByteBuf buf) {
				String v = s.decode(buf);
				if (!allowed.contains(v)) {
					throw new DecoderException("unexpected value " + v);
				}

				return v;
			}

			@Override
			public void encode(final ByteBuf buf, final String v) {
				if (!allowed.contains(v)) {
					throw new EncoderException("unexpected value " + v);
				}

				s.encode(buf, v);
			}
		};
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(final String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Cyberblock.ID, path));
	}

	/** cyberblock:hs (C2S): on join, and again when the DLL link comes up, drops, or its ver changes. dll "" = no DLL linked. */
	public record Hs(int proto, String mod, String dll, boolean wantHost) implements CustomPacketPayload {
		public static final Type<Hs> TYPE = typeOf("hs");
		public static final StreamCodec<ByteBuf, Hs> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Hs::proto,
			utf8(VERSION_CAP), Hs::mod,
			utf8(VERSION_CAP), Hs::dll,
			ByteBufCodecs.BOOL, Hs::wantHost,
			Hs::new);

		@Override
		public Type<Hs> type() {
			return TYPE;
		}
	}

	/** cyberblock:hs_ack (S2C): the answer to every hs; role is host or guest, reason is shown to the player when not empty. */
	public record HsAck(int proto, String server, boolean ok, String role, String reason, int perms, int maxHosts, int features) implements CustomPacketPayload {
		public static final Type<HsAck> TYPE = typeOf("hs_ack");
		public static final StreamCodec<ByteBuf, HsAck> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, HsAck::proto,
			utf8(VERSION_CAP), HsAck::server,
			ByteBufCodecs.BOOL, HsAck::ok,
			oneOf(ROLES), HsAck::role,
			utf8(REASON_CAP), HsAck::reason,
			ByteBufCodecs.VAR_INT, HsAck::perms,
			ByteBufCodecs.VAR_INT, HsAck::maxHosts,
			ByteBufCodecs.VAR_INT, HsAck::features,
			HsAck::new);

		@Override
		public Type<HsAck> type() {
			return TYPE;
		}
	}

	/** cyberblock:up (C2S): one UP message of section 1.2 as the DLL sent it; seq is per connection, +1 each. */
	public record Up(int seq, String t, String json) implements CustomPacketPayload {
		public static final Type<Up> TYPE = typeOf("up");
		public static final StreamCodec<ByteBuf, Up> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Up::seq,
			utf8(T_CAP), Up::t,
			utf8(UP_CAP), Up::json,
			Up::new);

		@Override
		public Type<Up> type() {
			return TYPE;
		}
	}

	/** cyberblock:down (S2C): one DOWN message for this client's DLL (or a client-only survey_cmd). */
	public record Down(String json) implements CustomPacketPayload {
		public static final Type<Down> TYPE = typeOf("down");
		public static final StreamCodec<ByteBuf, Down> CODEC = utf8(DOWN_CAP).map(Down::new, Down::json);

		@Override
		public Type<Down> type() {
			return TYPE;
		}
	}

	/** One player in the roster. body is male or female, team -1 is none. */
	public record RosterEntry(UUID uuid, String name, boolean linked, String body, int team, boolean pvp, boolean downed, boolean hidden) {
		public static final StreamCodec<ByteBuf, RosterEntry> CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, RosterEntry::uuid,
			utf8(NAME_CAP), RosterEntry::name,
			ByteBufCodecs.BOOL, RosterEntry::linked,
			oneOf(BODIES), RosterEntry::body,
			ByteBufCodecs.VAR_INT, RosterEntry::team,
			ByteBufCodecs.BOOL, RosterEntry::pvp,
			ByteBufCodecs.BOOL, RosterEntry::downed,
			ByteBufCodecs.BOOL, RosterEntry::hidden,
			RosterEntry::new);
	}

	/** cyberblock:roster (S2C): every player, on join and on every change; at most 200 entries. */
	public record Roster(List<RosterEntry> list) implements CustomPacketPayload {
		public static final Type<Roster> TYPE = typeOf("roster");
		public static final StreamCodec<ByteBuf, Roster> CODEC = new StreamCodec<>() {
			@Override
			public Roster decode(final ByteBuf buf) {
				int n = VarInt.read(buf);
				if (n < 0 || n > ROSTER_CAP) {
					throw new DecoderException("roster of " + n + " entries over the cap of " + ROSTER_CAP);
				}

				List<RosterEntry> list = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					list.add(RosterEntry.CODEC.decode(buf));
				}

				return new Roster(List.copyOf(list));
			}

			@Override
			public void encode(final ByteBuf buf, final Roster roster) {
				if (roster.list().size() > ROSTER_CAP) {
					throw new EncoderException("roster of " + roster.list().size() + " entries over the cap of " + ROSTER_CAP);
				}

				VarInt.write(buf, roster.list().size());
				for (RosterEntry e : roster.list()) {
					RosterEntry.CODEC.encode(buf, e);
				}
			}
		};

		@Override
		public Type<Roster> type() {
			return TYPE;
		}
	}

	/** cyberblock:perms (C2S): a host changes what guests may do to its game (PERM_* bits; devices: Phase 4 device-type bits). */
	public record Perms(int perms, int devices) implements CustomPacketPayload {
		public static final Type<Perms> TYPE = typeOf("perms");
		public static final StreamCodec<ByteBuf, Perms> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Perms::perms,
			ByteBufCodecs.VAR_INT, Perms::devices,
			Perms::new);

		@Override
		public Type<Perms> type() {
			return TYPE;
		}
	}

	/** cyberblock:content (S2C): one content kind a client needs (section 1.7), on join and after /reload. */
	public record Content(String kind, String json) implements CustomPacketPayload {
		public static final Type<Content> TYPE = typeOf("content");
		public static final StreamCodec<ByteBuf, Content> CODEC = StreamCodec.composite(
			utf8(KIND_CAP), Content::kind,
			utf8(DOWN_CAP), Content::json,
			Content::new);

		@Override
		public Type<Content> type() {
			return TYPE;
		}
	}

	/** cyberblock:act (C2S): a player's request the server routes to an owner (scan, hack, devact, meetup, spawn, breach, ...). */
	public record Act(String t, String json) implements CustomPacketPayload {
		public static final Type<Act> TYPE = typeOf("act");
		public static final StreamCodec<ByteBuf, Act> CODEC = StreamCodec.composite(
			utf8(T_CAP), Act::t,
			utf8(ACT_CAP), Act::json,
			Act::new);

		@Override
		public Type<Act> type() {
			return TYPE;
		}
	}

	/** cyberblock:ui (S2C): server data for a screen (shop, breach grid, Holo-Table pins, match state, Heat, feed frames, scaninfo). */
	public record Ui(String t, String json) implements CustomPacketPayload {
		public static final Type<Ui> TYPE = typeOf("ui");
		public static final StreamCodec<ByteBuf, Ui> CODEC = StreamCodec.composite(
			utf8(T_CAP), Ui::t,
			utf8(DOWN_CAP), Ui::json,
			Ui::new);

		@Override
		public Type<Ui> type() {
			return TYPE;
		}
	}

	/** Common init (both sides, once): registers every payload type with Fabric's play registries. */
	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Hs.TYPE, Hs.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Up.TYPE, Up.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Perms.TYPE, Perms.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Act.TYPE, Act.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HsAck.TYPE, HsAck.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Down.TYPE, Down.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Roster.TYPE, Roster.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Content.TYPE, Content.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Ui.TYPE, Ui.CODEC);
	}
}
