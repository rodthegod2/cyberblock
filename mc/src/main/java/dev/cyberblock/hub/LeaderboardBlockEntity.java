package dev.cyberblock.hub;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A leaderboard board's data: which scoreboard objective it shows, its title's lang key, and the top {@link Leaderboards#SHOWN} rows the
 * server last read. The server refreshes the rows every {@link #REFRESH_TICKS} and sends them to the clients only when they changed
 * (the block entity update packet); the client's renderer draws them on the board's face.
 */
public final class LeaderboardBlockEntity extends BlockEntity {
	static final int REFRESH_TICKS = 100;
	private String objective = Leaderboards.MATCH_WINS;
	private String titleKey = "";
	private List<Leaderboards.Row> rows = List.of();

	public LeaderboardBlockEntity(final BlockPos pos, final BlockState state) {
		super(HubBlocks.LEADERBOARD_ENTITY, pos, state);
	}

	public String objective() {
		return this.objective;
	}

	public String titleKey() {
		return this.titleKey;
	}

	public List<Leaderboards.Row> rows() {
		return this.rows;
	}

	/** The layout's board: what it shows (the objective is made when missing). */
	void configure(final String objective, final String titleKey) {
		this.objective = objective;
		this.titleKey = titleKey;
		if (this.level instanceof ServerLevel level) {
			Leaderboards.objective(level.getServer().getScoreboard(), objective, titleKey);
			this.refresh(level);
		}

		this.setChanged();
	}

	/** Server ticker: every few seconds the top rows again; clients hear of it only when they changed. */
	static void tick(final Level level, final BlockPos pos, final BlockState state, final LeaderboardBlockEntity board) {
		if (level instanceof ServerLevel server && (server.getGameTime() + pos.asLong()) % REFRESH_TICKS == 0) {
			board.refresh(server);
		}
	}

	private void refresh(final ServerLevel level) {
		ServerScoreboard scores = level.getServer().getScoreboard();
		var o = scores.getObjective(this.objective);
		List<Leaderboards.Row> now = o == null ? List.of() : Leaderboards.top(scores.listPlayerScores(o), Leaderboards.SHOWN);
		if (!now.equals(this.rows)) {
			this.rows = now;
			this.setChanged();
			level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected void saveAdditional(final ValueOutput output) {
		super.saveAdditional(output);
		output.putString("objective", this.objective);
		output.putString("title_key", this.titleKey);
		output.store("rows", Leaderboards.Row.CODEC.listOf(), this.rows);
	}

	@Override
	protected void loadAdditional(final ValueInput input) {
		super.loadAdditional(input);
		this.objective = input.getStringOr("objective", Leaderboards.MATCH_WINS);
		this.titleKey = input.getStringOr("title_key", "");
		this.rows = input.read("rows", Leaderboards.Row.CODEC.listOf()).orElse(List.of());
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(final HolderLookup.Provider registries) {
		return this.saveCustomOnly(registries);
	}
}
