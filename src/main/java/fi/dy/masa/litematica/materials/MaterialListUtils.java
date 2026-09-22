package fi.dy.masa.litematica.materials;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.data.ItemType;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.ListData;
import fi.dy.masa.malilib.util.nbt.NbtInventory;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.InclusionType;

/**
 * Naming convention used in this class:
 * - "create*ItemCounts"  : tally up item counts (Object2IntOpenHashMap<ItemType>) from schematic/world data
 * - "createMaterialList*": build the final List<MaterialListEntry> for a whole schematic/placement
 * - "buildEntriesFrom*"  : turn already-tallied item/block counts into a List<MaterialListEntry>
 */
public class MaterialListUtils
{
    /**
     * Builds the material list for a schematic that hasn't been placed yet, i.e. there is no
     * world to compare against, so everything is reported as missing.
     */
    public static List<MaterialListEntry> createMaterialListForSchematic(LitematicaSchematic schematic,
                                                                           Collection<String> subRegions,
                                                                           InclusionType entitiesInclusionType,
                                                                           InclusionType containersInclusionType)
    {
        Player player = Minecraft.getInstance().player;
        Object2IntOpenHashMap<ItemType> total = onlyTally(entitiesInclusionType, () -> createEntityItemCounts(schematic, subRegions),
                                                          containersInclusionType, () -> createContainerItemCounts(schematic, subRegions));

        if (total == null)
        {
            total = createBlockItemCounts(schematic, subRegions);

            if (entitiesInclusionType != InclusionType.NONE)
            {
                addTally(total, null, createEntityItemCounts(schematic, subRegions));
            }

            if (containersInclusionType != InclusionType.NONE)
            {
                addTally(total, null, createContainerItemCounts(schematic, subRegions));
            }
        }

        // None of it is built yet, so all of it is missing
        return buildEntriesFromItemCounts(total, total.clone(), new Object2IntOpenHashMap<>(), player);
    }

    /**
     * The tally a list of only the entities or only the container contents is built from,
     * or null when the list is the usual one of everything.
     * <p>
     * The tallies are suppliers because working one out means walking the schematic, and
     * only the one that is asked for should be walked for.
     */
    @Nullable
    private static Object2IntOpenHashMap<ItemType> onlyTally(InclusionType entitiesInclusionType,
                                                             Supplier<Object2IntOpenHashMap<ItemType>> entities,
                                                             InclusionType containersInclusionType,
                                                             Supplier<Object2IntOpenHashMap<ItemType>> containers)
    {
        if (entitiesInclusionType == InclusionType.ONLY)
        {
            return entities.get();
        }

        if (containersInclusionType == InclusionType.ONLY)
        {
            return containers.get();
        }

        return null;
    }

    /**
     * Creates a material list directly from item types and quantities,
     * bypassing the block-based conversion system. This allows tracking
     * of any item type including non-placeable items like tools and food.
     *
     * @param items Map of ItemType to quantity
     * @param player Player entity for inventory tracking (can be null)
     * @return List of MaterialListEntry objects
     */
    public static List<MaterialListEntry> createMaterialListFromItems(
		    java.util.Map<ItemType, Integer> items, Player player)
    {
        List<MaterialListEntry> list = new ArrayList<>();

        if (items.isEmpty())
        {
            return list;
        }

        Object2IntOpenHashMap<ItemType> playerInvItems = null;
        Object2IntOpenHashMap<ItemType> enderItems = null;

        if (player != null)
        {
            playerInvItems = getInventoryItemCounts(player.getInventory());
            NbtInventory ender = Registry.ENTITY_DATA_REGISTRY.chestTracker().getEnderCache();

            if (Configs.Generic.MATERIAL_LIST_COUNT_ENDER_CACHE.getBooleanValue() && ender != null)
            {
                Container ec = ender.toInventory(NbtInventory.DEFAULT_SIZE);

                if (ec != null)
                {
                    enderItems = getInventoryItemCounts(ec);
                }
            }
        }

        for (java.util.Map.Entry<ItemType, Integer> entry : items.entrySet())
        {
            ItemType type = entry.getKey();
            int count = entry.getValue();
            int countAvailable = playerInvItems != null
                                 ? playerInvItems.getInt(type)
                                 : 0;

            countAvailable += enderItems != null ? enderItems.getInt(type) : 0;

            // For custom item lists, total = missing (no placement state to compare against)
            list.add(new MaterialListEntry(
                type.getStack().copy(),
                count,           // countTotal
                count,           // countMissing (all items are "missing" since nothing is placed)
                0,               // countMismatched (not applicable for custom lists)
                countAvailable   // countAvailable (from player inventory)
            ));
        }

        return list;
    }

