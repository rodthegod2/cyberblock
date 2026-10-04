package dev.cyberblock.client.view;

import dev.cyberblock.client.Flight;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.phys.Vec3;

/**
 * Q-V2: third-person Steve's pose without flicker while a host drives him (AvatarRendererMixin, render thread, local player
 * only).
 * <ul>
 * <li>Gliding is Flight's own state, not the synced fall-flying flag: the pose starts on Flight's start tick and ends on its
 * landing tick (the only real starts and ends of a glide), and the body's tilt into the glide counts from that start, so a
 * flag blip from the server never flashes the standing pose or restarts the tilt.</li>
 * <li>The elytra stay open for the whole glide and the whole launch, from the first frame (the synced wings ease open over
 * several ticks and close on every flag blip).</li>
 * <li>Using an item (the shield's block pose, a bow, food) changes only once the raw state has held for 2 ticks; until then
 * the previous frame's using state and arm poses are drawn.</li>
 * </ul>
 */
public final class ViewPoses {
	/** Vanilla's open-wing angles (ElytraAnimationState while fall flying, not diving): x 20 degrees, z -90 degrees. */
	private static final float OPEN_X = 0.34906584F, OPEN_Z = -1.5707964F;
	/** Vanilla's folded-wing angles, the other end of the dive lerp (ElytraAnimationState). */
	private static final float FOLDED_X = 0.2617994F, FOLDED_Z = -0.2617994F;

	private static final PoseDebounce USING = new PoseDebounce();
	private static boolean usingShown;
	private static InteractionHand hand = InteractionHand.MAIN_HAND;
	private static float ticksUsing;
	private static HumanoidModel.ArmPose left = HumanoidModel.ArmPose.EMPTY, right = HumanoidModel.ArmPose.EMPTY;

	private ViewPoses() {
	}

	public static void apply(final Avatar entity, final AvatarRenderState state) {
		boolean glide = Flight.gliding();
		state.isFallFlying = glide;
		state.fallFlyingTimeInTicks = Flight.glideTicks();
		if (Flight.driving()) {
			// a glide dives with vanilla's wing angles; a launch keeps them spread however it tumbles
			Vec3 v = entity.getDeltaMovement();
			float open = glide ? wingOpening(v.y, v.length()) : 1.0F;
			state.elytraRotX = FOLDED_X + (OPEN_X - FOLDED_X) * open;
			state.elytraRotY = 0.0F;
			state.elytraRotZ = FOLDED_Z + (OPEN_Z - FOLDED_Z) * open;
		}

		boolean shown = USING.update(state.isUsingItem, entity.tickCount);
		if (shown == state.isUsingItem) {
			usingShown = shown;
			hand = state.useItemHand;
			ticksUsing = state.ticksUsingItem;
			left = state.leftArmPose;
			right = state.rightArmPose;
		} else {
			// the raw state changed less than 2 ticks ago: the last steady pose stays
			state.isUsingItem = usingShown;
			state.useItemHand = hand;
			state.ticksUsingItem = ticksUsing;
			state.leftArmPose = left;
			state.rightArmPose = right;
		}
	}

	/** No host: nothing is debounced, the next host frame starts from vanilla's state. */
	public static void reset(final AvatarRenderState state) {
		USING.force(state.isUsingItem);
	}

	/**
	 * How far open the wings are, 0..1, as vanilla's ElytraAnimationState has them while fall flying: fully open unless diving,
	 * closing as the motion turns straight down (1 - (-dy/|v|)^1.5).
	 */
	static float wingOpening(final double vy, final double speed) {
		if (!(vy < 0.0) || !(speed > 0.0)) {
			return 1.0F;
		}

		return (float) (1.0 - Math.pow(Math.min(1.0, -vy / speed), 1.5));
	}
}
