package dev.cyberblock.hub;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cyberblock.Cyberblock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * The hub's data kept with the world ({@code cyberblock:hub}, in the overworld's storage): which layout the Afterlife was built from
 * (empty: not built yet; it is built once), where its arrivals stand and below which y a player has fallen off, and for every player in
 * the hub where they came from, so leaving (the portal, the command or the pause screen) takes them back, also after a reconnect or a
 * server restart. Server thread only.
 */
final class HubData extends SavedData {
	/** Where a player came from: the level id, feet position, rotation, and whether they hung with no gravity (no host yet). */
	record Return(String level, double x, double y, double z, float yaw, float pitch, boolean noGravity) {
		static final Codec<Return> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("level").forGetter(Return::level),
			Codec.DOUBLE.fieldOf("x").forGetter(Return::x),
			Codec.DOUBLE.fieldOf("y").forGetter(Return::y),
			Codec.DOUBLE.fieldOf("z").forGetter(Return::z),
			Codec.FLOAT.fieldOf("yaw").forGetter(Return::yaw),
			Codec.FLOAT.fieldOf("pitch").forGetter(Return::pitch),
			Codec.BOOL.optionalFieldOf("no_gravity", false).forGetter(Return::noGravity)
		).apply(i, Return::new));

		static Return of(final ServerPlayer player) {
			return new Return(player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(), player.getYRot(),
				player.getXRot(), player.isNoGravity());
		}
	}

	static final Codec<HubData> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.optionalFieldOf("built", "").forGetter(d -> d.built),
		Codec.DOUBLE.listOf().optionalFieldOf("spawn", List.of()).forGetter(d -> d.spawn),
		Codec.INT.optionalFieldOf("void_y", 0).forGetter(d -> d.voidY),
		Codec.unboundedMap(UUIDUtil.STRING_CODEC, Return.CODEC).optionalFieldOf("returns", Map.of()).forGetter(d -> d.returns)
	).apply(i, HubData::new));
	/** No data fixer: Fabric API's SavedDataStorageMixin reads a type without one as it is (as HostsData). */
	static final SavedDataType<HubData> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Cyberblock.ID, "hub"), HubData::new, CODEC, null);
	private String built;
	/** x, y, z, yaw of the arrival point; empty before the first build. */
	private List<Double> spawn;
	private int voidY;
	private final Map<UUID, Return> returns;

	HubData() {
		this("", List.of(), 0, Map.of());
	}

	private HubData(final String built, final List<Double> spawn, final int voidY, final Map<UUID, Return> returns) {
		this.built = built;
		this.spawn = List.copyOf(spawn);
		this.voidY = voidY;
		this.returns = new HashMap<>(returns);
	}

	static HubData get(final MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	/** The layout the hub was built from ("" before the first build). */
	String built() {
		return this.built;
	}

	void built(final String from, final HubLayout layout) {
		double[] p = layout.spawnWorld();
		this.built = from;
		this.spawn = List.of(p[0], p[1], p[2], (double) layout.spawnYaw());
		this.voidY = layout.voidY();
		this.setDirty();
	}

	/** The arrival point {x, y, z, yaw}, or null before the hub was built. */
	double @Nullable [] spawn() {
		return this.spawn.size() == 4 ? new double[] {this.spawn.get(0), this.spawn.get(1), this.spawn.get(2), this.spawn.get(3)} : null;
	}

	int voidY() {
		return this.voidY;
	}

	void setReturn(final UUID player, final Return r) {
		this.returns.put(player, r);
		this.setDirty();
	}

	@Nullable Return takeReturn(final UUID player) {
		Return r = this.returns.remove(player);
		if (r != null) {
			this.setDirty();
		}

		return r;
	}

	boolean hasReturn(final UUID player) {
		return this.returns.containsKey(player);
	}
}
