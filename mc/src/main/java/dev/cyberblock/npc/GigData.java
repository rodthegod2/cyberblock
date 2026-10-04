package dev.cyberblock.npc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cyberblock.Cyberblock;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;

/**
 * A player's fixer gig, kept on the player (Fabric data attachments, saved with the world and through death): the contract id taken
 * ("" for none) and its progress, and how many gigs the player has done (the hub's "gigs done" board reads {@link #DONE}).
 */
public final class GigData {
	/** The taken gig: a contract content id and how far along it is. */
	public record Gig(String contract, int progress) {
		public static final Gig NONE = new Gig("", 0);
		static final Codec<Gig> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("contract").forGetter(Gig::contract),
			Codec.INT.fieldOf("progress").forGetter(Gig::progress)
		).apply(i, Gig::new));

		public boolean none() {
			return this.contract.isEmpty();
		}
	}

	public static final AttachmentType<Gig> GIG = AttachmentRegistry.<Gig>builder()
		.persistent(Gig.CODEC)
		.copyOnDeath()
		.initializer(() -> Gig.NONE)
		.buildAndRegister(Identifier.fromNamespaceAndPath(Cyberblock.ID, "gig"));
	public static final AttachmentType<Integer> DONE = AttachmentRegistry.<Integer>builder()
		.persistent(Codec.INT)
		.copyOnDeath()
		.initializer(() -> 0)
		.buildAndRegister(Identifier.fromNamespaceAndPath(Cyberblock.ID, "gigs_done"));

	private GigData() {
	}

	/** Loads the class, which registers the attachment types (once, at startup). */
	static void register() {
	}
}
