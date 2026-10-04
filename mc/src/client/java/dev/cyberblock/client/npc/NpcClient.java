package dev.cyberblock.client.npc;

import dev.cyberblock.client.net.UiRoutes;
import dev.cyberblock.npc.HubNpcs;
import dev.cyberblock.npc.NpcWire;
import net.minecraft.client.renderer.entity.EntityRenderers;

/** The client half of the NPC interaction layer (megaplan task 7-N1): the role and surgery screens, and the hub NPCs' renderer. */
public final class NpcClient {
	private NpcClient() {
	}

	/** From the client entrypoint, after the common init registered the hub NPC entity. */
	public static void init() {
		EntityRenderers.register(HubNpcs.TYPE, HubNpcRenderer::new);
		UiRoutes.listen(NpcWire.T_NPC, NpcScreen::onUi);
		UiRoutes.listen(NpcWire.T_SURGERY, SurgeryScreen::onUi);
	}
}
