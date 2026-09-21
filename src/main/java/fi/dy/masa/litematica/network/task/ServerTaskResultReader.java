package fi.dy.masa.litematica.network.task;

import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.malilib.util.data.ItemType;
import fi.dy.masa.malilib.util.data.tag.BaseData;
import fi.dy.masa.malilib.util.data.tag.ListData;
import fi.dy.masa.malilib.util.data.tag.StringData;

/**
 * Decodes the tallies the server side analysis and material list send back.
 * <p>
 * Both use the same two encodings. Block states travel as global palette ids - the same
 * numbering vanilla chunk packets use - so each resolves straight back to the canonical
 * {@link BlockState} instance the material cache keys on. Items and entity types travel as
 * registry identifiers rather than numeric ids, because those only mean anything after
 * registry sync and shift with the mod set.
 * <p>
 * Every count array runs parallel to its palette or id list; a short one would mean a
 * malformed batch, so each reader stops at the shorter of the two.
 */
public class ServerTaskResultReader
{
	/** Adds a palette and its parallel count array to a block state tally. */
	public static void readBlockCounts(int[] palette, int[] counts, Object2IntOpenHashMap<BlockState> out)
	{
		final int size = Math.min(palette.length, counts.length);

		for (int i = 0; i < size; i++)
		{
			out.addTo(Block.stateById(palette[i]), counts[i]);
		}
	}

	/**
	 * Adds an identifier list and its parallel count array to an item tally.
	 * <p>
	 * Entity types resolve through the item registry too: the local material list and
	 * analyzer map an entity type onto the item of the same identifier, which for an entity
	 * with no such item (a cow, say) is the empty item. Doing the same here makes a server
	 * result and a local one price up the same things the same way.
	 */
	public static void readItemCounts(@Nullable ListData ids, int[] counts, Object2IntOpenHashMap<ItemType> out)
	{
		if (ids == null)
		{
			return;
		}

		final int size = Math.min(ids.size(), counts.length);

		for (int i = 0; i < size; i++)
		{
			BaseData entry = ids.get(i);

			if (!(entry instanceof StringData str))
			{
				continue;
			}

			Identifier id = Identifier.tryParse(str.getString());

			if (id == null)
			{
				continue;
			}

			Item item = BuiltInRegistries.ITEM.getValue(id);

			out.addTo(new ItemType(new ItemStack(item), false), counts[i]);
		}
	}
}
