package dev.cyberblock.client.hack.screen;

import dev.cyberblock.client.ui.McTheme;
import dev.cyberblock.hack.actions.ActWire;
import dev.cyberblock.hack.actions.RemoteHack;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * The Vehicle Panel (megaplan task 6-I1): opened by right-clicking a car figure with the Cyberdeck Link (the server answers
 * `cyberblock:ui vpanel`) or from the Net Map. Five remote hacks on that car: lock its doors, kill the engine, force a brake, a
 * remote start, jam it. Each is an `rhack` request naming the figure; the server finds its owner and checks the toggles.
 */
final class VehiclePanelScreen extends ToolScreen {
	static final List<RemoteHack> HACKS = List.of(RemoteHack.VEH_LOCK, RemoteHack.VEH_ENGINE_OFF, RemoteHack.VEH_BRAKE, RemoteHack.VEH_START,
		RemoteHack.VEH_JAM);
	private final int eid;
	private final String name;

	VehiclePanelScreen(final int eid, final String name) {
		super(Component.translatableWithFallback("cyberblock.hack.vehicle.title", "Vehicle Panel"), 200, 20 + HACKS.size() * ROW + 40);
		this.eid = eid;
		this.name = name;
	}

	@Override
	protected void init() {
		for (int i = 0; i < HACKS.size(); i++) {
			RemoteHack hack = HACKS.get(i);
			this.row(i, hackName(hack), b -> this.sent(ActionsClient.send(ActWire.T_RHACK, ActWire.vehicle(hack, this.eid)), hack));
		}
	}

	@Override
	public void tick() {
		super.tick();
		Entity car = this.minecraft.level == null ? null : this.minecraft.level.getEntity(this.eid);
		if (car == null || car.isRemoved()) {
			this.status(Component.translatableWithFallback("cyberblock.hack.vehicle.gone", "The car is gone"), McTheme.RED);
		}
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		String car = this.name.isEmpty() ? Component.translatableWithFallback("cyberblock.hack.vehicle.car", "Car").getString() : this.name;
		graphics.text(this.font, this.font.plainSubstrByWidth(car, this.panelW() / 2), this.left() + this.panelW() - PAD - Math.min(this.font.width(car),
			this.panelW() / 2), this.top() + 7, McTheme.DARK_GRAY, false);
	}
}
