package dev.cyberblock.client.cyber;

import dev.cyberblock.cyber.CyberData;
import dev.cyberblock.cyber.Perk;
import dev.cyberblock.cyber.QuickhackItem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/** Layer 6 (plan 2026-10-03, track C; plan retest2 package C), client half: keys, the RAM bar, scanning, the skill tree, cyberware. */
public class CyberClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		QuickhackItem.readerHasCheapHacks = () -> {
			LocalPlayer player = Minecraft.getInstance().player;
			return player != null && CyberData.has(player, Perk.CHEAP_HACKS);
		};
		CyberKeys.register();
		CyberLink.register();
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, RamHud.ID, RamHud::draw);
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, CyberwareHud.ID, CyberwareHud::draw);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ScanClient.HUD_ID, ScanClient::draw);
		HudElementRegistry.addLast(SandevistanFx.HUD_ID, SandevistanFx::draw);
		ClientTickEvents.END_CLIENT_TICK.register(CyberClient::tick);
		// Steve's arm cyberware in third person (plan retest3 C6): every avatar renderer gets the layer, which draws only the local player's
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
			if (renderer instanceof AvatarRenderer<?> avatar) {
				helper.register(new ArmCyberwareLayer(avatarParent(avatar)));
			}
		});
	}

	@SuppressWarnings("unchecked")
	private static RenderLayerParent<AvatarRenderState, PlayerModel> avatarParent(final AvatarRenderer<?> renderer) {
		return (RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) renderer;
	}

	/** Every client tick (inside vanilla's per-tick gizmo collection, which the scan and Kiroshi outlines use). */
	private static void tick(final Minecraft minecraft) {
		CyberKeys.tick(minecraft);
		HackWheel.tick(minecraft);
		ScanClient.tick(minecraft);
		CyberwareClient.tick(minecraft);
		SandevistanFx.tick(minecraft);
	}
}
