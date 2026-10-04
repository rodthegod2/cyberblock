package dev.cyberblock.net;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

class PayloadsTest {
	private static <T> T roundTrip(final StreamCodec<ByteBuf, T> codec, final T value) {
		ByteBuf buf = Unpooled.buffer();
		codec.encode(buf, value);
		T back = codec.decode(buf);
		assertEquals(0, buf.readableBytes(), "the codec reads exactly what it wrote");
		return back;
	}

	@Test
	void everyPayloadRoundTrips() {
		Payloads.Hs hs = new Payloads.Hs(1, "1.0.0", "", true);
		assertEquals(hs, roundTrip(Payloads.Hs.CODEC, hs));
		Payloads.HsAck ack = new Payloads.HsAck(1, "1.0.0", true, "guest", "Cyberblock: another Cyberpunk host is already active (max 1)", 15, 1, 0);
		assertEquals(ack, roundTrip(Payloads.HsAck.CODEC, ack));
		Payloads.Up up = new Payloads.Up(42, "ground", "{\"t\":\"ground\",\"c\":[1,2,3,4]}");
		assertEquals(up, roundTrip(Payloads.Up.CODEC, up));
		Payloads.Down down = new Payloads.Down("{\"t\":\"align\",\"k\":7}");
		assertEquals(down, roundTrip(Payloads.Down.CODEC, down));
		Payloads.Roster roster = new Payloads.Roster(List.of(
			new Payloads.RosterEntry(UUID.randomUUID(), "CbHost1", true, "male", -1, false, false, false),
			new Payloads.RosterEntry(UUID.randomUUID(), "CbGuest1", false, "female", 3, true, true, true)));
		assertEquals(roster, roundTrip(Payloads.Roster.CODEC, roster));
		Payloads.Perms perms = new Payloads.Perms(31, 2047);
		assertEquals(perms, roundTrip(Payloads.Perms.CODEC, perms));
		Payloads.Content content = new Payloads.Content("test", "{\"cyberblock:a\":{}}");
		assertEquals(content, roundTrip(Payloads.Content.CODEC, content));
		Payloads.Act act = new Payloads.Act("scan", "{\"target\":null}");
		assertEquals(act, roundTrip(Payloads.Act.CODEC, act));
		Payloads.Ui ui = new Payloads.Ui("scaninfo", "{\"id\":3}");
		assertEquals(ui, roundTrip(Payloads.Ui.CODEC, ui));
	}

	@Test
	void unicodeIsCountedInBytes() {
		// 10 three-byte characters: 30 bytes, over a 16-byte cap although only 10 chars
		String s = "€".repeat(10);
		assertFalse(Payloads.fits(s, 16));
		assertTrue(Payloads.fits(s, 30));
		assertThrows(EncoderException.class, () -> Payloads.utf8(16).encode(Unpooled.buffer(), s));
		assertEquals(s, roundTrip(Payloads.utf8(30), s));
	}

	@Test
	void anOverCapStringIsRefusedAtEncode() {
		String big = "x".repeat(Payloads.UP_CAP + 1);
		assertThrows(EncoderException.class, () -> Payloads.Up.CODEC.encode(Unpooled.buffer(), new Payloads.Up(1, "ground", big)));
		assertThrows(EncoderException.class, () -> Payloads.Up.CODEC.encode(Unpooled.buffer(), new Payloads.Up(1, "x".repeat(17), "{}")));
		assertThrows(EncoderException.class, () -> Payloads.Hs.CODEC.encode(Unpooled.buffer(), new Payloads.Hs(1, "v".repeat(33), "", false)));
		assertThrows(EncoderException.class, () -> Payloads.Act.CODEC.encode(Unpooled.buffer(), new Payloads.Act("scan", "y".repeat(Payloads.ACT_CAP + 1))));
		assertThrows(EncoderException.class, () -> Payloads.Down.CODEC.encode(Unpooled.buffer(), new Payloads.Down("z".repeat(Payloads.DOWN_CAP + 1))));
		// exactly at the cap is fine
		assertDoesNotThrow(() -> Payloads.Up.CODEC.encode(Unpooled.buffer(), new Payloads.Up(1, "ground", "x".repeat(Payloads.UP_CAP))));
	}

