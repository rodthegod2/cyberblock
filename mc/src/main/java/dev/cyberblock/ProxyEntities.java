package dev.cyberblock;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;

/**
 * The two proxy entity types, differing only in the hitbox. Registered at startup, on both sides; guests see them as figures
 * (client/figure, plan 2026-10-04 task 3-F1).
 */
public final class ProxyEntities {
	/** A pedestrian: 0.6 x 1.8. */
	public static EntityType<ProxyEntity> HUMAN;
	/** A car, bike or truck: 3.0 wide and deep (a square box: a car is about 2 m by 4.5 m, and 2.0 was too easy to miss), 1.6 high. */
	public static EntityType<ProxyEntity> VEHICLE;

	private ProxyEntities() {
	}

	static void register() {
		HUMAN = register("proxy_human", 0.6F, 1.8F);
		VEHICLE = register("proxy_vehicle", 3.0F, 1.6F);
		// the host's kill reports turn proxies into bodies and wrecks (WorldModule.init registers the other proxy messages)
		ProxyManager.listen();
	}

	private static EntityType<ProxyEntity> register(final String name, final float width, final float height) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(Cyberblock.ID, name));
		// tracked within 4 chunks (the host lists NPCs within 48 m) and updated every tick: the client picks targets from these positions
		EntityType<ProxyEntity> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, key,
			EntityType.Builder.of(ProxyEntity::new, MobCategory.MISC).sized(width, height).noSave().noSummon().noLootTable()
				.clientTrackingRange(4).updateInterval(1).build(key));
		FabricDefaultAttributeRegistry.register(type, LivingEntity.createLivingAttributes());
		return type;
	}
}
