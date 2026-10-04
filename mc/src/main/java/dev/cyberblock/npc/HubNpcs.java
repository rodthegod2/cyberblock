package dev.cyberblock.npc;

import dev.cyberblock.Cyberblock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;

/**
 * The hub NPCs (megaplan task 7-N1): one {@link HubNpc} per {@code hub} spot of every {@code npc_role} entry, in the Afterlife hub
 * dimension ({@code cyberblock:afterlife}, built by 7H), always there and independent of hosts. Every 5 s each spot whose chunk is
 * loaded gets its NPC back if it is missing (NPCs are never saved), one that drifted is put back, and NPCs of spots that a
 * {@code /reload} removed go. Without the dimension (before 7H) the NPCs wait, said once in the log. Server thread.
 */
public final class HubNpcs {
	public static final ResourceKey<Level> AFTERLIFE = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Cyberblock.ID, "afterlife"));
	public static EntityType<HubNpc> TYPE;
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

	/** Spot keys: "&lt;role id&gt;#&lt;index&gt;". */
	static String spotKey(final Identifier role, final int index) {
		return role + "#" + index;
	}

	static void tick(final MinecraftServer server) {
		if (server.getTickCount() % 100 != 0) {
			return;
		}

		ServerLevel level = server.getLevel(AFTERLIFE);
		if (level == null) {
			if (!loggedMissing && NpcContent.roles().values().stream().anyMatch(r -> !r.hub().isEmpty())) {
				loggedMissing = true;
				Cyberblock.LOG.info("npc: no {} dimension on this server yet: hub NPCs wait for the hub (7H)", AFTERLIFE.identifier());
			}

			return;
		}

		Set<String> wanted = new HashSet<>();
		for (Map.Entry<Identifier, NpcRole> e : NpcContent.roles().entrySet()) {
			for (int i = 0; i < e.getValue().hub().size(); i++) {
				String key = spotKey(e.getKey(), i);
				wanted.add(key);
				keep(level, key, e.getKey(), e.getValue(), e.getValue().hub().get(i));
			}
		}

		for (Iterator<Map.Entry<String, HubNpc>> it = spawned.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, HubNpc> e = it.next();
			if (!wanted.contains(e.getKey())) {
				e.getValue().discard();
				it.remove();
				Cyberblock.LOG.info("npc: hub NPC {} removed (its spot is gone from npc_role)", e.getKey());
			}
		}
	}

	private static void keep(final ServerLevel level, final String key, final Identifier role, final NpcRole r, final NpcRole.HubSpot spot) {
		HubNpc npc = spawned.get(key);
		if (npc != null && !npc.isRemoved() && npc.level() == level) {
			npc.setup(role, spot.name(), spot.skin(), spot.slim(), key);
			if (npc.distanceToSqr(spot.x(), spot.y(), spot.z()) > 1.0) {
				npc.snapTo(spot.x(), spot.y(), spot.z(), spot.yaw(), 0.0F);
			}

			return;
		}

		if (!level.isLoaded(BlockPos.containing(spot.x(), spot.y(), spot.z()))) {
			spawned.remove(key);
			return;
		}

		npc = spawn(level, role, spot, key);
		if (npc != null) {
			spawned.put(key, npc);
			Cyberblock.LOG.info("npc: hub NPC {} ({}) at {} {} {}", spot.name().isEmpty() ? key : spot.name(), r.screen(), spot.x(), spot.y(), spot.z());
		}
	}

	/** A role NPC standing at the spot (also /cyberblock npc spawn, outside the hub). Null when the level refused it. */
	static HubNpc spawn(final ServerLevel level, final Identifier role, final NpcRole.HubSpot spot, final String key) {
		HubNpc npc = TYPE.create(level, EntitySpawnReason.COMMAND);
		if (npc == null) {
			return null;
		}

		npc.snapTo(spot.x(), spot.y(), spot.z(), spot.yaw(), 0.0F);
		npc.setYHeadRot(spot.yaw());
		npc.setYBodyRot(spot.yaw());
		npc.setup(role, spot.name(), spot.skin(), spot.slim(), key);
		return level.addFreshEntity(npc) ? npc : null;
	}

	static void clear() {
		spawned.clear();
		loggedMissing = false;
	}
}
