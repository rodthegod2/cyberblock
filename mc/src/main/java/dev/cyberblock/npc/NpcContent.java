package dev.cyberblock.npc;

import dev.cyberblock.content.Content;
import dev.cyberblock.content.ContentKind;
import dev.cyberblock.device.Shops;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The Phase 7 content kinds this package reads (megaplan section 1.7): {@code npc_role}, {@code dialogue}, {@code contract} and
 * {@code ripperdoc}, registered here (server-side only: a client gets one screen at a time through {@code cyberblock:ui}); the
 * {@code shop} kind stays Phase 4's (device/Shops, with its sell rows), and {@code landmark} is the Holo-Table's (7T): a reach gig
 * reads a landmark's position from whatever record that kind holds, by a component named {@code pos}. Any thread may read.
 */
public final class NpcContent {
	private static @Nullable ContentKind<NpcRole> roles;
	private static @Nullable ContentKind<Dialogue> dialogue;
	private static @Nullable ContentKind<Contract> contracts;
	private static @Nullable ContentKind<Implant> implants;
	/** Tests: these instead of the loaded content (null: the loaded content). */
	static @Nullable Map<Identifier, NpcRole> testRoles;
	static @Nullable Map<Identifier, Dialogue> testDialogue;
	static @Nullable Map<Identifier, Contract> testContracts;
	static @Nullable Map<Identifier, Implant> testImplants;
	static @Nullable Map<Identifier, Shops.Shop> testShops;

	private NpcContent() {
	}

	/** Once at startup, before the server loads its datapacks. */
	static void register() {
		roles = ContentKind.register("npc_role", NpcRole::parse, false);
		dialogue = ContentKind.register("dialogue", Dialogue::parse, false);
		contracts = ContentKind.register("contract", Contract::parse, false);
		implants = ContentKind.register("ripperdoc", Implant::parse, false);
	}

	private static <T> Map<Identifier, T> all(final @Nullable ContentKind<T> kind, final @Nullable Map<Identifier, T> test) {
		return test != null ? test : kind == null ? Map.of() : Content.all(kind);
	}

	public static Map<Identifier, NpcRole> roles() {
		return all(roles, testRoles);
	}

	static Map<Identifier, Dialogue> dialogue() {
		return all(dialogue, testDialogue);
	}

	static Map<Identifier, Contract> contracts() {
		return all(contracts, testContracts);
	}

	static Map<Identifier, Implant> implants() {
		return all(implants, testImplants);
	}

	/** Phase 4's shop content (its kind is registered by device/Shops). */
	@SuppressWarnings("unchecked")
	static Map<Identifier, Shops.Shop> shops() {
		if (testShops != null) {
			return testShops;
		}

		ContentKind<?> kind = ContentKind.byId("shop");
		return kind == null ? Map.of() : (Map<Identifier, Shops.Shop>) Content.all(kind);
	}

	/** The role for a figure's name-tag label (vendor type or faction), by role id order; null when no role names it. */
	static Map.@Nullable Entry<Identifier, NpcRole> roleFor(final String label) {
		for (Map.Entry<Identifier, NpcRole> e : roles().entrySet()) {
			if (e.getValue().matches(label)) {
				return e;
			}
		}

		return null;
	}

	/**
	 * A landmark's position from the {@code landmark} kind (7T's record, read through its {@code pos} component: a Vec3, a BlockPos or
	 * double[3]); empty when the kind, the landmark or such a component is missing.
	 */
	static Optional<Vec3> landmark(final Identifier id) {
		ContentKind<?> kind = ContentKind.byId("landmark");
		Object entry = kind == null ? null : Content.all(kind).get(id);
		return entry == null ? Optional.empty() : position(entry);
	}

	static Optional<Vec3> position(final Object entry) {
		if (!entry.getClass().isRecord()) {
			return Optional.empty();
		}

		for (RecordComponent c : entry.getClass().getRecordComponents()) {
			if (!c.getName().equals("pos") && !c.getName().equals("position")) {
				continue;
			}

			try {
				java.lang.reflect.Method accessor = c.getAccessor();
				accessor.setAccessible(true);
				Object v = accessor.invoke(entry);
				if (v instanceof Vec3 vec) {
					return Optional.of(vec);
				} else if (v instanceof BlockPos b) {
					return Optional.of(Vec3.atBottomCenterOf(b));
				} else if (v instanceof double[] d && d.length == 3) {
					return Optional.of(new Vec3(d[0], d[1], d[2]));
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				return Optional.empty();
			}
		}

		return Optional.empty();
	}
}
