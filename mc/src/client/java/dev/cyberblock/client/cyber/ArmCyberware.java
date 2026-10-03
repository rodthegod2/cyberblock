package dev.cyberblock.client.cyber;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.cyberblock.client.HostState;
import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Cyberware;
import dev.cyberblock.cyber.CyberwareRules;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import org.jspecify.annotations.Nullable;

/**
 * Arm cyberware drawn on Steve (plan retest3 section 1.8, F6): the installed Arms implant's 3D model (A's
 * `cyberblock:item/<id>_arm` and `_arm_out`, picked by the item definition's custom_model_data string) wraps his arm, in
 * third person through ArmCyberwareLayer and in first person through AvatarRendererCyberMixin. The model frame: the
 * shoulder pivot is model point (8, 16, 8) and the arm runs toward -y (y 16 shoulder, y 4 wrist), Steve's arm being the box
 * x 6..10, y 4..16, z 6..10. Hidden while Steve aims one of the host's guns and in vehicles.
 */
public final class ArmCyberware {
	/** The implants that have an arm model. */
	public static final List<Cyberware> ARMS = List.of(Cyberware.MANTIS_BLADES, Cyberware.GORILLA_ARMS, Cyberware.MONOWIRE,
		Cyberware.PROJECTILE_LAUNCH_SYSTEM);
	/** Out for this long after the implant fired or Steve swung an empty hand (ticks). */
	static final int DEPLOY_TICKS = 20;
	/** The first-person hand's item state (render thread; made on first use, so the pure rule loads without the renderer). */
	private static @Nullable ItemStackRenderState firstPerson;

	/** What to draw on the arm: the implant and its custom_model_data string ("arm", or "arm_out" while deployed). */
	public record Arm(Cyberware cyberware, String model) {
	}

	private ArmCyberware() {
	}

	/**
	 * Pure: the arm implant to draw, or null. The installed Arms implant with an arm model, not while the host's gun pose or a
	 * vehicle hides Steve's arms; deployed ("arm_out") only with an empty main hand.
	 */
	public static @Nullable Arm armStack(final Set<Cyberware> installed, final boolean mainHandEmpty, final boolean hostGun, final boolean vehicle,
		final boolean deployed) {
		if (hostGun || vehicle) {
			return null;
		}

		for (Cyberware c : ARMS) {
			if (installed.contains(c)) {
				return new Arm(c, deployed && mainHandEmpty ? "arm_out" : "arm");
			}
		}

		return null;
	}

	/** The item stack whose model is the arm model (CustomModelData strings index 0). */
	static ItemStack stack(final Arm arm) {
		ItemStack stack = new ItemStack(arm.cyberware().item());
		stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(arm.model()), List.of()));
		return stack;
	}

	/** Out: the implant fired within DEPLOY_TICKS (or its effect runs), or Steve swings an empty hand. */
	static boolean deployed(final LocalPlayer player, final @Nullable Cyberware cyberware) {
		if (player.isSwinging() && player.getMainHandItem().isEmpty()) {
			return true;
		}

		if (cyberware == null) {
			return false;
		}

		long now = player.level().getGameTime();
		CyberwareRules.Timer timer = CyberData.timer(player, cyberware);
		return timer.active(now) || timer.start() > 0L && now - timer.start() >= 0L && now - timer.start() < DEPLOY_TICKS;
	}

	/** The local player's arm right now, or null. */
	static @Nullable Arm current(final LocalPlayer player) {
		Set<Cyberware> installed = CyberData.installed(player);
		HostState.Pose pose = HostState.frame();
		Cyberware implant = ARMS.stream().filter(installed::contains).findFirst().orElse(null);
		return armStack(installed, player.getMainHandItem().isEmpty(), pose != null && pose.gun(), HostState.vehicle(),
			deployed(player, implant));
	}

	/** Resolves the local player's arm item into `out` (cleared when there is none). True when there is one. */
	static boolean resolve(final LocalPlayer player, final ItemStackRenderState out) {
		Arm arm = current(player);
		if (arm == null) {
			out.clear();
			return false;
		}

		Minecraft.getInstance().getItemModelResolver().updateForLiving(out, stack(arm), ItemDisplayContext.NONE, player);
		return !out.isEmpty();
	}

	/**
	 * Puts the item model's arm frame on an arm ModelPart already applied to `poseStack` (translateAndRotate): the model's
	 * shoulder (8, 16, 8) goes to the top centre of Steve's arm box (x -1 or +1, y -2 in model pixels), y flipped to point down
	 * the arm (a 180 degree turn about x, so nothing is mirrored).
	 */
	static void toArmFrame(final PoseStack poseStack, final HumanoidArm side) {
		poseStack.translate((side == HumanoidArm.RIGHT ? -1.0F : 1.0F) / 16.0F, -2.0F / 16.0F + 0.5F, 0.0F);
		poseStack.rotateDegrees(Axis.XP, 180.0F);
	}

	/** Submits the arm item on an arm part (in the part's parent space): third-person layer and first-person hand alike. */
	static void submit(final ItemStackRenderState item, final ModelPart arm, final HumanoidArm side, final PoseStack poseStack,
		final SubmitNodeCollector collector, final int light, final int outline) {
		poseStack.pushPose();
		arm.translateAndRotate(poseStack);
		toArmFrame(poseStack, side);
		item.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, outline);
		poseStack.popPose();
	}

	/** AvatarRenderer.extractRenderState's tail: the local player's arm item and cyber look into the render state, every other avatar none. */
	public static void extract(final Avatar entity, final AvatarRenderState state, final float partialTick) {
		ItemStackRenderState item = state.getData(ArmCyberwareLayer.ARM);
		if (!(entity instanceof LocalPlayer player)) {
			if (item != null) {
				item.clear();
			}

			CyberLook.clear(state);
			return;
		}

		CyberLook.extract(player, state);

		if (item == null) {
			item = new ItemStackRenderState();
			state.setData(ArmCyberwareLayer.ARM, item);
		}

		resolve(player, item);
	}

	/** AvatarRenderer.renderHand's tail (first person): the local player's arm implant on the hand being drawn. */
	public static void hand(final AvatarRenderer<?> renderer, final PoseStack poseStack, final SubmitNodeCollector collector, final int light,
		final ModelPart arm) {
		HumanoidArm side = arm == renderer.getModel().leftArm ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}

		if (firstPerson == null) {
			firstPerson = new ItemStackRenderState();
		}

		if (resolve(player, firstPerson)) {
			submit(firstPerson, arm, side, poseStack, collector, light, 0);
		}
	}
}
