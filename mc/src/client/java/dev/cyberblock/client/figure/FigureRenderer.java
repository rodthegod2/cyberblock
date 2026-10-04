package dev.cyberblock.client.figure;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntities;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.client.HostState;
import dev.cyberblock.figure.FigureTags;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

/**
 * A Night City person as guests see it (plan 2026-10-04 tasks 3-F1 and 5-F1): the player body (FigureModel), classic or slim, in the skin the
 * server chose from skin_map (the neutral figure skin when there is none or its texture is missing); idle, walk or run from the host's
 * bits or from the speed it is drawn at; aim, fire, combat and crouch poses; a ragdoll's limp tumble; lying down once dead; a red hit
 * flash; the name tag in the threat colour. Moves at the speed the host reports, smoothed (FigureSmoothing). An active host's own
 * client draws nothing: its Cyberpunk shows the real NPC.
 */
public final class FigureRenderer extends LivingEntityRenderer<ProxyEntity, FigureModel.State, FigureModel> {
	private static final Identifier NEUTRAL = FigureRules.NEUTRAL;
	/** What the log last said: which neutral skin, and whether this client draws figures (null: nothing yet). */
	private static Boolean loggedNeutral, loggedDrawn;
	/** Whether each skin texture is in the loaded resources; asked again after each join (a server pack may add skins). */
	private static final Map<Identifier, Boolean> present = new HashMap<>();
	private static final Set<Identifier> loggedMissing = new HashSet<>();
	private final Identifier neutral;
	private final FigureModel wide, slim;
	private final Map<Entity, Track> tracks = new WeakHashMap<>();

	/** One figure's drawing state across frames: its smoothing and its stride phase. */
	static final class Track {
		final FigureSmoothing smoothing = new FigureSmoothing();
		float phase;
		long nanos;
	}