    /**
     * The material list of a whole schematic, blocks only.
     * <p>
     * This and the sub region form below are the entry points upstream has, kept under
     * their upstream names so that anything written against Litematica still calls them;
     * what this fork added is the inclusion types, which they leave at
     * {@link InclusionType#NONE}.
     */
    public static List<MaterialListEntry> createMaterialListFor(LitematicaSchematic schematic)
    {
        return createMaterialListFor(schematic, schematic.getAreas().keySet());
    }

    /** The material list of the given sub regions of a schematic, blocks only. */
    public static List<MaterialListEntry> createMaterialListFor(LitematicaSchematic schematic, Collection<String> subRegions)
    {
        return createMaterialListForSchematic(schematic, subRegions, InclusionType.NONE, InclusionType.NONE);
    }

    public static Object2IntOpenHashMap<ItemType> createBlockItemCounts(LitematicaSchematic schematic, Collection<String> subRegions)
    {
        Object2IntOpenHashMap<BlockState> countsTotal = new Object2IntOpenHashMap<>();

        for (String regionName : subRegions)
        {
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);

            if (container != null)
            {
                Vec3i size = container.getSize();
                final int sizeX = size.getX();
                final int sizeY = size.getY();
                final int sizeZ = size.getZ();

                for (int y = 0; y < sizeY; ++y)
                {
                    for (int z = 0; z < sizeZ; ++z)
                    {
                        for (int x = 0; x < sizeX; ++x)
                        {
                            BlockState state = container.get(x, y, z);
                            countsTotal.addTo(state, 1);
                        }
                    }
                }
            }
        }
        MaterialCache cache = MaterialCache.getInstance();

