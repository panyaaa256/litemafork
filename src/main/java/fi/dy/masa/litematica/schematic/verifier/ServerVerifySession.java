package fi.dy.masa.litematica.schematic.verifier;

import java.util.UUID;
import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.data.Constants;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.ListData;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.litematica.network.ServuxLitematicaHandler;
import fi.dy.masa.litematica.network.ServuxLitematicaPacket;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;

/**
 * Client half of a server side verification.
 * <p>
 * The server walks the placement without being limited by the client's render distance and
 * streams the mismatches back in batches; this class uploads the request, acknowledges each
 * batch to pull the next one, and hands the decoded entries to the {@link SchematicVerifier}
 * that asked for them.
 * <p>
 * Only one session exists at a time - the server enforces one per player anyway - so this
 * is a singleton keyed on the session id it generated for the current request.
 */
public class ServerVerifySession
{
	private static final ServerVerifySession INSTANCE = new ServerVerifySession();

	public static ServerVerifySession getInstance()
	{
		return INSTANCE;
	}

	@Nullable private UUID sessionId;
	@Nullable private SchematicVerifier verifier;

	private ServerVerifySession() {}

	public boolean isActive()
	{
		return this.sessionId != null;
	}

	/**
	 * Uploads the placement and asks the server to verify it.
	 *
	 * @return false if the server has not advertised the verify capability, or the request
	 *         could not be sent
	 */
	public boolean start(SchematicVerifier verifier, SchematicPlacement placement)
	{
		if (!EntityDataManager.getInstance().hasServuxFeature("verify"))
		{
			InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.message.error.verifier.no_server_support");
			return false;
		}

		this.cancel();

		this.sessionId = UUID.randomUUID();
		this.verifier = verifier;

		CompoundData nbt = placement.toData(true);
		nbt.putString("Task", "LitematicaVerify");
		nbt.putIntArray("SessionId", uuidToIntArray(this.sessionId));
		// Off unless asked for; the server's verify_nbt still decides whether it is allowed
		nbt.putBoolean("VerifyNbt", Configs.Generic.VERIFIER_CHECK_CONTENTS.getBooleanValue());

		Litematica.debugLog("ServerVerifySession: requesting verification of '{}' (session {})", placement.getName(), this.sessionId);

		// A request too large for the packet splitter is never sent, so no reply would ever
		// come; report that now rather than leave the verifier waiting on the server forever
		if (!ServuxLitematicaHandler.getInstance().encodeClientRequest(nbt))
		{
			this.clear();
			return false;
		}

		return true;
	}

	/** Tells the server to abandon the run, and forgets it locally. */
	public void cancel()
	{
		if (this.sessionId == null)
		{
			return;
		}

		CompoundData nbt = new CompoundData();
		nbt.putString("Task", "LitematicaVerifyCancel");
		nbt.putIntArray("SessionId", uuidToIntArray(this.sessionId));

		ServuxLitematicaHandler.getInstance().encodeClientData(ServuxLitematicaPacket.TaskCancel(nbt));

		this.clear();
	}

	private void clear()
	{
		this.sessionId = null;
		this.verifier = null;
	}

	/** True when a reply names the session we are actually waiting on. */
	private boolean matches(CompoundData nbt)
	{
		UUID id = uuidFromIntArray(nbt.getIntArray("SessionId"));

		return this.sessionId != null && this.sessionId.equals(id);
	}

	/** Progress ping while the server is still walking chunks. Returns true when it was ours. */
	public boolean handleStatus(CompoundData nbt)
	{
		if (!this.matches(nbt) || this.verifier == null)
		{
			return false;
		}

		this.verifier.onServerProgress(nbt.getInt("ChunksDone"),
		                               nbt.getInt("ChunksTotal"),
		                               nbt.getInt("Mismatches"));

		return true;
	}

