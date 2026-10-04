package dev.cyberblock.hack.actions;

import dev.cyberblock.Cyberblock;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;

/**
 * The interaction map's items (megaplan task 6-I1): the Traffic Tool, the Net Map and the Comms Panel. Using one opens its screen
 * on the user's client (the server decides every action the screen then asks for). Names fall back to English until the lang
 * owner (6A) adds `item.cyberblock.&lt;id&gt;`; the art is 6A's too.
 */
public final class ActionItems {
	/** Which screen an item opens. */
	public enum Kind {
		TRAFFIC_TOOL("traffic_tool", "Traffic Tool"),
		NET_MAP("net_map", "Net Map"),
		COMMS_PANEL("comms_panel", "Comms Panel");

		private final String id;
		private final String english;

		Kind(final String id, final String english) {
			this.id = id;
			this.english = english;
		}

		/** The item id path, e.g. "net_map". */
		public String id() {
			return this.id;
		}

		public String english() {
			return this.english;
		}
	}

	/** Opens the item's screen on this client (the client entrypoint sets it; nothing on a dedicated server). */
	public static volatile Consumer<Kind> clientOpen = kind -> {
	};
	public static Item TRAFFIC_TOOL, NET_MAP, COMMS_PANEL;

	/** An item that opens its screen when used. */
	static final class ActionItem extends Item {
		private final Kind kind;

		ActionItem(final Kind kind, final Properties properties) {
			super(properties);
			this.kind = kind;
		}

		public Kind kind() {
			return this.kind;
		}

		@Override
		public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
			if (level.isClientSide()) {
				clientOpen.accept(this.kind);
			}

			return InteractionResult.SUCCESS;
		}
	}

	private ActionItems() {
	}

	/** Common init (both sides): the three items, also in the Cyberblock creative tab. */
	static void register() {
		TRAFFIC_TOOL = register(Kind.TRAFFIC_TOOL);
		NET_MAP = register(Kind.NET_MAP);
		COMMS_PANEL = register(Kind.COMMS_PANEL);
		ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(Cyberblock.ID, "cyberblock"));
		CreativeModeTabEvents.modifyOutputEvent(tab).register(output -> {
			output.accept(TRAFFIC_TOOL);
			output.accept(NET_MAP);
			output.accept(COMMS_PANEL);
		});
	}

	private static Item register(final Kind kind) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Cyberblock.ID, kind.id()));
		Item.Properties properties = new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)
			.component(DataComponents.ITEM_NAME, Component.translatableWithFallback("item.cyberblock." + kind.id(), kind.english()));
		return Registry.register(BuiltInRegistries.ITEM, key, new ActionItem(kind, properties.setId(key)));
	}
}
