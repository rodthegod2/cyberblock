package dev.cyberblock.client.view;

import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Q-V4: the camera rows QV adds to the Cyberblock settings screen (QB's screen; added after its own widgets, two columns like
 * vanilla's options): distance, shoulder offset, FOV, vehicle camera distance, auto-recenter and the step-up assist. Every change
 * is saved and sent to the DLL as {@code prefs} (ViewClient).
 */
final class ViewSettingsRows {
	private static final int W = 150, H = 20, GAP = 4;
	/** FOV slider: step 0 is the game's own, steps 1..61 are 50..110 degrees. */
	private static final int FOV_STEPS = 61;
	/** Vehicle distance slider: step 0 is the game's own, steps 1..23 are 3..14 m by 0.5 m. */
	private static final int VDIST_STEPS = 23;
	/** Distance 1.5..8 m by 0.1 m; shoulder -1..1 m by 0.05 m. */
	private static final int DIST_STEPS = 65, SHOULDER_STEPS = 40;

	private ViewSettingsRows() {
	}

	static void add(final Screen screen) {
		List<AbstractWidget> widgets = Screens.getWidgets(screen);
		// below the screen's own rows, above its Done button at the bottom
		int top = 32;
		for (AbstractWidget w : widgets) {
			if (w.getY() < screen.height - 40) {
				top = Math.max(top, w.getBottom() + GAP * 2);
			}
		}

		int left = screen.width / 2 - W - GAP, right = screen.width / 2 + GAP;
		ViewPrefs p = ViewClient.prefs();
		widgets.add(new StepSlider(left, top, DIST_STEPS, (int) Math.round((p.dist() - ViewPrefs.DIST_MIN) * 10.0),
			i -> label("cyberblock.view.distance", "Camera distance", metres(ViewPrefs.DIST_MIN + i / 10.0)),
			i -> ViewClient.setPrefs(ViewClient.prefs().withDist(ViewPrefs.DIST_MIN + i / 10.0))));
		widgets.add(new StepSlider(right, top, SHOULDER_STEPS, (int) Math.round((p.shoulder() + 1.0) * 20.0),
			i -> label("cyberblock.view.shoulder", "Shoulder offset", metres(i / 20.0 - 1.0)),
			i -> ViewClient.setPrefs(ViewClient.prefs().withShoulder(i / 20.0 - 1.0))));
		int y = top + H + GAP;
		widgets.add(new StepSlider(left, y, FOV_STEPS, p.fov() == 0.0 ? 0 : (int) Math.round(p.fov() - ViewPrefs.FOV_MIN) + 1,
			i -> label("cyberblock.view.fov", "Third-person FOV", i == 0 ? game() : Component.literal((49 + i) + "°")),
			i -> ViewClient.setPrefs(ViewClient.prefs().withFov(i == 0 ? 0.0 : 49.0 + i))));
		widgets.add(new StepSlider(right, y, VDIST_STEPS, p.vdist() == 0.0 ? 0 : (int) Math.round((p.vdist() - ViewPrefs.VDIST_MIN) * 2.0) + 1,
			i -> label("cyberblock.view.vehicle_distance", "Vehicle camera distance", i == 0 ? game() : metres(2.5 + i / 2.0)),
			i -> ViewClient.setPrefs(ViewClient.prefs().withVdist(i == 0 ? 0.0 : 2.5 + i / 2.0))));
		y += H + GAP;
		widgets.add(CycleButton.onOffBuilder(p.recenter()).create(left, y, W, H, Component.translatableWithFallback("cyberblock.view.recenter", "Auto-recenter"),
			(button, on) -> ViewClient.setPrefs(ViewClient.prefs().withRecenter(on))));
		widgets.add(CycleButton.onOffBuilder(p.stepup()).create(right, y, W, H, Component.translatableWithFallback("cyberblock.stepup", "Step-up assist"),
			(button, on) -> ViewClient.setPrefs(ViewClient.prefs().withStepup(on))));
	}

	private static Component label(final String key, final String fallback, final Component value) {
		return CommonComponents.optionNameValue(Component.translatableWithFallback(key, fallback), value);
	}

	private static Component metres(final double m) {
		return Component.literal(String.format(Locale.ROOT, "%.2f m", m));
	}

	private static Component game() {
		return Component.translatableWithFallback("cyberblock.view.game_default", "Game");
	}

	/** A slider over steps 0..steps; the label and the change see the step. */
	private static final class StepSlider extends AbstractSliderButton {
		private final int steps;
		private final IntFunction<Component> label;
		private final IntConsumer change;

		StepSlider(final int x, final int y, final int steps, final int step, final IntFunction<Component> label, final IntConsumer change) {
			super(x, y, W, H, Component.empty(), Math.clamp(step, 0, steps) / (double) steps);
			this.steps = steps;
			this.label = label;
			this.change = change;
			this.updateMessage();
		}

		private int step() {
			return (int) Math.round(this.value * this.steps);
		}

		@Override
		protected void updateMessage() {
			this.setMessage(this.label.apply(this.step()));
		}

		@Override
		protected void applyValue() {
			this.change.accept(this.step());
		}
	}
}