	/** CyberblockClient: guests see both kinds of proxy as figures, with their effects. */
	public static void register() {
		EntityRenderers.register(ProxyEntities.HUMAN, FigureRenderer::new);
		EntityRenderers.register(ProxyEntities.VEHICLE, VehicleFigureRenderer::new);
		ClientTickEvents.END_CLIENT_TICK.register(FigureEffects::tick);
		ClientTickEvents.END_CLIENT_TICK.register(FigureStats::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			present.clear();
			loggedMissing.clear();
		});
	}

	private FigureRenderer(final EntityRendererProvider.Context context) {
		super(context, new FigureModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
		this.wide = this.model;
		this.slim = new FigureModel(context.bakeLayer(ModelLayers.PLAYER_SLIM));
		boolean neutral = context.getResourceManager().getResource(NEUTRAL).isPresent();
		this.neutral = neutral ? NEUTRAL : DefaultPlayerSkin.getDefaultTexture();
		if (!Boolean.valueOf(neutral).equals(loggedNeutral)) {
			loggedNeutral = neutral;
			Cyberblock.LOG.info(neutral ? "figures: people without a skin wear the neutral figure skin ({})" : "figures: no neutral figure skin ({}), people without a skin wear the vanilla default skin", NEUTRAL);
		}
	}

	/** Client thread, every frame: whether this client draws figures at all (said in the log when it changes). */
	static boolean drawn() {
		boolean drawn = FigureRules.drawn(HostState.active());
		if (!Boolean.valueOf(drawn).equals(loggedDrawn)) {
			loggedDrawn = drawn;
			Cyberblock.LOG.info(drawn ? "figures: shown (this client is not an active host)" : "figures: hidden (active host: Cyberpunk shows the real NPCs)");
		}

		return drawn;
	}

	/** Where to draw this figure this frame: smoothed per figure, kept as long as the figure exists. Returns its track. */
	static Track smooth(final Map<Entity, Track> tracks, final Entity entity, final EntityRenderState state) {
		Track track = tracks.computeIfAbsent(entity, e -> new Track());
		double[] at = track.smoothing.at(state.x, state.y, state.z, System.nanoTime());
		state.x = at[0];
		state.y = at[1];
		state.z = at[2];
		return track;
	}

	/** The texture for a skin id: its own when the resources have it, else the neutral skin (the log names each missing one once). */
	private Identifier texture(final String skin) {
		Identifier id = FigureRules.skinTexture(skin);
		if (id.equals(NEUTRAL)) {
			return this.neutral;
		}

		boolean found = present.computeIfAbsent(id, i -> Minecraft.getInstance().getResourceManager().getResource(i).isPresent());
		if (!found && loggedMissing.add(id)) {
			Cyberblock.LOG.warn("figures: skin {} has no texture ({}), the neutral skin instead", skin, id);
		}

		return found ? id : this.neutral;
	}

	@Override
	public boolean shouldRender(final ProxyEntity entity, final Frustum frustum, final double x, final double y, final double z, final float partialTicks) {
		boolean draw = drawn() && super.shouldRender(entity, frustum, x, y, z, partialTicks);
		FigureStats.considered(entity.getId(), draw);
		return draw;
	}

	@Override
	public FigureModel.State createRenderState() {
		return new FigureModel.State();
	}

	@Override
	public void extractRenderState(final ProxyEntity entity, final FigureModel.State state, final float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTicks, this.itemModelResolver);
		// the proxy is invisible to vanilla (and to mobs' eyes); the figure is what guests see of it
		state.isInvisible = false;
		state.isInvisibleToPlayer = false;
		Track track = smooth(this.tracks, entity, state);
		FigureEffects.Fx fx = FigureEffects.of(entity);
		int anim = entity.anim();
		boolean dead = entity.dead() || (anim & FigureTags.DEAD) != 0;
		state.slim = entity.slim();
		state.texture = this.texture(entity.lookId());
		state.ragdollSeconds = !dead && fx.ragdollTicks > 0 ? (fx.ragdollTicks + partialTicks) / 20.0F : 0.0F;
		boolean still = dead || state.ragdollSeconds > 0.0F;
		state.aim = !still && (anim & FigureTags.AIM) != 0;
		state.fire = !still && (anim & FigureTags.FIRE) != 0;
		state.combat = !still && (anim & FigureTags.COMBAT) != 0;
		state.isCrouching = !still && (anim & FigureTags.CROUCH) != 0;
		state.hasRedOverlay = fx.flash > 0;

		// the stride: the host's walk and run bits, or (Phase 0 f fallback) the speed the figure is drawn at
		long now = System.nanoTime();
		double dt = track.nanos == 0L ? 0.0 : Math.min((now - track.nanos) / 1.0E9, 0.25);
		track.nanos = now;
		double speed = track.smoothing.speed();
		FigureAnim.Gait gait = still ? FigureAnim.Gait.IDLE : FigureAnim.gait(anim, speed);
		track.phase += (float) (FigureAnim.strideRate(gait, speed) * dt);
		state.walkAnimationPos = track.phase;
		state.walkAnimationSpeed = FigureAnim.swing(gait, speed);
		if (gait != FigureAnim.Gait.IDLE || state.aim || state.fire || state.combat || state.isCrouching || state.ragdollSeconds > 0.0F) {
			FigureStats.animated(entity.getId());
		}
		if (dead) {
			// vanilla's death fall (setupRotations: on its side within a second)
			state.deathTime = Math.max(1.0F, (entity.dead() ? entity.deadTicks() : fx.deadBitTicks) + partialTicks);
		}
	}

	@Override
	public void submit(final FigureModel.State state, final PoseStack poseStack, final SubmitNodeCollector collector, final CameraRenderState camera) {
		this.model = state.slim ? this.slim : this.wide;
		super.submit(state, poseStack, collector, camera);
	}

	@Override
	protected void setupRotations(final FigureModel.State state, final PoseStack poseStack, final float bodyRot, final float scale) {
		super.setupRotations(state, poseStack, bodyRot, scale);
		if (state.ragdollSeconds > 0.0F) {
			// the limp tumble: over onto its back, a little bounce as it lands
			poseStack.rotateDegrees(Axis.XP, FigureAnim.ragdollTilt(state.ragdollSeconds));
		}
	}

	@Override
	protected boolean shouldShowName(final ProxyEntity entity, final double distanceToCameraSq) {
		return FigureRules.showTag(entity.dead() || (entity.anim() & FigureTags.DEAD) != 0, distanceToCameraSq);
	}

	@Override
	protected Component getNameTag(final ProxyEntity entity) {
		return FigureRules.tag(entity.label(), entity.threat(), entity.hostile(), false);
	}

	@Override
	public Identifier getTextureLocation(final FigureModel.State state) {
		return state.texture;
	}
}
