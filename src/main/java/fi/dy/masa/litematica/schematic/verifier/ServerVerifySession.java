package fi.dy.masa.litematica.schematic.verifier;

import java.util.List;
import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.Constants;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.ListData;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.litematica.network.task.ServerTaskSessionBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;

/**
 * Client half of a server side verification.
 * <p>
 * The server walks the placement without being limited by the client's render distance and
 * streams the mismatches back in batches; this class uploads the request and hands the
 * decoded entries to the {@link SchematicVerifier} that asked for them. Everything else -
 * the session id, the acknowledgement that pulls the next batch, cancelling, and the info
 * HUD lines - is the task protocol every server side task speaks, and comes from
 * {@link ServerTaskSessionBase}.
 */
public class ServerVerifySession extends ServerTaskSessionBase
{
	private static final ServerVerifySession INSTANCE = new ServerVerifySession();

	public static ServerVerifySession getInstance()
	{
		return INSTANCE;
	}

	@Nullable private SchematicVerifier verifier;

	private ServerVerifySession() {}

	@Override
	protected String taskPrefix()
	{
		return "LitematicaVerify";
	}

	@Override
	protected String featureName()
	{
		return "verify";
	}

	@Override
	protected String displayNameKey()
	{
		return "litematica.gui.label.task_name.verifier";
	}

	/**
	 * Uploads the placement and asks the server to verify it.
	 *
	 * @return false if the server has not advertised the verify capability, or the request
	 *         could not be sent
	 */
	public boolean start(SchematicVerifier verifier, SchematicPlacement placement)
	{
		// Unlike the other tasks this one has no local fallback to quietly drop back to:
		// the player pressed the server side button, so say why nothing happens
		if (this.isSupportedByServer() == false)
		{
			InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, "litematica.message.error.verifier.no_server_support");
			return false;
		}

		if (this.begin() == false)
		{
			return false;
		}

		this.verifier = verifier;

		CompoundData nbt = placement.toData(true);
		// Off unless asked for; the server's verify_nbt still decides whether it is allowed
		nbt.putBoolean("VerifyNbt", Configs.Generic.VERIFIER_CHECK_CONTENTS.getBooleanValue());
		// The same rules and tolerance as a local run, so that both find the same entities missing
		nbt.putBoolean("VerifyEntities", isCheckingEntities());
		nbt.putDouble("EntityTolerance", Configs.Generic.VERIFIER_ENTITY_TOLERANCE.getDoubleValue());

		Litematica.debugLog("ServerVerifySession: requesting verification of '{}' (session {})", placement.getName(), this.sessionId);

		return this.send(nbt);
	}

	/**
	 * Whether a server run checks entities: when they are checked locally too, and the server
	 * is new enough to. An older Servux has no entity check, and would ignore the request.
	 */
	public static boolean isCheckingEntities()
	{
		return Configs.Generic.VERIFIER_CHECK_ENTITIES.getBooleanValue() &&
		       EntityDataManager.getInstance().hasServuxFeature("verify_entities");
	}

	@Override
	protected void clear()
	{
		super.clear();

		this.verifier = null;
	}

	/** The verifier keeps the counts too: its GUI shows them as a status line. */
	@Override
	protected void onStatus(CompoundData nbt)
	{
		if (this.verifier != null)
		{
			this.verifier.onServerProgress(this.getChunksDone(), this.getChunksTotal(), nbt.getInt("Mismatches"));
		}
	}

	/** The chunks the server has not read yet, in place of the local pending chunk list. */
	@Override
	protected void addExtraInfoHudLines(List<String> lines)
	{
		final int unseen = this.verifier != null ? this.verifier.getUnseenChunks() : 0;
		String color = unseen > 0 ? GuiBase.TXT_GOLD : GuiBase.TXT_GREEN;

		lines.add(StringUtils.translate("litematica.hud.server_task.unseen", color + unseen + GuiBase.TXT_RST));
	}

	@Override
	protected void onFailed()
	{
		if (this.verifier != null)
		{
			this.verifier.onServerFailed();
		}
	}

	/**
	 * Decodes one result batch and acknowledges it, which is what pulls the next one out
	 * of the server. The final batch also carries the run totals.
	 */
	@Override
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

		// The schematic's entities that are not in the world; these come after every block pair
		ListData entities = nbt.getList("Entities");

		for (int i = 0; entities != null && i < entities.size(); i++)
		{
			CompoundData entry = entities.getCompoundAt(i);

			if (entry != null && entry.contains("Type", Constants.NBT.TAG_STRING))
			{
				this.verifier.addServerMissingEntity(entry.getString("Type"),
				                                     new Vec3(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z")));
			}
		}

		final boolean last = nbt.getBoolean("Final");

		if (last)
		{
			CompoundData totals = nbt.getCompound("Totals");
			this.verifier.onServerFinished(totals != null ? totals : new CompoundData());
		}

		// Acknowledge either way: the server frees the session on the final ack
		this.acknowledge(nbt.getInt("Batch"));

		if (last)
		{
			this.clear();
		}
	}
}
