package dev.cyberblock.net;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cyberblock.Cyberblock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Per-host data kept with the world (megaplan sections 1.6 and 1.13: the plan's SavedData {@code cyberblock_hosts}, saved as
 * {@code cyberblock:hosts} in the PlacedBlocks pattern): the guest toggles each host chose and its last session time (for Phase 3's
 * Offline Builds). Server thread only.
 */
public final class HostsData extends SavedData {
	/** One host: Permissions bits, the Phase 4 device-type bits, and when its last session ended (epoch ms, 0 = never). */
	public record Entry(int perms, int devices, long lastSession) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("perms").forGetter(Entry::perms),
			Codec.INT.fieldOf("devices").forGetter(Entry::devices),
			Codec.LONG.optionalFieldOf("last_session", 0L).forGetter(Entry::lastSession)
		).apply(i, Entry::new));
	}

	static final Codec<HostsData> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC).fieldOf("hosts").forGetter(data -> data.hosts)
	).apply(i, HostsData::new));
	/** No data fixer: Fabric API's SavedDataStorageMixin reads a type without one as it is. */
	static final SavedDataType<HostsData> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Cyberblock.ID, "hosts"), HostsData::new, CODEC, null);
	private final Map<UUID, Entry> hosts;

	HostsData() {
		this(Map.of());
	}

	HostsData(final Map<UUID, Entry> hosts) {
		this.hosts = new HashMap<>(hosts);
	}

	/** The host's toggles, the defaults when it never changed them. */
	Permissions perms(final UUID host) {
		Entry e = this.hosts.get(host);
		return e == null ? Permissions.DEFAULT : new Permissions(e.perms(), e.devices());
	}

	void setPerms(final UUID host, final Permissions perms) {
		Entry old = this.hosts.get(host);
		Entry e = new Entry(perms.bits(), perms.devices(), old == null ? 0L : old.lastSession());
		if (!e.equals(old)) {
			this.hosts.put(host, e);
			this.setDirty();
		}
	}

	long lastSession(final UUID host) {
		Entry e = this.hosts.get(host);
		return e == null ? 0L : e.lastSession();
	}

	void setLastSession(final UUID host, final long epochMs) {
		Permissions p = this.perms(host);
		this.hosts.put(host, new Entry(p.bits(), p.devices(), epochMs));
		this.setDirty();
	}
}
