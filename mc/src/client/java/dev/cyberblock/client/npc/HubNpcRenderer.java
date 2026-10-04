package dev.cyberblock.client.npc;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.npc.HubNpc;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.Identifier;

/**
 * A hub NPC (megaplan task 7-N1) drawn like a Night City figure: the player body, classic or slim, in its {@code npc_role} skin (Phase
 * 5's NPC skins, e.g. {@code cyberblock:textures/entity/npc/bartender_f.png}); the neutral figure skin when it names none or the
 * texture is missing (logged once per skin). Its name tag is vanilla's custom name.
 */
public final class HubNpcRenderer extends LivingEntityRenderer<HubNpc, HubNpcRenderer.State, HumanoidModel<HubNpcRenderer.State>> {
	static final Identifier NEUTRAL = Identifier.fromNamespaceAndPath(Cyberblock.ID, "textures/entity/figure/neutral.png");
	private static final Map<Identifier, Boolean> present = new HashMap<>();
	private static final Set<Identifier> loggedMissing = new HashSet<>();
	private final HumanoidModel<State> wide, slim;
	private final Identifier neutral;

	/** A humanoid's render state plus the skin. */
	public static final class State extends HumanoidRenderState {
		Identifier texture = NEUTRAL;
		boolean slim;
	}

	HubNpcRenderer(final EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER), RenderTypes::entityTranslucent), 0.5F);
		this.wide = this.model;
		this.slim = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), RenderTypes::entityTranslucent);
		this.neutral = context.getResourceManager().getResource(NEUTRAL).isPresent() ? NEUTRAL : DefaultPlayerSkin.getDefaultTexture();
	}

	/** The skin texture for a skin id: its own when the resources have it, else the neutral skin. */
	private Identifier texture(final String skin) {
		Identifier id = skin.isEmpty() ? null : Identifier.tryParse(skin);
		if (id == null) {
			return this.neutral;
		}

		boolean found = present.computeIfAbsent(id, i -> Minecraft.getInstance().getResourceManager().getResource(i).isPresent());
		if (!found && loggedMissing.add(id)) {
			Cyberblock.LOG.warn("npc: hub NPC skin {} has no texture, the neutral skin instead", id);
		}

		return found ? id : this.neutral;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(final HubNpc entity, final State state, final float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTicks, this.itemModelResolver);
		state.texture = this.texture(entity.skin());
		state.slim = entity.slim();
	}

	@Override
	public void submit(final State state, final PoseStack poseStack, final SubmitNodeCollector collector, final CameraRenderState camera) {
		this.model = state.slim ? this.slim : this.wide;
		super.submit(state, poseStack, collector, camera);
	}

	@Override
	public Identifier getTextureLocation(final State state) {
		return state.texture;
	}
}
