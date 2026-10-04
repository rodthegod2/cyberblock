package dev.cyberblock.npc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.content.Content;
import dev.cyberblock.content.ContentKind;
import dev.cyberblock.figure.FigureTags;
import dev.cyberblock.figure.SkinMap;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The hub NPCs (megaplan task 7-N1): one {@link HubNpc} per entry of the {@code figures} of the Afterlife's layout (7A's
 * {@code data/cyberblock/cyberblock/hub/afterlife.json}, the file 7H builds the hub from), in the hub dimension
 * {@code cyberblock:afterlife}, always there and independent of hosts. A figure: {@code id}, {@code role} (an {@code npc_role}),
 * {@code pos} (layout cells from the layout's {@code origin}) and {@code yaw}, {@code gender} (the {@code skin_map} row: female on the
 * slim model), {@code name}, {@code shop} (vendors). Every 5 s each figure whose chunk is loaded gets its NPC back if it is missing
 * (NPCs are never saved), one that drifted is put back, and NPCs whose figure a {@code /reload} removed go. Without the dimension
 * (before 7H) the NPCs wait, said once in the log. Server thread.
 */
public final class HubNpcs {
	public static final ResourceKey<Level> AFTERLIFE = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Cyberblock.ID, "afterlife"));
	/** The layout file (the same one 7H's Afterlife.LAYOUT names). */
	static final Identifier LAYOUT = Identifier.fromNamespaceAndPath(Cyberblock.ID, "cyberblock/hub/afterlife.json");
	static final int MAX_FIGURES = 32;
	public static EntityType<HubNpc> TYPE;

	/** One hub figure in world coordinates. gender: 0 unknown, 1 male, 2 female (FigureTags). */
	record Figure(String id, Identifier role, double x, double y, double z, float yaw, int gender, String name, @Nullable Identifier shop) {
	}

	private static List<Figure> figures = List.of();
	private static final Map<String, HubNpc> spawned = new HashMap<>();
	private static boolean loggedMissing;

	private HubNpcs() {
	}

	static void register() {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Cyberblock.ID, "hub_npc"));
		TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, key, EntityType.Builder.of(HubNpc::new, MobCategory.MISC).sized(0.6F, 1.8F)
			.noSave().noSummon().clientTrackingRange(8).build(key));
		FabricDefaultAttributeRegistry.register(TYPE, Mob.createMobAttributes());
	}

	/** The layout's figures in world coordinates (origin + pos). Throws IllegalArgumentException for a bad layout. Pure. */
	static List<Figure> parse(final JsonObject layout) {
		if (!(layout.get("origin") instanceof JsonArray o) || o.size() != 3) {
			throw new IllegalArgumentException("origin must be [x, y, z]");
		}

		int[] origin = {o.get(0).getAsInt(), o.get(1).getAsInt(), o.get(2).getAsInt()};
		JsonArray list = NpcJson.array(layout, "figures", MAX_FIGURES);
		List<Figure> out = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		for (JsonElement e : list) {
			JsonObject f = NpcJson.object(e, "a figure");
			String id = Content.string(f, "id", 32);
			if (!id.matches("[a-z0-9_]+") || !ids.add(id)) {
				throw new IllegalArgumentException("figure ids are a-z, 0-9 and _ and unique: " + id);
			}

			double[] p = Content.position(f, "pos");
			String gender = NpcJson.string(f, "gender", 8, "");
			Identifier shop = f.has("shop") ? NpcJson.id(Content.string(f, "shop", 128), "shop") : null;
			out.add(new Figure(id, NpcJson.id(Content.string(f, "role", 128), "role"), origin[0] + p[0], origin[1] + p[1], origin[2] + p[2],
				(float) NpcJson.number(f, "yaw", -360.0, 360.0, 0.0), gender.equals("female") ? 2 : gender.equals("male") ? 1 : 0,
				NpcJson.string(f, "name", 32, ""), shop));
		}

		return List.copyOf(out);
	}

	/** Server start and every /reload: the figures from the layout (none, logged, when it is missing or bad). */
	static void load(final MinecraftServer server) {
		Optional<Resource> file = server.getResourceManager().getResource(LAYOUT);
		if (file.isEmpty()) {
			figures = List.of();
			Cyberblock.LOG.info("npc: no hub layout {}: no hub NPCs", LAYOUT);
			return;
		}

		try (Reader reader = file.get().openAsReader()) {
			figures = parse(JsonParser.parseReader(reader).getAsJsonObject());
			Cyberblock.LOG.info("npc: hub layout has {} figures", figures.size());
		} catch (Exception e) {
			figures = List.of();
			Cyberblock.LOG.warn("npc: hub layout {} skipped for its figures: {}", LAYOUT, e.getMessage());
		}
	}

	/** The skin_map skin for a person of this role and gender (FigureTags role tag = the role id's path). */
	@SuppressWarnings("unchecked")
	static SkinMap.Skin skin(final Identifier role, final int gender, final String id) {
		ContentKind<?> kind = ContentKind.byId("skin_map");
		Collection<SkinMap.Row> rows = kind == null ? List.of() : (Collection<SkinMap.Row>) Content.all(kind).values();
		return SkinMap.resolve(rows, new FigureTags(role.getPath(), "", gender, "", 0, "", -1, 1.0F), id.hashCode());
	}

	static void tick(final MinecraftServer server) {
		if (server.getTickCount() % 100 != 0) {
			return;
		}

		ServerLevel level = server.getLevel(AFTERLIFE);
		if (level == null) {
			if (!loggedMissing && !figures.isEmpty()) {
				loggedMissing = true;
				Cyberblock.LOG.info("npc: no {} dimension on this server yet: the {} hub NPCs wait for the hub (7H)", AFTERLIFE.identifier(), figures.size());
			}

			return;
		}

		Set<String> wanted = new HashSet<>();
		for (Figure f : figures) {
			if (NpcContent.roles().containsKey(f.role())) {
				wanted.add(f.id());
				keep(level, f);
			}
		}

		for (Iterator<Map.Entry<String, HubNpc>> it = spawned.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, HubNpc> e = it.next();
			if (!wanted.contains(e.getKey())) {
				e.getValue().discard();
				it.remove();
				Cyberblock.LOG.info("npc: hub NPC {} removed (its figure or role is gone)", e.getKey());
			}
		}
	}

	private static void keep(final ServerLevel level, final Figure f) {
		HubNpc npc = spawned.get(f.id());
		if (npc != null && !npc.isRemoved() && npc.level() == level) {
			if (npc.distanceToSqr(f.x(), f.y(), f.z()) > 1.0) {
				npc.snapTo(f.x(), f.y(), f.z(), f.yaw(), 0.0F);
			}

			return;
		}

		spawned.remove(f.id());
		if (!level.isLoaded(BlockPos.containing(f.x(), f.y(), f.z()))) {
			return;
		}

		npc = spawn(level, f);
		if (npc != null) {
			spawned.put(f.id(), npc);
			Cyberblock.LOG.info("npc: hub NPC {} ({}) at {} {} {}", f.name().isEmpty() ? f.id() : f.name(), f.role(), f.x(), f.y(), f.z());
		}
	}

	/** A role NPC standing at the figure's place (also /cyberblock npc spawn, outside the hub). Null when the level refused it. */
	static @Nullable HubNpc spawn(final ServerLevel level, final Figure f) {
		HubNpc npc = TYPE.create(level, EntitySpawnReason.COMMAND);
		if (npc == null) {
			return null;
		}

		SkinMap.Skin skin = skin(f.role(), f.gender(), f.id());
		npc.snapTo(f.x(), f.y(), f.z(), f.yaw(), 0.0F);
		npc.setYHeadRot(f.yaw());
		npc.setYBodyRot(f.yaw());
		npc.setup(f.role(), f.name(), skin.texture().equals(SkinMap.NEUTRAL) ? "" : skin.texture(), skin.slim(), f.id(), f.shop());
		return level.addFreshEntity(npc) ? npc : null;
	}

	static void clear() {
		spawned.clear();
		loggedMissing = false;
	}
}