	/** A failure the server reports as a translation key, so it renders in our language. */
	public void handleError(CompoundData nbt)
	{
		// An error can arrive before we know the session id is valid, so do not filter it
		String key = nbt.getString("Key");

		if (!key.isEmpty())
		{
			InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, Component.translatable(key).getString());
		}

		if (this.verifier != null)
		{
			this.verifier.onServerFailed();
		}

		this.clear();
	}

	/**
	 * Decodes one result batch and acknowledges it, which is what pulls the next one out
	 * of the server. The final batch also carries the run totals.
	 */
	public void handleResult(CompoundData nbt)
	{
		if (!this.matches(nbt) || this.verifier == null)
		{
			return;
		}

		int[] paletteIds = nbt.getIntArray("StatePalette");
		BlockState[] palette = new BlockState[paletteIds.length];

		for (int i = 0; i < paletteIds.length; i++)
		{
			// The global palette id, the same numbering vanilla chunk packets use, so this
			// resolves back to the canonical BlockState instance the verifier keys on
			palette[i] = Block.stateById(paletteIds[i]);
		}

		ListData entries = nbt.getList("Entries");

		for (int i = 0; entries != null && i < entries.size(); i++)
		{
			CompoundData entry = entries.getCompoundAt(i);

			if (entry == null)
			{
				continue;
			}

			// getInt() yields 0 for an absent key, so require the key to be present
			// rather than letting a malformed entry resolve to palette slot 0
			int expectedIndex = entry.contains("Expected", Constants.NBT.TAG_INT) ? entry.getInt("Expected") : -1;
			int foundIndex = entry.contains("Found", Constants.NBT.TAG_INT) ? entry.getInt("Found") : -1;

			if (expectedIndex < 0 || expectedIndex >= palette.length ||
				foundIndex < 0 || foundIndex >= palette.length)
			{
				continue;
			}

			this.verifier.addServerMismatch(entry.getString("Type"),
			                                palette[expectedIndex],
			                                palette[foundIndex],
			                                entry.getLongArray("Positions"));
		}

		// Both sides' container data for Wrong Contents positions, for as many as the server
		// was willing to send; these come after the positions they belong to
		ListData contents = nbt.getList("Contents");

		for (int i = 0; contents != null && i < contents.size(); i++)
		{
			CompoundData entry = contents.getCompoundAt(i);

			if (entry != null && entry.contains("Pos", Constants.NBT.TAG_LONG))
			{
				this.verifier.addServerContents(BlockPos.of(entry.getLong("Pos")),
				                                entry.getCompound("Expected"),
				                                entry.getCompound("Found"));
			}
		}

		final int batch = nbt.getInt("Batch");
		final boolean last = nbt.getBoolean("Final");

		if (last)
		{
			CompoundData totals = nbt.getCompound("Totals");
			this.verifier.onServerFinished(totals != null ? totals : new CompoundData());
		}

		// Acknowledge either way: the server frees the session on the final ack
		CompoundData ack = new CompoundData();
		ack.putString("Task", "LitematicaVerifyAck");
		ack.putIntArray("SessionId", uuidToIntArray(this.sessionId));
		ack.putInt("Batch", batch);

		ServuxLitematicaHandler.getInstance().encodeClientData(ServuxLitematicaPacket.TaskRequest(ack));

		if (last)
		{
			this.clear();
		}
	}

	public static int[] uuidToIntArray(UUID uuid)
	{
		long most = uuid.getMostSignificantBits();
		long least = uuid.getLeastSignificantBits();

		return new int[] {(int) (most >> 32), (int) most, (int) (least >> 32), (int) least};
	}

	@Nullable
	public static UUID uuidFromIntArray(@Nullable int[] array)
	{
		if (array == null || array.length != 4)
		{
			return null;
		}

		return new UUID((long) array[0] << 32 | (array[1] & 0xFFFFFFFFL),
		                (long) array[2] << 32 | (array[3] & 0xFFFFFFFFL));
	}
}
