package dev.cyberblock.npc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cyberblock.Cyberblock;
import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;

/**
 * A player's fixer gigs, kept on the player (Fabric data attachments, saved with the world and through death): the gig taken (a
 * contract id, "" for none), its progress and when it was taken (game time, for {@code time_limit}); how many gigs the player has done;
 * and the gigs done that are not {@code repeatable} (a fixer no longer offers those to this player).
 */
public final class GigData {
	/** The taken gig. */
	public record Gig(String contract, int progress, long started) {
		public static final Gig NONE = new Gig("", 0, 0L);
		static final Codec<Gig> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("contract").forGetter(Gig::contract),
			Codec.INT.fieldOf("progress").forGetter(Gig::progress),
			Codec.LONG.optionalFieldOf("started", 0L).forGetter(Gig::started)
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
	/** Gigs done (the hub's "Gigs Done" board shows the cb_gigs scoreboard objective, raised with this). */
	public static final AttachmentType<Integer> DONE = AttachmentRegistry.<Integer>builder()
		.persistent(Codec.INT)
		.copyOnDeath()
		.initializer(() -> 0)
		.buildAndRegister(Identifier.fromNamespaceAndPath(Cyberblock.ID, "gigs_done"));
	/** Contract ids done that are not repeatable. */
	public static final AttachmentType<List<String>> FINISHED = AttachmentRegistry.<List<String>>builder()
		.persistent(Codec.STRING.listOf())
		.copyOnDeath()
		.initializer(List::of)
		.buildAndRegister(Identifier.fromNamespaceAndPath(Cyberblock.ID, "gigs_finished"));

	private GigData() {
	}

	/** Loads the class, which registers the attachment types (once, at startup). */
	static void register() {
	}
}
