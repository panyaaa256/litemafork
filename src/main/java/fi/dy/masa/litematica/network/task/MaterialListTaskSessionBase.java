package fi.dy.masa.litematica.network.task;

import java.util.List;
import javax.annotation.Nullable;

import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.util.InclusionType;

/**
 * A server side task whose result is a material list: the area analysis and the material
 * list itself.
 * <p>
 * Both accumulate their tallies across batches and hand them to one {@link MaterialListBase}
 * only once the final batch lands, so the GUI never shows a half-built list, and both have
 * to remember how that list asked for entities and containers - what comes back has to be
 * read the way it was asked for.
 */
public abstract class MaterialListTaskSessionBase extends ServerTaskSessionBase
{
	@Nullable protected MaterialListBase materialList;
	protected InclusionType entitiesInclusionType = InclusionType.NONE;
	protected InclusionType containersInclusionType = InclusionType.NONE;

	/** True while the given list is the one waiting on a server result. */
	public boolean isActiveFor(MaterialListBase list)
	{
		return this.isActive() && this.materialList == list;
	}

	/**
	 * Takes on the list a run is being requested for, and the inclusion types to read its
	 * result by. {@link #begin} has to have said yes first.
	 */
	protected void claim(MaterialListBase list)
	{
		this.materialList = list;
		// Captured for the whole run, the way the local task captures them in its
		// constructor: what comes back has to be read the way it was asked for
		this.entitiesInclusionType = list.getEntitiesInclusionType();
		this.containersInclusionType = list.getContainersInclusionType();
	}

	@Override
	protected void clear()
	{
		super.clear();

		this.materialList = null;
		this.entitiesInclusionType = InclusionType.NONE;
		this.containersInclusionType = InclusionType.NONE;

		this.clearCounts();
	}

	/** Drops the tallies gathered so far. */
	protected abstract void clearCounts();

	/** Adds the tallies of one result batch to the ones gathered so far. */
	protected abstract void readCounts(CompoundData nbt);

	/** The tallies as list entries, once the last batch has been read. */
	protected abstract List<MaterialListEntry> buildEntries();

	@Override
	public void handleResult(CompoundData nbt)
	{
		if (!this.matches(nbt) || this.materialList == null)
		{
			return;
		}

		this.readCounts(nbt);

		// Acknowledge either way: the server frees the session on the final ack
		this.acknowledge(nbt.getInt("Batch"));

		if (nbt.getBoolean("Final"))
		{
			this.finish(nbt.getCompound("Totals"));
		}
	}

	/** The server has sent its final batch; turn the tallies into the list itself. */
	private void finish(@Nullable CompoundData totals)
	{
		MaterialListBase list = this.materialList;
		List<MaterialListEntry> entries = this.buildEntries();

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
