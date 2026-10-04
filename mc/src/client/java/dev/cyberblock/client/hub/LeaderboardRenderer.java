package dev.cyberblock.client.hub;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.cyberblock.hub.HubBlocks;
import dev.cyberblock.hub.LeaderboardBlockEntity;
import dev.cyberblock.hub.Leaderboards;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A leaderboard board's face (megaplan 7-H1): the title and the top rows the server sent with the block entity, drawn full-bright on
 * the side the board faces, like a sign's text.
 */
final class LeaderboardRenderer implements BlockEntityRenderer<LeaderboardBlockEntity, LeaderboardRenderer.State> {
	/** Text pixels to blocks: a 90-pixel row is about one block wide. */
	private static final float SCALE = 0.0105F;
	private static final int TITLE = 0xFFFFD040;
	private static final int ROW = 0xFF40E0FF;
	private static final int LINE = 10;
	private final Font font;

	static final class State extends BlockEntityRenderState {
		Direction facing = Direction.NORTH;
		final List<FormattedCharSequence> lines = new ArrayList<>();
	}

	LeaderboardRenderer(final BlockEntityRendererProvider.Context context) {
		this.font = context.font();
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(final LeaderboardBlockEntity board, final State state, final float partialTick, final Vec3 camera,
		final ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
		BlockEntityRenderer.super.extractRenderState(board, state, partialTick, camera, breakProgress);
		state.facing = board.getBlockState().getValue(HubBlocks.FACING);
		state.lines.clear();
		state.lines.add(Leaderboards.title(board.objective(), board.titleKey()).getVisualOrderText());
		List<Leaderboards.Row> rows = board.rows();
		for (int i = 0; i < rows.size(); i++) {
			state.lines.add(Component.literal((i + 1) + ". " + rows.get(i).name() + "  " + rows.get(i).value()).getVisualOrderText());
		}

		if (rows.isEmpty()) {
			state.lines.add(Component.translatableWithFallback("cyberblock.hub.board.empty", "No scores yet").getVisualOrderText());
		}
	}

	@Override
	public void submit(final State state, final PoseStack pose, final SubmitNodeCollector collector, final CameraRenderState camera) {
		pose.pushPose();
		pose.translate(0.5F, 0.5F, 0.5F);
		pose.rotateDegrees(Axis.YP, -state.facing.toYRot());
		pose.translate(0.0F, 0.0F, 0.505F);
		pose.scale(SCALE, -SCALE, SCALE);
		float top = -state.lines.size() * LINE / 2.0F;
		for (int i = 0; i < state.lines.size(); i++) {
			FormattedCharSequence line = state.lines.get(i);
			collector.submitText(pose, -this.font.width(line) / 2.0F, top + i * LINE, line, false, Font.DisplayMode.POLYGON_OFFSET,
				LightCoordsUtil.FULL_BRIGHT, i == 0 ? TITLE : ROW, 0, 0);
		}

		pose.popPose();
	}
}
