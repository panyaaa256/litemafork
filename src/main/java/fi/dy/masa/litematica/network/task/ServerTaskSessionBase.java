package fi.dy.masa.litematica.network.task;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

import net.minecraft.network.chat.Component;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.Constants;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.litematica.data.EntityDataManager;
import fi.dy.masa.litematica.network.ServuxLitematicaHandler;
import fi.dy.masa.litematica.network.ServuxLitematicaPacket;
import fi.dy.masa.litematica.render.infohud.IInfoHudRenderer;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.render.infohud.RenderPhase;

/**
 * The client half of one server side task, minus whatever the task actually is.
 * <p>
 * Every kind of server side task speaks the same shape: the client mints a session id and
 * uploads a request under {@code <prefix>}, the server replies with {@code <prefix>Status}
 * pings, {@code <prefix>Result} batches and {@code <prefix>Error}, the client acknowledges
 * each batch with {@code <prefix>Ack} to pull the next one, and it can send
 * {@code <prefix>Cancel} at any point. Only one session of a kind is in flight at a time -
 * the server enforces one per player per kind anyway - so each subclass is a singleton.
 * <p>
 * The capability check in {@link #begin} is what keeps this working against a Servux that
 * predates the feature: the server advertises what it can do in its metadata, and a client
 * that asked anyway would simply be ignored, so it does not ask.
 * <p>
 * A session also shows its progress in the info HUD while it runs. The work happens on the
 * server, so without that there would be nothing at all to look at between asking and the
 * result landing.
 */
public abstract class ServerTaskSessionBase implements IInfoHudRenderer
{
	/**
	 * Every session instance, so that a disconnect can forget all of them without having to
	 * know which kinds exist. The subclasses are singletons, so this never grows past one
	 * entry per kind.
	 */
	private static final List<ServerTaskSessionBase> INSTANCES = new ArrayList<>();

	private final List<String> infoHudLines = new ArrayList<>();

	@Nullable protected UUID sessionId;

	private int chunksDone;
	private int chunksTotal;

	protected ServerTaskSessionBase()
	{
		INSTANCES.add(this);
	}

	/**
	 * The connection is gone. Forgets every run without trying to tell the server, so that
	 * a stale session cannot outlive the server that was running it.
	 */
	public static void clearAll()
	{
		for (ServerTaskSessionBase session : INSTANCES)
		{
			session.clear();
		}
	}

	/** The {@code Task} string prefix this session speaks, e.g. {@code LitematicaAnalyze}. */
	protected abstract String taskPrefix();

	/** The {@code Features} capability the server has to advertise, e.g. {@code analyze}. */
	protected abstract String featureName();

	/** The translation key of the name this task goes by on the HUD. */
	protected abstract String displayNameKey();

	public boolean isActive()
	{
		return this.sessionId != null;
	}

	public int getChunksDone()
	{
		return this.chunksDone;
	}

	public int getChunksTotal()
	{
		return this.chunksTotal;
	}

	/** True when the server has advertised this kind of task. */
	public boolean isSupportedByServer()
	{
		return EntityDataManager.getInstance().hasServuxFeature(this.featureName());
	}

	/**
	 * Abandons any session in flight and mints a fresh id for a new one.
	 *
	 * @return false if the server has not advertised this capability, in which case the
	 *         caller should fall back to doing the work locally
	 */
	protected boolean begin()
	{
		if (!this.isSupportedByServer())
		{
			return false;
		}

		this.cancel();

		this.sessionId = UUID.randomUUID();
		this.chunksDone = 0;
		this.chunksTotal = 0;

		return true;
	}

	/**
	 * Uploads a request payload, stamping in the task string and session id, and starts
	 * showing the session in the info HUD.
	 *
	 * @return false if the request could not be sent (it was too large for the packet
	 *         splitter, for one); the session has then already been forgotten
	 */
	protected boolean send(CompoundData nbt)
	{
		if (this.sessionId == null)
		{
			return false;
		}

		nbt.putString("Task", this.taskPrefix());
		nbt.putIntArray("SessionId", uuidToIntArray(this.sessionId));

		if (!ServuxLitematicaHandler.getInstance().encodeClientRequest(nbt))
		{
			this.clear();
			return false;
		}

		this.updateInfoHudLines();
		InfoHud.getInstance().addInfoHudRenderer(this, true);

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
		nbt.putString("Task", this.taskPrefix() + "Cancel");
		nbt.putIntArray("SessionId", uuidToIntArray(this.sessionId));

		ServuxLitematicaHandler.getInstance().encodeClientData(ServuxLitematicaPacket.TaskCancel(nbt));

		this.clear();
	}

	protected void clear()
	{
		this.sessionId = null;
		this.chunksDone = 0;
		this.chunksTotal = 0;

		this.infoHudLines.clear();
		InfoHud.getInstance().removeInfoHudRenderer(this, false);
	}

