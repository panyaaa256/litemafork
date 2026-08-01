package fi.dy.masa.litematica.schematic.verifier;

import java.util.UUID;
import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.data.EntitiesDataStorage;
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
	 * @return false if the server has not advertised the verify capability
	 */
	public boolean start(SchematicVerifier verifier, SchematicPlacement placement)
	{
		if (!EntitiesDataStorage.getInstance().hasServuxFeature("verify"))
		{
			InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.message.error.verifier.no_server_support");
			return false;
		}

		this.cancel();

		this.sessionId = UUID.randomUUID();
		this.verifier = verifier;

		CompoundTag nbt = placement.toNbt(true);
		nbt.putString("Task", "LitematicaVerify");
		nbt.putIntArray("SessionId", uuidToIntArray(this.sessionId));

		Litematica.debugLog("ServerVerifySession: requesting verification of '{}' (session {})", placement.getName(), this.sessionId);

		ServuxLitematicaHandler.getInstance().encodeClientData(ServuxLitematicaPacket.ResponseC2SStart(nbt));

		return true;
	}

	/** Tells the server to abandon the run, and forgets it locally. */
	public void cancel()
	{
		if (this.sessionId == null)
		{
			return;
		}

		CompoundTag nbt = new CompoundTag();
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
	private boolean matches(CompoundTag nbt)
	{
		UUID id = uuidFromIntArray(nbt.getIntArray("SessionId").orElse(null));

		return this.sessionId != null && this.sessionId.equals(id);
	}

	/** Progress ping while the server is still walking chunks. Returns true when it was ours. */
	public boolean handleStatus(CompoundTag nbt)
	{
		if (!this.matches(nbt) || this.verifier == null)
		{
			return false;
		}

		this.verifier.onServerProgress(nbt.getIntOr("ChunksDone", 0),
		                               nbt.getIntOr("ChunksTotal", 0),
		                               nbt.getIntOr("Mismatches", 0));

		return true;
	}

	/** A failure the server reports as a translation key, so it renders in our language. */
	public void handleError(CompoundTag nbt)
	{
		// An error can arrive before we know the session id is valid, so do not filter it
		String key = nbt.getStringOr("Key", "");

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
	public void handleResult(CompoundTag nbt)
	{
		if (!this.matches(nbt) || this.verifier == null)
		{
			return;
		}

		int[] paletteIds = nbt.getIntArray("StatePalette").orElse(new int[0]);
		BlockState[] palette = new BlockState[paletteIds.length];

		for (int i = 0; i < paletteIds.length; i++)
		{
			// The global palette id, the same numbering vanilla chunk packets use, so this
			// resolves back to the canonical BlockState instance the verifier keys on
			palette[i] = Block.stateById(paletteIds[i]);
		}

		ListTag entries = nbt.getListOrEmpty("Entries");

		for (int i = 0; i < entries.size(); i++)
		{
			CompoundTag entry = entries.getCompound(i).orElse(null);

			if (entry == null)
			{
				continue;
			}

			int expectedIndex = entry.getIntOr("Expected", -1);
			int foundIndex = entry.getIntOr("Found", -1);

			if (expectedIndex < 0 || expectedIndex >= palette.length ||
				foundIndex < 0 || foundIndex >= palette.length)
			{
				continue;
			}

			this.verifier.addServerMismatch(entry.getStringOr("Type", ""),
			                                palette[expectedIndex],
			                                palette[foundIndex],
			                                entry.getLongArray("Positions").orElse(new long[0]));
		}

		final int batch = nbt.getIntOr("Batch", 0);
		final boolean last = nbt.getBooleanOr("Final", false);

		if (last)
		{
			this.verifier.onServerFinished(nbt.getCompound("Totals").orElseGet(CompoundTag::new));
		}

		// Acknowledge either way: the server frees the session on the final ack
		CompoundTag ack = new CompoundTag();
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
