package dev.cyberblock.client.ui;

import dev.cyberblock.Cyberblock;
import dev.cyberblock.client.HostState;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

/**
 * Wrappers around vanilla HUD elements (section 1.11). In a vehicle (HostState.vehicle) the hotbar, health, food, armour, air,
 * experience bar and level, held-item name and crosshair are hidden, while the hand still renders (the HUD itself stays on). While
 * a pass-through menu covers the screen every wrapped element returns early, so only MenuFrameHud's frame reaches the overlay.
 */
final class VehicleHud {
	/** Hidden in a vehicle. */
	private static final List<Identifier> VEHICLE = List.of(
		VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.ARMOR_BAR,
		VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
		VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.CROSSHAIR);
	/** Hidden only under a pass-through menu. */
	private static final List<Identifier> MENU_ONLY = List.of(
		VanillaHudElements.MISC_OVERLAYS, VanillaHudElements.SPECTATOR_MENU, VanillaHudElements.SPECTATOR_TOOLTIP, VanillaHudElements.MOB_EFFECTS,
		VanillaHudElements.BOSS_BAR, VanillaHudElements.SLEEP, VanillaHudElements.DEMO_TIMER, VanillaHudElements.SCOREBOARD,
		VanillaHudElements.OVERLAY_MESSAGE, VanillaHudElements.TITLE_AND_SUBTITLE, VanillaHudElements.CHAT, VanillaHudElements.PLAYER_LIST,
		VanillaHudElements.SUBTITLES);
	/**
	 * The cyber track's HUD elements (RamHud.ID, CyberwareHud.ID, ScanClient.HUD_ID, and the Sandevistan flourish when C7 has
	 * registered it): hidden under a pass-through menu too. One that is not registered is skipped.
	 */
	private static final List<Identifier> CYBER = List.of(
		Identifier.fromNamespaceAndPath(Cyberblock.ID, "ram_bar"), Identifier.fromNamespaceAndPath(Cyberblock.ID, "cyberware_bar"),
		Identifier.fromNamespaceAndPath(Cyberblock.ID, "scan_tooltip"), Identifier.fromNamespaceAndPath(Cyberblock.ID, "sandevistan"));

	private VehicleHud() {
	}

	static void register() {
		VEHICLE.forEach(id -> HudElementRegistry.replaceElement(id, element -> gate(element, true)));
		MENU_ONLY.forEach(id -> HudElementRegistry.replaceElement(id, element -> gate(element, false)));
		for (Identifier id : CYBER) {
			try {
				HudElementRegistry.replaceElement(id, element -> gate(element, false));
			} catch (IllegalArgumentException e) {
				Cyberblock.LOG.debug("ui: no HUD element {} to cover under pass-through menus", id);
			}
		}
	}

	private static HudElement gate(final HudElement element, final boolean hideInVehicle) {
		return (graphics, deltaTracker) -> {
			if (Cyberblock.active && (UiHud.covered() || hideInVehicle && HostState.vehicle())) {
				return;
			}

			element.extractRenderState(graphics, deltaTracker);
		};
	}
}