	@Test
	void aClaimedLengthOverTheCapIsRefusedAtDecode() {
		ByteBuf buf = Unpooled.buffer();
		VarInt.write(buf, 1); // seq
		VarInt.write(buf, 6);
		buf.writeBytes("ground".getBytes());
		VarInt.write(buf, Payloads.UP_CAP + 1); // claimed json length, no bytes follow
		assertThrows(DecoderException.class, () -> Payloads.Up.CODEC.decode(buf));
		ByteBuf truncated = Unpooled.buffer();
		VarInt.write(truncated, 10);
		truncated.writeBytes("abc".getBytes());
		assertThrows(DecoderException.class, () -> Payloads.utf8(32).decode(truncated));
	}

	@Test
	void roleAndBodyMustBeKnownValues() {
		assertThrows(EncoderException.class,
			() -> Payloads.HsAck.CODEC.encode(Unpooled.buffer(), new Payloads.HsAck(1, "1.0.0", true, "admin", "", 0, 1, 0)));
		assertThrows(EncoderException.class, () -> Payloads.RosterEntry.CODEC.encode(Unpooled.buffer(),
			new Payloads.RosterEntry(UUID.randomUUID(), "a", false, "robot", -1, false, false, false)));
	}

	@Test
	void theRosterIsCappedAt200() {
		List<Payloads.RosterEntry> list = new ArrayList<>();
		for (int i = 0; i < 201; i++) {
			list.add(new Payloads.RosterEntry(new UUID(0, i), "p" + i, false, "male", -1, false, false, false));
		}

		assertThrows(EncoderException.class, () -> Payloads.Roster.CODEC.encode(Unpooled.buffer(), new Payloads.Roster(list)));
		Payloads.Roster ok = new Payloads.Roster(list.subList(0, 200));
		assertEquals(200, roundTrip(Payloads.Roster.CODEC, ok).list().size());
	}

	@Test
	void anUpAtTheCapFitsTheServerboundLimit() {
		ByteBuf buf = Unpooled.buffer();
		Payloads.Up.CODEC.encode(buf, new Payloads.Up(Integer.MAX_VALUE, "x".repeat(Payloads.T_CAP), "x".repeat(Payloads.UP_CAP)));
		// plus the payload id cyberblock:up (a varint-prefixed string) the packet adds
		assertTrue(buf.readableBytes() + 1 + "cyberblock:up".length() <= Payloads.C2S_LIMIT, "up at the cap: " + buf.readableBytes());
		ByteBuf down = Unpooled.buffer();
		Payloads.Down.CODEC.encode(down, new Payloads.Down("x".repeat(Payloads.DOWN_CAP)));
		assertTrue(down.readableBytes() + 1 + "cyberblock:down".length() <= Payloads.S2C_LIMIT);
	}

	@Test
	void typeIdsAreTheContractsIds() {
		assertEquals("cyberblock:hs", Payloads.Hs.TYPE.id().toString());
		assertEquals("cyberblock:hs_ack", Payloads.HsAck.TYPE.id().toString());
		assertEquals("cyberblock:up", Payloads.Up.TYPE.id().toString());
		assertEquals("cyberblock:down", Payloads.Down.TYPE.id().toString());
		assertEquals("cyberblock:roster", Payloads.Roster.TYPE.id().toString());
		assertEquals("cyberblock:perms", Payloads.Perms.TYPE.id().toString());
		assertEquals("cyberblock:content", Payloads.Content.TYPE.id().toString());
		assertEquals("cyberblock:act", Payloads.Act.TYPE.id().toString());
		assertEquals("cyberblock:ui", Payloads.Ui.TYPE.id().toString());
	}
}
