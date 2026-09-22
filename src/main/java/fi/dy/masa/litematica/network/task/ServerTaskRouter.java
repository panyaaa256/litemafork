package fi.dy.masa.litematica.network.task;

import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.schematic.verifier.ServerVerifySession;

/**
 * Dispatches a server reply to whichever client side session it belongs to.
 * <p>
 * Every reply names itself in its {@code Task} string, and the suffix says what kind of
 * reply it is: {@code ...Status}, {@code ...Result} or {@code ...Error}. Routing on that
 * string alone - rather than on the packet type, which every kind shares - is what lets a
 * new kind of task be added without touching the packet layer.
 * <p>
 * A {@code Litematica...} task string this client does not know means the <i>server</i> is
 * newer than we are. It is logged and dropped, never guessed at - the same thing Servux does
 * with a request it does not know.
 */
public class ServerTaskRouter
{
	private static final String TASK_PREFIX = "Litematica";

	/**
	 * The session each task prefix belongs to. The suppliers are named rather than looked
	 * up among the created sessions on purpose: a reply can be the first thing that ever
	 * mentions a kind of task, and a class nothing has touched yet has no instance to find.
	 */
	private static final Map<String, Supplier<? extends ServerTaskSessionBase>> SESSIONS = Map.of(
			"LitematicaVerify", ServerVerifySession::getInstance,
			"LitematicaAnalyze", ServerAnalyzeSession::getInstance,
			"LitematicaMaterials", ServerMaterialListSession::getInstance);

	/** Handles {@code PACKET_S2C_TASK_RESPONSE}, which is how the server reports a failure. */
	public static void routeTaskResponse(CompoundData nbt)
	{
		String task = nbt.getStringOrDefault("Task", "");

		ServerTaskSessionBase session = sessionFor(task, "Error");

		if (session != null)
		{
			session.handleError(nbt);
		}
		else
		{
			Litematica.LOGGER.warn("ServerTaskRouter: ignoring an unknown task response '{}'; the server is likely newer than this Litematica", task);
		}
	}

	/**
	 * Handles {@code PACKET_S2C_TASK_STATUS_SYNC}: progress pings.
	 *
	 * @return false when the ping is not one of ours, i.e. it belongs to the shared info HUD
	 *         sync that the fill, delete and paste tasks use
	 */
	public static boolean routeStatus(CompoundData nbt)
	{
		String task = nbt.getStringOrDefault("Task", "");

		if (!task.startsWith(TASK_PREFIX))
		{
			return false;
		}

		ServerTaskSessionBase session = sessionFor(task, "Status");

		if (session != null)
		{
			session.handleStatus(nbt);
		}
		else
		{
			Litematica.debugLog("ServerTaskRouter: ignoring an unknown task status '{}'", task);
		}

		return true;
	}

	/**
	 * Handles a result batch, which arrives over the bulk (packet splitter) route.
	 *
	 * @return true when the payload was a task result and has been dealt with; false leaves
	 *         it to the caller's own bulk handling
	 */
	public static boolean routeBulkResult(String task, CompoundData nbt)
	{
		if (!task.startsWith(TASK_PREFIX))
		{
			return false;
		}

		ServerTaskSessionBase session = sessionFor(task, "Result");

		if (session != null)
		{
			session.handleResult(nbt);
		}
		else
		{
			Litematica.LOGGER.warn("ServerTaskRouter: ignoring an unknown task result '{}'; the server is likely newer than this Litematica", task);
		}

		return true;
	}

	/** Strips the role suffix off a {@code Task} string and finds the session for the rest. */
	@Nullable
	private static ServerTaskSessionBase sessionFor(String task, String suffix)
	{
		if (!task.endsWith(suffix))
		{
			return null;
		}

		Supplier<? extends ServerTaskSessionBase> session = SESSIONS.get(task.substring(0, task.length() - suffix.length()));

		return session != null ? session.get() : null;
	}
}
