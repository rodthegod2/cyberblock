package dev.cyberblock.hack.redstone;

import dev.cyberblock.cyber.Hack;
import dev.cyberblock.hack.redstone.RedstoneRules.Deck;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * The saved state of every redstone hacking block (megaplan 6-R1), one type for all six: the Cyberdeck Link (Device Relay, Breach
 * Port), who acts through the block (whoever placed it, then whoever last linked or configured it: the owner's guest toggles are
 * checked against that player), the screen's choices (Proximity Sensor targets and range, Breach Port hack and range) and the
 * Cyberdeck block's RAM. Saved with the chunk, so links survive restarts.
 */
public final class HackBlockEntity extends BlockEntity {
	private @Nullable Link link;
	/** For the status line: "door (Door) of CbHost1". */
	private String linkLabel = "";
	private @Nullable UUID actor;
	private int mask = RedstoneRules.ALL_TARGETS;
	private int range = RedstoneRules.DEFAULT_RANGE;
	private Hack hack = Hack.PING;
	private @Nullable Deck deck;
	/** Last refusal or result shown on a right-click (not saved). */
	String status = "";

	public HackBlockEntity(final BlockEntityType<?> type, final BlockPos pos, final BlockState state) {
		super(type, pos, state);
	}

	public @Nullable Link link() {
		return this.link;
	}

	public String linkLabel() {
		return this.linkLabel;
	}

	void link(final Link newLink, final String label, final UUID by) {
		this.link = newLink;
		this.linkLabel = label;
		this.actor = by;
		this.setChanged();
	}

	public @Nullable UUID actor() {
		return this.actor;
	}

	void actor(final @Nullable UUID by) {
		this.actor = by;
		this.setChanged();
	}

	public int mask() {
		return this.mask;
	}

	void mask(final int m) {
		this.mask = m & RedstoneRules.ALL_TARGETS;
		this.setChanged();
	}

	public int range() {
		return this.range;
	}

	void range(final int r) {
		this.range = RedstoneRules.range(r);
		this.setChanged();
	}

	public Hack hack() {
		return this.hack;
	}

	void hack(final Hack h) {
		this.hack = h;
		this.setChanged();
	}

	/** The Cyberdeck block's RAM (full the first time it is read). */
	Deck deck(final long now) {
		if (this.deck == null) {
			this.deck = Deck.full(now);
		}

		return this.deck;
	}

	void deck(final Deck d) {
		this.deck = d;
		this.setChanged();
	}

	@Override
	protected void saveAdditional(final ValueOutput out) {
		super.saveAdditional(out);
		if (this.link != null) {
			ValueOutput l = out.child("link");
			l.store("owner", UUIDUtil.CODEC, this.link.owner());
			l.putDouble("x", this.link.x());
			l.putDouble("y", this.link.y());
			l.putDouble("z", this.link.z());
			l.putString("cls", this.link.cls());
			l.putString("label", this.linkLabel);
		}

		if (this.actor != null) {
			out.store("actor", UUIDUtil.CODEC, this.actor);
		}

		out.putInt("mask", this.mask);
		out.putInt("range", this.range);
		out.putString("hack", this.hack.wire());
		if (this.deck != null) {
			out.putInt("ram", this.deck.stored());
			out.putLong("ramSince", this.deck.since());
		}
	}

	@Override
	protected void loadAdditional(final ValueInput in) {
		super.loadAdditional(in);
		this.link = null;
		this.linkLabel = "";
		in.child("link").ifPresent(l -> {
			UUID owner = l.read("owner", UUIDUtil.CODEC).orElse(null);
			String cls = l.getStringOr("cls", "");
			try {
				this.link = owner == null ? null : new Link(owner, l.getDoubleOr("x", Double.NaN), l.getDoubleOr("y", Double.NaN), l.getDoubleOr("z", Double.NaN), cls);
				this.linkLabel = l.getStringOr("label", "");
			} catch (IllegalArgumentException bad) {
				this.link = null; // a hand-edited save: the block is simply not linked
			}
		});
		this.actor = in.read("actor", UUIDUtil.CODEC).orElse(null);
		this.mask = in.getIntOr("mask", RedstoneRules.ALL_TARGETS) & RedstoneRules.ALL_TARGETS;
		this.range = RedstoneRules.range(in.getIntOr("range", RedstoneRules.DEFAULT_RANGE));
		Hack h = Hack.byWire(in.getStringOr("hack", Hack.PING.wire()));
		this.hack = h == null || h.needsDevice() ? Hack.PING : h;
		this.deck = in.getInt("ram").map(ram -> new Deck(Math.clamp(ram, 0, RedstoneRules.DECK_MAX_RAM), in.getLongOr("ramSince", 0L))).orElse(null);
	}
}
