package fi.dy.masa.litematica.network.task;

import java.util.List;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.ItemType;
import fi.dy.masa.malilib.util.LayerRange;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.litematica.util.InclusionType;
import fi.dy.masa.litematica.util.SchematicWorldRefresher;

/**
 * Client half of a server side material list.
 * <p>
 * The point of running it on the server is the "missing" column: the local task asks the
 * <i>client</i> world what is already built, and outside the render distance that answers
 * air, so a build larger than the render distance prices up as almost entirely missing.
 * The server sees all of it.
 * <p>
 * The request is the same upload a server side verification makes, because it is the same
 * walk over the same blocks. What comes back is per block state counts rather than
 * mismatch positions, plus the entity and container item tallies - exactly the maps that
 * {@link MaterialListUtils#buildEntriesForPlacement} turns into a list. Block states travel
 * rather than items because converting a state into the items it costs needs
 * {@link MaterialCache}, which is built from a client side fake world.
 */
public class ServerMaterialListSession extends ServerTaskSessionBase
{
	private static final ServerMaterialListSession INSTANCE = new ServerMaterialListSession();

	public static ServerMaterialListSession getInstance()
	{
		return INSTANCE;
	}

	private final Object2IntOpenHashMap<BlockState> countsTotal = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<BlockState> countsMissing = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<BlockState> countsMismatch = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<ItemType> entitiesTotal = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<ItemType> containersTotal = new Object2IntOpenHashMap<>();

	@Nullable private MaterialListBase materialList;
	private InclusionType entitiesInclusionType = InclusionType.NONE;
	private InclusionType containersInclusionType = InclusionType.NONE;
	private long missing;

	private ServerMaterialListSession() {}

	@Override
	protected String taskPrefix()
	{
		return "LitematicaMaterials";
	}

	@Override
	protected String featureName()
	{
		return "materials";
	}

	@Override
	protected String displayNameKey()
	{
		return "litematica.gui.label.task_name.material_list";
	}

	/** True while the given list is the one waiting on a server result. */
	public boolean isActiveFor(MaterialListBase list)
	{
		return this.isActive() && this.materialList == list;
	}

	/**
	 * Uploads the placement and asks the server to price it up.
	 *
	 * @param ignoreState do not count a block of the right type but the wrong state as
	 *                    missing, i.e. the client's own materialListIgnoreState
	 * @return false if the server cannot do it, in which case the caller should fall back
	 *         to the local {@code TaskCountBlocksPlacement}
	 */
	public boolean start(MaterialListBase list, SchematicPlacement placement, boolean ignoreState)
	{
		if (!this.begin())
		{
			return false;
		}

		this.materialList = list;
		// Captured for the whole run, the way the local task captures them in its
		// constructor: what comes back has to be read the way it was asked for
		this.entitiesInclusionType = list.getEntitiesInclusionType();
		this.containersInclusionType = list.getContainersInclusionType();

		CompoundData nbt = placement.toData(true);
		nbt.putBoolean("IgnoreState", ignoreState);
		nbt.putBoolean("CountEntities", this.entitiesInclusionType != InclusionType.NONE);
		nbt.putBoolean("CountContainers", this.containersInclusionType != InclusionType.NONE);

		// toData() always writes the current render layer range, because a paste honours it.
		// A material list only does so when it was asked for the rendered layers, so an
		// all-blocks list has to replace it with a range that covers everything - otherwise
		// the server would price up whatever slice the player happens to be looking at
		if (list.getMaterialListType() != BlockInfoListType.RENDER_LAYERS)
		{
			nbt.putCodec("RenderLayerRange", LayerRange.CODEC, new LayerRange(SchematicWorldRefresher.INSTANCE));
		}

		Litematica.debugLog("ServerMaterialListSession: requesting a material list for '{}' (session {})", placement.getName(), this.sessionId);

		return this.send(nbt);
	}

	@Override
	protected void clear()
	{
		super.clear();

		this.materialList = null;
		this.entitiesInclusionType = InclusionType.NONE;
		this.containersInclusionType = InclusionType.NONE;
		this.missing = 0;

		this.countsTotal.clear();
		this.countsMissing.clear();
		this.countsMismatch.clear();
		this.entitiesTotal.clear();
		this.containersTotal.clear();
	}

	@Override
	protected void onStatus(CompoundData nbt)
	{
		this.missing = nbt.getLong("Missing");
	}

	@Override
	protected void addExtraInfoHudLines(List<String> lines)
	{
		String color = this.missing > 0 ? GuiBase.TXT_GOLD : GuiBase.TXT_GREEN;

		lines.add(StringUtils.translate("litematica.hud.server_task.missing", color + this.missing + GuiBase.TXT_RST));
	}

	@Override
	public void handleResult(CompoundData nbt)
	{
		if (!this.matches(nbt) || this.materialList == null)
		{
			return;
		}

		// The three block tallies share one palette and run parallel to it
		int[] palette = nbt.getIntArray("BlockPalette");

		ServerTaskResultReader.readBlockCounts(palette, nbt.getIntArray("BlockCountsTotal"), this.countsTotal);
		ServerTaskResultReader.readBlockCounts(palette, nbt.getIntArray("BlockCountsMissing"), this.countsMissing);
		ServerTaskResultReader.readBlockCounts(palette, nbt.getIntArray("BlockCountsMismatch"), this.countsMismatch);
		ServerTaskResultReader.readEntityCounts(nbt.getList("EntityIds"), nbt.getIntArray("EntityCounts"), this.entitiesTotal);
		ServerTaskResultReader.readItemCounts(nbt.getList("ItemIds"), nbt.getIntArray("ItemCounts"), this.containersTotal);

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

		List<MaterialListEntry> entries = MaterialListUtils.buildEntriesForPlacement(
				this.countsTotal, this.countsMissing, this.countsMismatch,
				this.entitiesTotal, this.entitiesInclusionType,
				this.containersTotal, this.containersInclusionType,
				Minecraft.getInstance().player);

		// clear() before delivering: setMaterialListEntries() notifies the GUI, which may
		// then ask whether a run is still going
		this.clear();

		if (list != null)
		{
			list.setMaterialListEntries(entries);
		}

		// Those chunks were never read, so their blocks are in none of the counts above
		this.warnAboutSkippedChunks(totals);
	}
}