        return convertBlockStatesToItemCounts(countsTotal, cache);
    }

    public static Object2IntOpenHashMap<ItemType> createEntityItemCounts(LitematicaSchematic schematic, Collection<String> subRegions)
    {
        Object2IntOpenHashMap<ItemType> entitiesTotal = new Object2IntOpenHashMap<>();

        for (String regionName : subRegions)
        {
            List<EntityInfo> entitiesList = schematic.getEntityListForRegion(regionName);

            if (entitiesList == null)
            {
                continue;
            }

            for (EntityInfo entityInfo : entitiesList)
            {
                EntityType<?> type = EntityUtils.getEntityTypeById(entityInfo.nbt().getString("id"));
                ItemStack stack = type != null ? EntityUtils.getEntityItem(type) : ItemStack.EMPTY;

                if (stack.isEmpty() == false)
                {
                    entitiesTotal.addTo(new ItemType(stack, false), 1);
                }
            }
        }

        return entitiesTotal;
    }

    public static Object2IntOpenHashMap<ItemType> createContainerItemCounts(LitematicaSchematic schematic, Collection<String> subRegions)
    {
        Object2IntOpenHashMap<ItemType> containersTotal = new Object2IntOpenHashMap<>();

        for (String regionName : subRegions)
        {
            // Both are null for a region this schematic does not have, which a region name
            // that came from somewhere else - a placement, say - can name
            Map<BlockPos, CompoundData> containers = schematic.getBlockEntityMapForRegion(regionName);
            List<EntityInfo> entities = schematic.getEntityListForRegion(regionName);
            ListData items = new ListData();

            if (containers != null)
            {
                for (CompoundData containerTag : containers.values())
                {
                    addAllItems(items, containerTag);
                }
            }

            if (entities != null)
            {
                for (EntityInfo entityInfo : entities)
                {
                    addAllItems(items, entityInfo.nbt());
                }
            }

            for (int i = 0; i < items.size(); i++)
            {
                CompoundData itemTag = items.getCompoundAt(i);

                if (itemTag != null)
                {
                    accumulateContainerItem(itemTag, containersTotal);
                }
            }
        }

        return containersTotal;
    }

    /**
     * Adds the counts for a single item stack's NBT, and -- since shulker boxes can't be nested --
     * unpacks one extra level of "minecraft:container" contents if the item itself is a shulker box.
     * Bundles are intentionally not unpacked here.
     */
    private static void accumulateContainerItem(CompoundData itemTag, Object2IntOpenHashMap<ItemType> containersTotal)
    {
        addItemTagCount(itemTag, containersTotal);

        CompoundData components = itemTag.getCompound("components");

        if (components == null)
        {
            return;
        }

        ListData shulkerItems = components.getList("minecraft:container");

        for (int i = 0; shulkerItems != null && i < shulkerItems.size(); i++)
        {
            CompoundData slotCompound = shulkerItems.getCompoundAt(i);

            if (slotCompound != null)
            {
                CompoundData inner = slotCompound.getCompound("item");

                if (inner != null)
                {
                    addItemTagCount(inner, containersTotal);
                }
            }
        }
    }

    /** Appends the "Items" list of a container/entity tag, if it has one. */
    private static void addAllItems(ListData out, CompoundData tag)
    {
        ListData items = tag != null ? tag.getList("Items") : null;

        if (items != null)
        {
            out.addAll(items);
        }
    }

    private static void addItemTagCount(CompoundData itemTag, Object2IntOpenHashMap<ItemType> total)
    {
        ItemStack stack = getItemById(itemTag.getString("id"));

        if (stack.isEmpty() == false)
        {
            total.addTo(new ItemType(stack, false), itemTag.getInt("count"));
        }
    }

    /**
     * The item an id names, or the empty stack when this client does not know it.
     * <p>
     * The item registry is a defaulted one, so an unparseable or unknown id - an item of a
     * mod the client does not have, for one - resolves to air rather than to nothing, and
     * would be tallied up as a material under that name. Every caller counting items by
     * their id goes through here so that none of them do that.
     */
    public static ItemStack getItemById(String id)
    {
        Identifier identifier = Identifier.tryParse(id);

        if (identifier == null)
        {
            return ItemStack.EMPTY;
        }

        return BuiltInRegistries.ITEM.getOptional(identifier).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    /**
     * Turns already-tallied item counts into the final entry list, looking up availability from the player's inventory.
     */
    public static List<MaterialListEntry> buildEntriesFromItemCounts(
            Object2IntOpenHashMap<ItemType> itemTypesTotal,
            Object2IntOpenHashMap<ItemType> itemTypesMissing,
            Object2IntOpenHashMap<ItemType> itemTypesMismatch,
            Player player)
    {
        List<MaterialListEntry> list = new ArrayList<>();

        if (!itemTypesTotal.isEmpty())
        {
            Object2IntOpenHashMap<ItemType> playerInvItems = player != null ? getInventoryItemCounts(player.getInventory()) : new Object2IntOpenHashMap<>();
            Object2IntOpenHashMap<ItemType> enderItems = null;
            NbtInventory ender = Registry.ENTITY_DATA_REGISTRY.chestTracker().getEnderCache();

            if (player != null && Configs.Generic.MATERIAL_LIST_COUNT_ENDER_CACHE.getBooleanValue() && ender != null)
            {
                Container ec = ender.toInventory(NbtInventory.DEFAULT_SIZE);

                if (ec != null)
                {
                    enderItems = getInventoryItemCounts(ec);
                }
            }

            for (ItemType type : itemTypesTotal.keySet())
            {
                final int enderCount = enderItems != null ? enderItems.getInt(type) : 0;

                list.add(new MaterialListEntry(type.getStack().copy(),
                                               itemTypesTotal.getInt(type),
                                               itemTypesMissing.getInt(type),
                                               itemTypesMismatch.getInt(type),
                                               playerInvItems.getInt(type) + enderCount));
            }
        }
        return list;
    }

    /** The upstream name of {@link #buildEntriesFromBlockCounts}. */
    public static List<MaterialListEntry> getMaterialList(
            Object2IntOpenHashMap<BlockState> countsTotal,
            Object2IntOpenHashMap<BlockState> countsMissing,
            Object2IntOpenHashMap<BlockState> countsMismatch,
            Player player)
    {
        return buildEntriesFromBlockCounts(countsTotal, countsMissing, countsMismatch, player);
    }

    /**
     * Builds the material list for a placement (or other live-world block count), with no entities/containers.
     */
    public static List<MaterialListEntry> buildEntriesFromBlockCounts(
            Object2IntOpenHashMap<BlockState> countsTotal,
            Object2IntOpenHashMap<BlockState> countsMissing,
            Object2IntOpenHashMap<BlockState> countsMismatch,
            Player player)
    {
        return buildEntriesFromItemCounts(blockItemCounts(countsTotal), blockItemCounts(countsMissing),
                                          blockItemCounts(countsMismatch), player);
    }

    /**
     * Builds the material list for a placement, combining live-world block counts with entity/container
     * item counts gathered separately (e.g. filtered to what's visible in the current render layer).
     * Entities/containers are counted from the schematic's ghost/overlay world, so there is no real-world
     * match to check availability against: they are always reported as missing.
     */
    public static List<MaterialListEntry> buildEntriesForPlacement(
            Object2IntOpenHashMap<BlockState> countsTotal,
            Object2IntOpenHashMap<BlockState> countsMissing,
            Object2IntOpenHashMap<BlockState> countsMismatch,
            Object2IntOpenHashMap<ItemType> entitiesTotal,
            InclusionType entitiesInclusionType,
            Object2IntOpenHashMap<ItemType> containersTotal,
            InclusionType containersInclusionType,
            Player player)
    {
        Object2IntOpenHashMap<ItemType> only = onlyTally(entitiesInclusionType, () -> entitiesTotal,
                                                         containersInclusionType, () -> containersTotal);

        if (only != null)
        {
            return buildEntriesFromItemCounts(only, only.clone(), new Object2IntOpenHashMap<>(), player);
        }

        Object2IntOpenHashMap<ItemType> itemTypesTotal = blockItemCounts(countsTotal);
        Object2IntOpenHashMap<ItemType> itemTypesMissing = blockItemCounts(countsMissing);
        Object2IntOpenHashMap<ItemType> itemTypesMismatch = blockItemCounts(countsMismatch);

        if (entitiesInclusionType != InclusionType.NONE)
        {
            addTally(itemTypesTotal, itemTypesMissing, entitiesTotal);
        }

        if (containersInclusionType != InclusionType.NONE)
        {
            addTally(itemTypesTotal, itemTypesMissing, containersTotal);
        }

        return buildEntriesFromItemCounts(itemTypesTotal, itemTypesMissing, itemTypesMismatch, player);
    }

    /**
     * Builds the material list for the area analyzer: a plain inventory of what's currently present
     * in the selected area, with no "missing" concept (there is nothing to compare the area against).
     */
    public static List<MaterialListEntry> buildEntriesForAreaAnalyzer(
            Object2IntOpenHashMap<BlockState> countsTotal,
            Object2IntOpenHashMap<ItemType> entitiesTotal,
            InclusionType entitiesInclusionType,
            Object2IntOpenHashMap<ItemType> containersTotal,
            InclusionType containersInclusionType,
            Player player)
    {
        Object2IntOpenHashMap<ItemType> itemTypesTotal = onlyTally(entitiesInclusionType, () -> entitiesTotal,
                                                                   containersInclusionType, () -> containersTotal);

        if (itemTypesTotal == null)
        {
            itemTypesTotal = blockItemCounts(countsTotal);

            if (entitiesInclusionType != InclusionType.NONE)
            {
                addTally(itemTypesTotal, null, entitiesTotal);
            }

            if (containersInclusionType != InclusionType.NONE)
            {
                addTally(itemTypesTotal, null, containersTotal);
            }
        }

        // There is nothing to compare an area against, so nothing of it is missing
        Object2IntOpenHashMap<ItemType> empty = new Object2IntOpenHashMap<>();

        return buildEntriesFromItemCounts(itemTypesTotal, empty, empty, player);
    }

    /**
     * Adds an entity or container tally to the list it belongs to.
     *
     * @param missing the missing column, when the list has one: entities and containers
     *                are counted from the schematic, so there is nothing in the world to
     *                match them against and all of them count as missing
     */
    private static void addTally(Object2IntOpenHashMap<ItemType> total,
                                 @Nullable Object2IntOpenHashMap<ItemType> missing,
                                 Object2IntOpenHashMap<ItemType> extra)
    {
        for (ItemType itemType : extra.keySet())
        {
            int count = extra.getInt(itemType);

            total.addTo(itemType, count);

            if (missing != null)
            {
                missing.addTo(itemType, count);
            }
        }
    }

    /** The items a block state tally costs, or an empty tally when there are no blocks. */
    private static Object2IntOpenHashMap<ItemType> blockItemCounts(Object2IntOpenHashMap<BlockState> counts)
    {
        return counts.isEmpty() ? new Object2IntOpenHashMap<>()
                                : convertBlockStatesToItemCounts(counts, MaterialCache.getInstance());
    }

    private static Object2IntOpenHashMap<ItemType> convertBlockStatesToItemCounts(
            Object2IntOpenHashMap<BlockState> blockStatesIn,
            MaterialCache cache)
    {
        Object2IntOpenHashMap<ItemType> itemTypesOut = new Object2IntOpenHashMap<>();
        for (BlockState state : blockStatesIn.keySet())
        {
            int count = blockStatesIn.getInt(state);
            BlockState stateToConvert = isWaterloggedBlock(state) ? getBaseBlockState(state) : state;

            // Add water bucket for waterlogged blocks
            if (isWaterloggedBlock(state))
            {
                itemTypesOut.addTo(new ItemType(new ItemStack(Items.WATER_BUCKET), false, false), count);
            }

            // Convert block to items
            if (cache.requiresMultipleItems(stateToConvert))
            {
                for (ItemStack stack : cache.getItems(stateToConvert))
                {
                    if (!stack.isEmpty())
                    {
                        itemTypesOut.addTo(new ItemType(stack, true, false), count * stack.getCount());
                    }
                }
            }
            else
            {
                ItemStack stack = cache.getRequiredBuildItemForState(stateToConvert);
                if (!stack.isEmpty())
                {
                    itemTypesOut.addTo(new ItemType(stack, true, false), count * stack.getCount());
                }
            }
        }

        return itemTypesOut;
    }

    public static void updateAvailableCounts(List<MaterialListEntry> list, Player player)
    {
        if (player == null) return;
        Object2IntOpenHashMap<ItemType> playerInvItems = getInventoryItemCounts(player.getInventory());
        Object2IntOpenHashMap<ItemType> enderItems = null;
        NbtInventory ender = Registry.ENTITY_DATA_REGISTRY.chestTracker().getEnderCache();

        if (Configs.Generic.MATERIAL_LIST_COUNT_ENDER_CACHE.getBooleanValue() && ender != null)
        {
            Container ec = ender.toInventory(NbtInventory.DEFAULT_SIZE);

            if (ec != null)
            {
                enderItems = getInventoryItemCounts(ec);
            }
        }

        for (MaterialListEntry entry : list)
        {
            ItemType type = new ItemType(entry.getStack(), true, false);
            int countAvailable = enderItems != null
                                 ? playerInvItems.getInt(type) + enderItems.getInt(type)
                                 : playerInvItems.getInt(type);
            entry.setCountAvailable(countAvailable);
        }
    }

    public static Object2IntOpenHashMap<ItemType> getInventoryItemCounts(Container inv)
    {
        Object2IntOpenHashMap<ItemType> map = new Object2IntOpenHashMap<>();
        final int slots = inv.getContainerSize();

        for (int slot = 0; slot < slots; ++slot)
        {
            ItemStack stack = inv.getItem(slot);

            if (stack.isEmpty() == false)
            {
                Item item = stack.getItem();

                if (item instanceof BlockItem &&
                    ((BlockItem) stack.getItem()).getBlock() instanceof ShulkerBoxBlock &&
                    InventoryUtils.shulkerBoxHasItems(stack))
                {
                    Object2IntOpenHashMap<ItemType> boxCounts = getStoredItemCounts(stack);

                    for (ItemType boxType : boxCounts.keySet())
                    {
                        map.addTo(boxType, boxCounts.getInt(boxType));
                    }

                    boxCounts.clear();
                }
                else if (item instanceof BundleItem && InventoryUtils.bundleHasItems(stack))
                {
                    Object2IntOpenHashMap<ItemType> bundleCounts = getBundleItemCounts(stack);

                    for (ItemType bundleType : bundleCounts.keySet())
                    {
                        map.addTo(bundleType, bundleCounts.getInt(bundleType));
                    }

                    bundleCounts.clear();
                }
                else
                {
                    map.addTo(new ItemType(stack, true, false), stack.getCount());
                }
            }
        }

        return map;
    }

    public static Object2IntOpenHashMap<ItemType> getStoredItemCounts(ItemStack stackShulkerBox)
    {
        Object2IntOpenHashMap<ItemType> map = new Object2IntOpenHashMap<>();
        NonNullList<ItemStack> items = InventoryUtils.getStoredItems(stackShulkerBox);
		int multiplier = stackShulkerBox.getCount();

        for (ItemStack boxStack : items)
        {
            if (boxStack.isEmpty() == false)
            {
                // Copy Nested Bundles
                if (boxStack.getItem() instanceof BundleItem && InventoryUtils.bundleHasItems(boxStack))
                {
                    Object2IntOpenHashMap<ItemType> bundleMap = getBundleItemCounts(boxStack);

                    if (!bundleMap.isEmpty())
                    {
                        bundleMap.forEach(map::addTo);
                    }
                }
				
                map.addTo(new ItemType(boxStack, false, false), boxStack.getCount() * multiplier);
            }
        }

        return map;
    }

    public static Object2IntOpenHashMap<ItemType> getBundleItemCounts(ItemStack stackBundle)
    {
        Object2IntOpenHashMap<ItemType> map = new Object2IntOpenHashMap<>();
        NonNullList<ItemStack> items = InventoryUtils.getBundleItems(stackBundle);

        for (ItemStack bundleStack : items)
        {
            if (bundleStack.isEmpty() == false)
            {
                // Copy Nested Bundles
                if (bundleStack.getItem() instanceof BundleItem && InventoryUtils.bundleHasItems(bundleStack))
                {
                    Object2IntOpenHashMap<ItemType> bundleMap = getBundleItemCounts(bundleStack);

                    if (!bundleMap.isEmpty())
                    {
                        bundleMap.forEach(map::addTo);
                    }
                }

                map.addTo(new ItemType(bundleStack, false, false), bundleStack.getCount());
            }
        }

        return map;
    }

    private static boolean isWaterloggedBlock(BlockState state)
    {
        return state.hasProperty(BlockStateProperties.WATERLOGGED) &&
               state.getValue(BlockStateProperties.WATERLOGGED);
    }

    private static BlockState getBaseBlockState(BlockState state)
    {
        if (state.hasProperty(BlockStateProperties.WATERLOGGED))
        {
            return state.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return state;
    }
}
