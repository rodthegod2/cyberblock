package dev.cyberblock.client.npc;

import dev.cyberblock.ProxyEntities;
import dev.cyberblock.ProxyEntity;
import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.npc.HubNpcs;
import dev.cyberblock.npc.NpcWire;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.world.InteractionResult;

/** The client half of the NPC interaction layer (megaplan task 7-N1): the role and surgery screens, and the hub NPCs' renderer. */
public final class NpcClient {
	private NpcClient() {
	}

	/** From the client entrypoint, after the common init registered the hub NPC entity. */
	public static void init() {
		EntityRenderers.register(HubNpcs.TYPE, HubNpcRenderer::new);
		UiRoutes.listen(NpcWire.T_NPC, NpcScreen::onUi);
		UiRoutes.listen(NpcWire.T_SURGERY, SurgeryScreen::onUi);
		// a right-click on a Night City person with a role tag is its screen: consumed here, so the held item is not used as well (an
		// ender pearl thrown, food eaten, a bucket emptied) and the off hand is not tried. Fabric still sends the interact packet, and
		// the server decides whether a screen opens. Sneak to use the item on the person.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> level.isClientSide() && !player.isSpectator() && !player.isShiftKeyDown()
			&& entity instanceof ProxyEntity proxy && proxy.getType() == ProxyEntities.HUMAN && !proxy.dead() && proxy.label().startsWith("role:")
			? InteractionResult.SUCCESS : InteractionResult.PASS);
	}
}
