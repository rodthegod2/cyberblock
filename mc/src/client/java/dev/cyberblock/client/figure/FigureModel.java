package dev.cyberblock.client.figure;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * The player model posed as a Night City person (megaplan task 5-F1): vanilla's walk, run and crouch, then the host's poses on top:
 * aiming (both arms raised at the target), firing (the same with a recoil kick), combat (arms ready), a ragdoll's limp, splayed limbs.
 */
final class FigureModel extends PlayerModel {
	/** What a figure's render state carries beyond the player's. */
	static final class State extends AvatarRenderState {
		Identifier texture = FigureRules.NEUTRAL;
		boolean slim;
		boolean aim;
		boolean fire;
		boolean combat;
		/** Seconds since it went limp (0: not a ragdoll). */
		float ragdollSeconds;
	}

	FigureModel(final ModelPart root, final boolean slim) {
		super(root, slim);
	}

	@Override
	public void setupAnim(final AvatarRenderState state) {
		super.setupAnim(state);
		if (!(state instanceof State s)) {
			return;
		}

		if (s.ragdollSeconds > 0.0F) {
			// limp: arms flung out, legs apart, the head lolling, a little flop as it lands
			float flop = Mth.sin(s.ageInTicks * 0.9F) * Math.max(0.0F, 0.3F - s.ragdollSeconds * 0.2F);
			this.rightArm.xRot = -0.4F + flop;
			this.rightArm.zRot = 1.3F;
			this.leftArm.xRot = -0.2F - flop;
			this.leftArm.zRot = -1.2F;
			this.rightLeg.xRot = 0.15F;
			this.rightLeg.zRot = 0.35F;
			this.leftLeg.xRot = -0.1F;
			this.leftLeg.zRot = -0.3F;
			this.head.xRot = 0.5F + flop;
			this.head.zRot = 0.3F;
			return;
		}

		if (s.aim || s.fire) {
			// both hands on a gun at eye height, along the look
			float kick = s.fire ? FigureAnim.recoil(s.ageInTicks) : 0.0F;
			float pitch = this.head.xRot;
			this.rightArm.xRot = -Mth.HALF_PI + pitch - kick;
			this.rightArm.yRot = -0.1F + this.head.yRot;
			this.rightArm.zRot = 0.0F;
			this.leftArm.xRot = -Mth.HALF_PI + pitch - kick * 0.6F;
			this.leftArm.yRot = 0.45F + this.head.yRot;
			this.leftArm.zRot = 0.0F;
		} else if (s.combat) {
			// arms ready: forearms raised, a little out from the body
			this.rightArm.xRot = this.rightArm.xRot * 0.3F - 0.7F;
			this.leftArm.xRot = this.leftArm.xRot * 0.3F - 0.5F;
			this.rightArm.zRot = 0.15F;
			this.leftArm.zRot = -0.15F;
		}
	}

}
