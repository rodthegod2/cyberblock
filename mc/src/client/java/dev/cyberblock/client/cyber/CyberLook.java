package dev.cyberblock.client.cyber;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.cyberblock.Cyberblock;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Cyberware;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Steve's cyber look in third person (plan retest3 C7, F11), drawn by ArmCyberwareLayer from what the extract hook put in
 * the render state: A's skin-layout overlays for Kiroshi optics (glowing eyes), subdermal armour and reinforced tendons
 * (glowing lines); the optical camo as a translucent cyan shimmer instead of nothing; and the Sandevistan's afterimages.
 */
final class CyberLook {
	/** What the local player's avatar shows this frame. */
	record Look(boolean eyes, boolean subdermal, boolean tendons, boolean camo, List<Vec3> ghosts, float age) {
	}

	static final RenderStateDataKey<Look> LOOK = RenderStateDataKey.create(() -> "cyberblock:cyber_look");
	private static final Identifier EYES = texture("kiroshi_eyes");
	private static final Identifier SUBDERMAL = texture("subdermal_armor");
	private static final Identifier TENDONS = texture("reinforced_tendons");
	private static final int CAMO_CYAN = 0x33E6FF;
	private static final int[] GHOST_COLOURS = {0x33E6FF, 0xFF3DF2, 0x33E6FF};
	private static final int[] GHOST_ALPHA = {0x60, 0x40, 0x24};

	private CyberLook() {
	}

	private static Identifier texture(final String name) {
		return Identifier.fromNamespaceAndPath(Cyberblock.ID, "textures/entity/cyber/" + name + ".png");
	}

	/** The extract hook, local player only: the look, and with the camo running the body drawn see-through instead of hidden. */
	static void extract(final LocalPlayer player, final AvatarRenderState state) {
		Set<Cyberware> installed = CyberData.installed(player);
		long now = player.level().getGameTime();
		boolean camo = CyberData.timer(player, Cyberware.OPTICAL_CAMO).active(now) && state.isInvisible;
		if (camo) {
			// vanilla draws an invisible body it may see translucent: so Steve shimmers (section 1.8) where he used to vanish
			state.isInvisibleToPlayer = false;
		}

		List<Vec3> ghosts = SandevistanFx.running(player) ? SandevistanFx.ghostOffsets(new Vec3(state.x, state.y, state.z)) : List.of();
		state.setData(LOOK, new Look(installed.contains(Cyberware.KIROSHI_OPTICS), installed.contains(Cyberware.SUBDERMAL_ARMOR),
			installed.contains(Cyberware.REINFORCED_TENDONS), camo, ghosts, state.ageInTicks));
	}

	/** Every other avatar: no look. */
	static void clear(final AvatarRenderState state) {
		if (state.getData(LOOK) != null) {
			state.setData(LOOK, null);
		}
	}

	/** ArmCyberwareLayer: the overlays, the camo shimmer and the afterimages, on the model as posed for this frame. */
	static void submit(final PlayerModel model, final PoseStack poseStack, final SubmitNodeCollector collector, final int light,
		final AvatarRenderState state) {
		Look look = state.getData(LOOK);
		if (look == null || state.isSpectator) {
			return;
		}

		Identifier skin = state.skin.body().texturePath();
		if (look.camo()) {
			int alpha = 0x30 + Math.round(0x28 * (0.5F + 0.5F * (float) Math.sin(look.age() * 0.6F)));
			part(model, state, poseStack, collector, RenderTypes.entityTranslucent(skin), light, ARGB.color(alpha, CAMO_CYAN));
		} else if (!state.isInvisible) {
			if (look.subdermal()) {
				part(model, state, poseStack, collector, RenderTypes.entityTranslucent(SUBDERMAL), light, -1);
			}

			if (look.tendons()) {
				part(model, state, poseStack, collector, RenderTypes.entityTranslucentEmissive(TENDONS), light, -1);
			}

			if (look.eyes()) {
				part(model, state, poseStack, collector, RenderTypes.eyes(EYES), light, -1);
			}
		}

		for (int i = 0; i < look.ghosts().size(); i++) {
			poseStack.pushPose();
			Vec3 local = toModel(look.ghosts().get(i), state.bodyRot, state.scale * 0.9375F);
			poseStack.translate(local.x, local.y, local.z);
			part(model, state, poseStack, collector, RenderTypes.entityTranslucent(skin), light, ARGB.color(GHOST_ALPHA[i], GHOST_COLOURS[i]));
			poseStack.popPose();
		}
	}

	/**
	 * A world offset in the model's space as LivingEntityRenderer sets it up: turned by 180 - bodyRot about y, then scaled by
	 * (-1, -1, 1) and the player's scale (0.9375 x the entity scale).
	 */
	static Vec3 toModel(final Vec3 world, final float bodyRot, final float scale) {
		Vector3f v = new Vector3f((float) world.x, (float) world.y, (float) world.z).rotateY((float) -Math.toRadians(180.0F - bodyRot));
		return new Vec3(-v.x / scale, -v.y / scale, v.z / scale);
	}

	private static void part(final PlayerModel model, final AvatarRenderState state, final PoseStack poseStack, final SubmitNodeCollector collector,
		final RenderType type, final int light, final int colour) {
		collector.submitModel(model, state, poseStack, type, light, OverlayTexture.NO_OVERLAY, colour, null, state.outlineColor);
	}
}
