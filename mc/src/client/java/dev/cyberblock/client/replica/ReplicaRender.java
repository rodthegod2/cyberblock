package dev.cyberblock.client.replica;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.HostState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Replica blocks on an active host's screen (megaplan task 3-C1): Cyberpunk already draws Night City under Minecraft, so while
 * Cyberpunk drives this client's camera ({@link HostState#active()}) the {@code cyberblock:nc_*} blocks of task 3-R1 render as
 * nothing (no faces, and no outline unless a creative player aims at one, so the Q5 rule still works). Guests and inactive hosts
 * see them normally. "Active host" here is the picture, not the role: the blocks would double Night City whenever Cyberpunk is the
 * picture under Minecraft, which is exactly when HostState is active.
 *
 * <p>How (verified with javap on the Loom cached Minecraft 26.3 client jar): section meshes are built by
 * {@code SectionCompiler.compile(SectionPos, RenderSectionRegion, VertexSorting, SectionBufferBuilderPack)}, which reads every
 * block, its fluid and its neighbours (face culling, ambient occlusion) through {@code RenderSectionRegion.getBlockState} and
 * {@code getFluidState}. {@code RenderSectionRegionMixin} answers air for a hidden block there, so the section is meshed as if the
 * cell were empty (a player block next to it keeps its face). Switching re-meshes the loaded sections once, through
 * {@code ClientLevel.setSectionRangeDirty(minX, minY, minZ, maxX, maxY, maxZ)} (section coordinates). The outline goes through
 * Fabric's {@code LevelRenderEvents.BEFORE_BLOCK_OUTLINE} (false cancels vanilla's outline).
 *
 * <p>Thread safety: the hidden set and the switch are read by the section compile workers; both are swapped whole (volatile).
 */
public final class ReplicaRender {
	/** The replica materials of task 3-A1 ({@code cyberblock:nc_<material>}); neon comes in the 16 dye colours. */
	public static final List<String> MATERIALS = List.of("concrete_light", "concrete_dark", "asphalt", "sidewalk", "glass", "metal",
		"brick", "tile", "ground", "water");
	/** The raw state must hold this many client ticks (1 s) before the view switches: a short gap in the cam stream never re-meshes. */
	static final int SWITCH_TICKS = 20;
	private static volatile Set<Block> hidden = Set.of();
	private static volatile boolean hiding;
	private static volatile boolean hookSeen;
	private static int differentFor;

	private ReplicaRender() {
	}

	/** From the client entrypoint (after the common init registered the blocks). */
	public static void init() {
		List<Identifier> missing = new ArrayList<>();
		List<Block> found = new ArrayList<>();
		for (Identifier id : replicaIds()) {
			BuiltInRegistries.BLOCK.getOptional(id).ifPresentOrElse(found::add, () -> missing.add(id));
		}

		registerHostHidden(found.toArray(Block[]::new));
		Cyberblock.LOG.info("replica: {} replica block types hidden while Cyberpunk is the picture{}", found.size(),
			missing.isEmpty() ? "" : " (" + missing.size() + " nc_* ids not registered, e.g. " + missing.getFirst() + ")");
		ClientTickEvents.END_CLIENT_TICK.register(ReplicaRender::tick);
		LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outline) -> {
			Minecraft minecraft = Minecraft.getInstance();
			ClientLevel level = minecraft.level;
			boolean creative = minecraft.player != null && minecraft.player.isCreative();
			return level == null || outlineShown(hides(level.getBlockState(outline.pos())), creative);
		});
	}

	/** {@code cyberblock:nc_<material>} for every material, then {@code cyberblock:nc_neon_<colour>} for the 16 dye colours. */
	static List<Identifier> replicaIds() {
		return Stream.concat(MATERIALS.stream(), Stream.of(DyeColor.values()).map(c -> "neon_" + c.getSerializedName()))
			.map(name -> Identifier.fromNamespaceAndPath(Cyberblock.ID, "nc_" + name)).toList();
	}

	/**
	 * Later phases hide their own blocks the same way (Phase 4 device blocks): these render as nothing while Cyberpunk is the
	 * picture. Client thread; re-meshes once when the host view is on now.
	 */
	public static void registerHostHidden(final Block... blocks) {
		Set<Block> next = Collections.newSetFromMap(new IdentityHashMap<>());
		next.addAll(hidden);
		Collections.addAll(next, blocks);
		hidden = Collections.unmodifiableSet(next);
		if (hiding) {
			remesh(Minecraft.getInstance().level);
		}
	}

	/** Any thread (the section compile workers): the state renders as nothing now. */
	public static boolean hides(final BlockState state) {
		return hiding && hidden.contains(state.getBlock());
	}

	/** The host view is on (replica blocks hidden). */
	public static boolean hiding() {
		return hiding;
	}

	/** RenderSectionRegionMixin replaced a hidden block for the first time: the render hook is live (one log line). */
	public static void hookUsed() {
		if (!hookSeen) {
			hookSeen = true;
			Cyberblock.LOG.info("replica: render hook active (RenderSectionRegion meshes hidden replica blocks as air)");
		}
	}

	/** Vanilla's outline is drawn unless the block is hidden; a creative player still sees it (the Q5 rule needs to aim). */
	static boolean outlineShown(final boolean hiddenBlock, final boolean creative) {
		return !hiddenBlock || creative;
	}

	/**
	 * One client tick of the debounce: {@code differentFor} counts the ticks the raw state has differed from the view. Returns the
	 * new count; the view switches when it reaches {@link #SWITCH_TICKS} (the caller then resets it to 0).
	 */
	static int debounce(final boolean view, final boolean raw, final int differentFor) {
		return view == raw ? 0 : differentFor + 1;
	}

	private static void tick(final Minecraft minecraft) {
		boolean raw = HostState.active();
		differentFor = debounce(hiding, raw, differentFor);
		if (differentFor < SWITCH_TICKS) {
			return;
		}

		differentFor = 0;
		hiding = raw;
		int sections = remesh(minecraft.level);
		Cyberblock.LOG.info("replica: {} ({} sections re-meshed)", raw ? "host view, replica blocks hidden" : "guest view, replica blocks shown", sections);
	}

	/** Marks every loaded section dirty once (client thread); returns how many. */
	private static int remesh(final ClientLevel level) {
		Minecraft minecraft = Minecraft.getInstance();
		if (level == null || minecraft.gameRenderer == null) {
			return 0;
		}

		int r = minecraft.options.getEffectiveRenderDistance() + 1;
		int cx = SectionPos.blockToSectionCoord(minecraft.gameRenderer.mainCamera().blockPosition().getX());
		int cz = SectionPos.blockToSectionCoord(minecraft.gameRenderer.mainCamera().blockPosition().getZ());
		level.setSectionRangeDirty(cx - r, level.getMinSectionY(), cz - r, cx + r, level.getMaxSectionY(), cz + r);
		return (2 * r + 1) * (2 * r + 1) * level.getSectionsCount();
	}
}