	/** True when a reply names the session we are actually waiting on. */
	protected boolean matches(CompoundData nbt)
	{
		UUID id = uuidFromIntArray(nbt.getIntArray("SessionId"));

		return this.sessionId != null && this.sessionId.equals(id);
	}

	/** Acknowledges a result batch, which is what pulls the next one out of the server. */
	protected void acknowledge(int batch)
	{
		if (this.sessionId == null)
		{
			return;
		}

		CompoundData ack = new CompoundData();
		ack.putString("Task", this.taskPrefix() + "Ack");
		ack.putIntArray("SessionId", uuidToIntArray(this.sessionId));
		ack.putInt("Batch", batch);

		ServuxLitematicaHandler.getInstance().encodeClientData(ServuxLitematicaPacket.TaskRequest(ack));
	}

	/** Progress ping while the server is still walking chunks. */
	public void handleStatus(CompoundData nbt)
	{
		if (this.matches(nbt))
		{
			this.chunksDone = nbt.getInt("ChunksDone");
			this.chunksTotal = nbt.getInt("ChunksTotal");

			this.onStatus(nbt);
			this.updateInfoHudLines();
		}
	}

	/** A failure the server reports as a translation key, so it renders in our language. */
	public void handleError(CompoundData nbt)
	{
		// The server can only fail a run it has heard of. An error naming some other session
		// is a leftover from a run we already abandoned, and must not end the current one
		if (this.sessionId == null ||
			(nbt.contains("SessionId", Constants.NBT.TAG_INT_ARRAY) && !this.matches(nbt)))
		{
			return;
		}

		String key = nbt.getString("Key");

		if (!key.isEmpty())
		{
			InfoUtils.showGuiOrInGameMessage(MessageType.ERROR, Component.translatable(key).getString());
		}

		this.onFailed();
		this.clear();
	}

	/** One result batch. */
	public abstract void handleResult(CompoundData nbt);

	/** Tells whatever was waiting on this run that it is not coming. */
	protected void onFailed()
	{
	}

	/** Reads whatever extra fields this kind of task puts in its status pings. */
	protected void onStatus(CompoundData nbt)
	{
	}

	/** Adds whatever extra lines this kind of task shows in the info HUD. */
	protected void addExtraInfoHudLines(List<String> lines)
	{
	}

	/**
	 * Warns that the result leaves out the chunks the server could not read, which would
	 * otherwise look like a complete answer.
	 */
	protected void warnAboutSkippedChunks(@Nullable CompoundData totals)
	{
		if (totals == null)
		{
			return;
		}

		final int unloaded = totals.getInt("UnloadedChunks");
		final int ungenerated = totals.getInt("UngeneratedChunks");

		if (unloaded + ungenerated > 0)
		{
			InfoUtils.showGuiOrInGameMessage(MessageType.WARNING, "litematica.message.warn.server_task.skipped_chunks",
			                                 unloaded + ungenerated, unloaded, ungenerated);
		}
	}

	protected void updateInfoHudLines()
	{
		String green = GuiBase.TXT_GREEN;
		String rst = GuiBase.TXT_RST;

		this.infoHudLines.clear();
		this.infoHudLines.add(String.format("%s%s%s", GuiBase.TXT_BOLD,
		                                    StringUtils.translate("litematica.hud.server_task.title",
		                                                          StringUtils.translate(this.displayNameKey())), rst));
		this.infoHudLines.add(StringUtils.translate("litematica.hud.server_task.chunks",
		                                            green + this.chunksDone + rst,
		                                            green + this.chunksTotal + rst));

		this.addExtraInfoHudLines(this.infoHudLines);
	}

	@Override
	public boolean getShouldRenderText(RenderPhase phase)
	{
		return phase == RenderPhase.POST && this.isActive();
	}

	@Override
	public boolean shouldRenderInGuis()
	{
		// Both the analyzer and the material list are waited on from a GUI that stays open,
		// so hiding this behind it would hide it exactly when it is being watched
		return true;
	}

	@Override
	public List<String> getText(RenderPhase phase)
	{
		return this.infoHudLines;
	}

	/** A session id travels as four ints, the way vanilla stores a UUID in NBT. */
	private static int[] uuidToIntArray(UUID uuid)
	{
		long most = uuid.getMostSignificantBits();
		long least = uuid.getLeastSignificantBits();

		return new int[] {(int) (most >> 32), (int) most, (int) (least >> 32), (int) least};
	}

	@Nullable
	private static UUID uuidFromIntArray(@Nullable int[] array)
	{
		if (array == null || array.length != 4)
		{
			return null;
		}

		return new UUID((long) array[0] << 32 | (array[1] & 0xFFFFFFFFL),
		                (long) array[2] << 32 | (array[3] & 0xFFFFFFFFL));
	}
}
