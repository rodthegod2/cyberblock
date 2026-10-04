package dev.cyberblock.holo;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cyberblock.Cyberblock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * The Spawn Beacons placed in Night City (megaplan 7-T1), kept with the world as SavedData {@code cyberblock:holo_beacons} on the
 * overworld (in the HostsData pattern): where, who placed it, and the match and team it belonged to when placed (Phase 8; "" and
 * -1 outside matches). At most {@link #PER_PLAYER} per player. Server thread only.
 */
public final class Beacons extends SavedData {
	public static final int PER_PLAYER = 4;

	public record Beacon(BlockPos pos, UUID owner, String ownerName, String match, int team) {
		static final Codec<Beacon> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockPos.CODEC.fieldOf("pos").forGetter(Beacon::pos),
			UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Beacon::owner),
			Codec.STRING.fieldOf("owner_name").forGetter(Beacon::ownerName),
			Codec.STRING.optionalFieldOf("match", "").forGetter(Beacon::match),
			Codec.INT.optionalFieldOf("team", -1).forGetter(Beacon::team)
		).apply(i, Beacon::new));

		/** The id the table and its spawn requests use: "x,y,z". */
		public String id() {
			return this.pos.getX() + "," + this.pos.getY() + "," + this.pos.getZ();
		}

		public @Nullable String matchOrNull() {
			return this.match.isEmpty() ? null : this.match;
		}
	}

	static final Codec<Beacons> CODEC = RecordCodecBuilder.create(i -> i.group(
		Beacon.CODEC.listOf().fieldOf("beacons").forGetter(b -> b.list)
	).apply(i, Beacons::new));
	/** No data fixer: Fabric API's SavedDataStorageMixin reads a type without one as it is. */
	static final SavedDataType<Beacons> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Cyberblock.ID, "holo_beacons"), Beacons::new, CODEC, null);
	private final List<Beacon> list;

	Beacons() {
		this(List.of());
	}

	Beacons(final List<Beacon> list) {
		this.list = new ArrayList<>(list);
	}

	List<Beacon> all() {
		return List.copyOf(this.list);
	}

	int count(final UUID owner) {
		return (int) this.list.stream().filter(b -> b.owner().equals(owner)).count();
	}

	@Nullable Beacon at(final BlockPos pos) {
		return this.list.stream().filter(b -> b.pos().equals(pos)).findFirst().orElse(null);
	}

	@Nullable Beacon byId(final String id) {
		return this.list.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
	}

	/** Adds a beacon (one per position); false when the owner already has {@link #PER_PLAYER}. */
	boolean add(final Beacon beacon) {
		if (this.count(beacon.owner()) >= PER_PLAYER) {
			return false;
		}

		this.list.removeIf(b -> b.pos().equals(beacon.pos()));
		this.list.add(beacon);
		this.setDirty();
		return true;
	}

	boolean remove(final BlockPos pos) {
		boolean removed = this.list.removeIf(b -> b.pos().equals(pos));
		if (removed) {
			this.setDirty();
		}

		return removed;
	}
}
