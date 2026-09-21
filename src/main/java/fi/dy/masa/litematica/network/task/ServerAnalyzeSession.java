package fi.dy.masa.litematica.network.task;

import java.util.List;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.malilib.util.ItemType;
import fi.dy.masa.malilib.util.LayerRange;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.litematica.util.InclusionType;

/**
 * Client half of a server side area analysis.
 * <p>
 * The server walks the area without being limited by the render distance, and - the reason
 * this exists at all - can read the contents of containers. A client is never told what is
 * inside a chest it has not opened, so the local analyzer reports every container on a
 * multiplayer server as empty; this one does not.
 * <p>
 * The tallies are accumulated across batches and handed to the material list only once the
 * final batch lands, so the GUI never shows a half-built list.
 */
public class ServerAnalyzeSession extends ServerTaskSessionBase
{
	private static final ServerAnalyzeSession INSTANCE = new ServerAnalyzeSession();

	public static ServerAnalyzeSession getInstance()
	{
		return INSTANCE;
	}

	private final Object2IntOpenHashMap<BlockState> blockCounts = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<ItemType> entityCounts = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<ItemType> containerCounts = new Object2IntOpenHashMap<>();

	@Nullable private MaterialListBase materialList;
	private InclusionType entitiesInclusionType = InclusionType.NONE;
	private InclusionType containersInclusionType = InclusionType.NONE;

	private ServerAnalyzeSession() {}

	@Override
	protected String taskPrefix()
	{
		return "LitematicaAnalyze";
	}

	@Override
	protected String featureName()
	{
		return "analyze";
	}

	@Override
	protected String displayNameKey()
	{
		return "litematica.gui.label.task_name.area_analyzer";
	}

	/** True while the given list is the one waiting on a server result. */
	public boolean isActiveFor(MaterialListBase list)
	{
		return this.isActive() && this.materialList == list;
	}

	/**
	 * Asks the server to analyze the given area.
	 *
	 * @return false if the server cannot do it, in which case the caller should fall back
	 *         to the local {@code TaskCountBlocksArea}
	 */
	public boolean start(AreaSelection area, MaterialListBase materialList)
	{
		if (!this.begin())
		{
			return false;
		}

		this.materialList = materialList;
		// Captured for the whole run, the way the local task captures them in its
		// constructor: what comes back has to be read the way it was asked for
		this.entitiesInclusionType = materialList.getEntitiesInclusionType();
		this.containersInclusionType = materialList.getContainersInclusionType();

		CompoundData nbt = new CompoundData();

		// The area travels as its own JSON, which the server parses with the same
		// AreaSelection port, so there is no second area format to keep in step
		nbt.putString("Area", area.toJson().toString());
		nbt.putBoolean("CountEntities", this.entitiesInclusionType != InclusionType.NONE);
		nbt.putBoolean("CountContainers", this.containersInclusionType != InclusionType.NONE);

		// The local task only honours the render layers when the list asks for them, and
		// the server counts everything when no range is given
		if (materialList.getMaterialListType() == BlockInfoListType.RENDER_LAYERS)
		{
			nbt.putCodec("RenderLayerRange", LayerRange.CODEC, DataManager.getRenderLayerRange());
		}

		Litematica.debugLog("ServerAnalyzeSession: requesting an analysis of '{}' (session {})", area.getName(), this.sessionId);

		return this.send(nbt);
	}

	@Override
	protected void clear()
	{
		super.clear();

		this.materialList = null;
		this.entitiesInclusionType = InclusionType.NONE;
		this.containersInclusionType = InclusionType.NONE;
		this.blockCounts.clear();
		this.entityCounts.clear();
		this.containerCounts.clear();
	}

	@Override
	public void handleResult(CompoundData nbt)
	{
		if (!this.matches(nbt) || this.materialList == null)
		{
			return;
		}

		ServerTaskResultReader.readBlockCounts(nbt.getIntArray("BlockPalette"), nbt.getIntArray("BlockCounts"), this.blockCounts);
		// Entity types resolve to the same items the local analyzer counts them as, so a
		// server result and a local result render identically
		ServerTaskResultReader.readEntityCounts(nbt.getList("EntityIds"), nbt.getIntArray("EntityCounts"), this.entityCounts);
		ServerTaskResultReader.readItemCounts(nbt.getList("ItemIds"), nbt.getIntArray("ItemCounts"), this.containerCounts);

		final int batch = nbt.getInt("Batch");
		final boolean last = nbt.getBoolean("Final");

		// Acknowledge either way: the server frees the session on the final ack
		this.acknowledge(batch);

		if (last)
		{
			this.finish(nbt.getCompound("Totals"));
		}
	}

	/** The server has sent its final batch; turn the tallies into the list itself. */
	private void finish(@Nullable CompoundData totals)
	{
		MaterialListBase list = this.materialList;

		List<MaterialListEntry> entries = MaterialListUtils.buildEntriesForAreaAnalyzer(
				this.blockCounts,
				this.entityCounts, this.entitiesInclusionType,
				this.containerCounts, this.containersInclusionType,
				Minecraft.getInstance().player);

		// clear() before delivering: setMaterialListEntries() notifies the GUI, which may
		// then ask whether a run is still going
		this.clear();

		if (list != null)
		{
			list.setMaterialListEntries(entries);
		}

		this.warnAboutSkippedChunks(totals);
	}
}
