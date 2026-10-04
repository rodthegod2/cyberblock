package dev.cyberblock.client.figure;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.ProxyEntities;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.client.HostState;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

/**
 * A Night City person as guests see it (plan 2026-10-04 task 3-F1): the player model in the neutral figure skin (3A ships it; the
 * vanilla default skin when it is missing), walking at the speed the host reports, smoothed (FigureSmoothing), a name tag that is red
 * for a hostile, lying down for 3 s once dead. An active host's own client draws nothing: its Cyberpunk shows the real NPC. Phase 5
 * gives figures their skins and animations.
 */
public final class FigureRenderer extends LivingEntityRenderer<ProxyEntity, AvatarRenderState, PlayerModel> {
	static final Identifier NEUTRAL = Identifier.fromNamespaceAndPath(Cyberblock.ID, "textures/entity/figure/neutral.png");
	/** What the log last said: which skin, and whether this client draws figures (null: nothing yet). */
	private static Boolean loggedNeutral, loggedDrawn;
	private final Identifier skin;
	private final Map<Entity, FigureSmoothing> smoothing = new WeakHashMap<>();

	/** CyberblockClient: guests see both kinds of proxy as figures. */
	public static void register() {
		EntityRenderers.register(ProxyEntities.HUMAN, FigureRenderer::new);
		EntityRenderers.register(ProxyEntities.VEHICLE, VehicleFigureRenderer::new);
	}

	private FigureRenderer(final EntityRendererProvider.Context context) {
		super(context, new PlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
		boolean neutral = context.getResourceManager().getResource(NEUTRAL).isPresent();
		this.skin = neutral ? NEUTRAL : DefaultPlayerSkin.getDefaultTexture();
		if (!Boolean.valueOf(neutral).equals(loggedNeutral)) {
			loggedNeutral = neutral;
			Cyberblock.LOG.info(neutral ? "figures: people wear the neutral figure skin ({})" : "figures: no neutral figure skin ({}), people wear the vanilla default skin", NEUTRAL);
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

	/** Where to draw this figure this frame: smoothed per figure, kept as long as the figure exists. */
	static void smooth(final Map<Entity, FigureSmoothing> smoothing, final Entity entity, final EntityRenderState state) {
		double[] at = smoothing.computeIfAbsent(entity, e -> new FigureSmoothing()).at(state.x, state.y, state.z, System.nanoTime());
		state.x = at[0];
		state.y = at[1];
		state.z = at[2];
	}

	@Override
	public boolean shouldRender(final ProxyEntity entity, final Frustum frustum, final double x, final double y, final double z, final float partialTicks) {
		return drawn() && super.shouldRender(entity, frustum, x, y, z, partialTicks);
	}

	@Override
	public AvatarRenderState createRenderState() {
		return new AvatarRenderState();
	}

	@Override
	public void extractRenderState(final ProxyEntity entity, final AvatarRenderState state, final float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTicks, this.itemModelResolver);
		// the proxy is invisible to vanilla (and to mobs' eyes); the figure is what guests see of it
		state.isInvisible = false;
		state.isInvisibleToPlayer = false;
		state.showHat = state.showJacket = state.showLeftPants = state.showRightPants = state.showLeftSleeve = state.showRightSleeve = true;
		smooth(this.smoothing, entity, state);
		if (entity.dead()) {
			// vanilla's death fall (setupRotations: on its side within a second), without the red flash or walking legs
			state.deathTime = Math.max(1.0F, entity.deadTicks() + partialTicks);
			state.hasRedOverlay = false;
			state.walkAnimationSpeed = 0.0F;
		}
	}

	@Override
	protected boolean shouldShowName(final ProxyEntity entity, final double distanceToCameraSq) {
		return FigureRules.showTag(entity.dead(), distanceToCameraSq);
	}

	@Override
	protected Component getNameTag(final ProxyEntity entity) {
		return FigureRules.tag(entity.hostile(), false);
	}

	@Override
	public Identifier getTextureLocation(final AvatarRenderState state) {
		return this.skin;
	}
}
