package fi.dy.masa.litematica.schematic.verifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;

/**
 * Both sides of one container whose contents do not match, as a server side verification
 * sent them: the schematic's block entity data, and the world's.
 * <p>
 * The containers themselves are only created when something asks to show them, since a
 * result can hold a thousand of these and most are never looked at.
 * <p>
 * Which slots differ is worked out the same way Servux decided that the contents differ at
 * all, so that what is highlighted is what the server objected to. By default that is a
 * multiset of {@code (item, count)} regardless of slot, in which case the schematic side
 * marks the items the world is short of and the world side the ones it has too many of;
 * with slot exact comparison it is simply every slot whose stack differs.
 */
public class ContentsMismatch
{
    private static final String ITEMS_KEY = "Items";
    private static final String SLOT_KEY = "Slot";
    private static final String ID_KEY = "id";
    private static final String COUNT_KEY = "count";
    private static final String COMPONENTS_KEY = "components";

    private final BlockPos pos;
    private final CompoundTag expectedTag;
    private final CompoundTag foundTag;
    private final CompoundData expectedData;
    private final CompoundData foundData;
    private final Set<Integer> differingExpectedSlots = new HashSet<>();
    private final Set<Integer> differingFoundSlots = new HashSet<>();
    @Nullable private Container expected;
    @Nullable private Container found;
    private boolean containersCreated;
    private boolean differencesComputed;
    private boolean slotExact;
    private boolean strict;
    @Nullable private String expectedContentsKey;

    public ContentsMismatch(BlockPos pos, CompoundData expectedData, CompoundData foundData)
    {
        this.pos = pos;
        this.expectedData = expectedData;
        this.foundData = foundData;
        this.expectedTag = DataConverterNbt.toVanillaCompound(expectedData);
        this.foundTag = DataConverterNbt.toVanillaCompound(foundData);

        // An older schematic may not name its block entity type. Both sides have the same
        // block state, or this would have been a block mismatch instead, so the world's does
        if (this.expectedTag.contains(ID_KEY) == false && this.foundTag.contains(ID_KEY))
        {
            this.expectedTag.putString(ID_KEY, this.foundTag.getStringOr(ID_KEY, ""));
        }
    }

    public CompoundData getExpectedData()
    {
        return this.expectedData;
    }

    public CompoundData getFoundData()
    {
        return this.foundData;
    }

    /**
     * A key standing for the schematic side's contents, equal for two containers holding
     * exactly the same thing: the same item with the same count and the same components in
     * the same slot, every slot over. A grouped Wrong Contents row only ever puts together
     * containers that share it, since a row of containers holding different things would
     * say nothing about what any of them should hold.
     */
    public String getExpectedContentsKey()
    {
        if (this.expectedContentsKey == null)
        {
            this.expectedContentsKey = contentsKeyOf(this.expectedTag);
        }

        return this.expectedContentsKey;
    }

    /** Every stack of one container as "slot: id xN components", in a fixed order. */
    private static String contentsKeyOf(CompoundTag tag)
    {
        ListTag items = tag.getListOrEmpty(ITEMS_KEY);
        List<String> stacks = new ArrayList<>();

        for (int i = 0; i < items.size(); i++)
        {
            CompoundTag stack = items.getCompound(i).orElse(null);

            if (stack == null)
            {
                continue;
            }

            int count = stack.getIntOr(COUNT_KEY, 1);

            if (count <= 0)
            {
                continue;
            }

            Tag components = stack.get(COMPONENTS_KEY);

            stacks.add(stack.getIntOr(SLOT_KEY, i) + ": " + stack.getStringOr(ID_KEY, "") + " x" + count +
                       (components != null ? " " + components : ""));
        }

        // The order the stacks happen to be stored in says nothing about the contents
        Collections.sort(stacks);

        return String.join(", ", stacks);
    }

    /** The schematic's container, or null if it could not be created. */
    @Nullable
    public Container getExpected(BlockState state)
    {
        this.createContainers(state);
        return this.expected;
    }

    /** The world's container, or null if it could not be created. */
    @Nullable
    public Container getFound(BlockState state)
    {
        this.createContainers(state);
        return this.found;
    }

    private void createContainers(BlockState state)
    {
        Level world = Minecraft.getInstance().level;

        if (this.containersCreated || world == null)
        {
            return;
        }

        this.containersCreated = true;
        this.expected = createContainer(this.pos, state, this.expectedTag, world);
        this.found = createContainer(this.pos, state, this.foundTag, world);
    }

    @Nullable
    private static Container createContainer(BlockPos pos, BlockState state, CompoundTag tag, Level world)
    {
        try
        {
            BlockEntity be = BlockEntity.loadStatic(pos, state, tag, world.registryAccess());
            return be instanceof Container container ? container : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** The schematic side slots to point out. */
    public Set<Integer> getDifferingExpectedSlots(boolean slotExact, boolean strict)
    {
        this.computeDifferences(slotExact, strict);
        return this.differingExpectedSlots;
    }

    /** The world side slots to point out. */
    public Set<Integer> getDifferingFoundSlots(boolean slotExact, boolean strict)
    {
        this.computeDifferences(slotExact, strict);
        return this.differingFoundSlots;
    }

    private void computeDifferences(boolean slotExact, boolean strict)
    {
        if (this.differencesComputed && this.slotExact == slotExact && this.strict == strict)
        {
            return;
        }

        this.differencesComputed = true;
        this.slotExact = slotExact;
        this.strict = strict;
        this.differingExpectedSlots.clear();
        this.differingFoundSlots.clear();

        ListTag expectedItems = this.expectedTag.getListOrEmpty(ITEMS_KEY);
        ListTag foundItems = this.foundTag.getListOrEmpty(ITEMS_KEY);

        if (slotExact)
        {
            Map<Integer, String> expectedSlots = this.slotMapOf(expectedItems);
            Map<Integer, String> foundSlots = this.slotMapOf(foundItems);
            Set<Integer> slots = new HashSet<>(expectedSlots.keySet());
            slots.addAll(foundSlots.keySet());

            for (int slot : slots)
            {
                if (Objects.equals(expectedSlots.get(slot), foundSlots.get(slot)) == false)
                {
                    this.differingExpectedSlots.add(slot);
                    this.differingFoundSlots.add(slot);
                }
            }
        }
        else
        {
            Map<String, Integer> expectedTotals = this.multisetOf(expectedItems);
            Map<String, Integer> foundTotals = this.multisetOf(foundItems);

            // Short of it in the world: mark where the schematic keeps it
            this.markExcess(expectedItems, expectedTotals, foundTotals, this.differingExpectedSlots);
            // More of it in the world than the schematic has: mark where the world keeps it
            this.markExcess(foundItems, foundTotals, expectedTotals, this.differingFoundSlots);
        }
    }

    private void markExcess(ListTag items, Map<String, Integer> totals, Map<String, Integer> otherTotals, Set<Integer> out)
    {
        for (int i = 0; i < items.size(); i++)
        {
            CompoundTag stack = items.getCompound(i).orElse(null);

            if (stack == null)
            {
                continue;
            }

            String key = this.keyOf(stack);

            if (totals.getOrDefault(key, 0) > otherTotals.getOrDefault(key, 0))
            {
                out.add(stack.getIntOr(SLOT_KEY, i));
            }
        }
    }

    /** item key -> total count, ignoring which slot each stack sits in; as Servux compares. */
    private Map<String, Integer> multisetOf(ListTag items)
    {
        Map<String, Integer> counts = new HashMap<>();

        for (int i = 0; i < items.size(); i++)
        {
            CompoundTag stack = items.getCompound(i).orElse(null);

            if (stack == null)
            {
                continue;
            }

            int count = stack.getIntOr(COUNT_KEY, 1);

            if (count > 0)
            {
                counts.merge(this.keyOf(stack), count, Integer::sum);
            }
        }

        return counts;
    }

    /** slot -> "key xN"; as Servux compares with verify_nbt_slot_exact. */
    private Map<Integer, String> slotMapOf(ListTag items)
    {
        Map<Integer, String> slots = new HashMap<>();

        for (int i = 0; i < items.size(); i++)
        {
            CompoundTag stack = items.getCompound(i).orElse(null);

            if (stack == null)
            {
                continue;
            }

            int count = stack.getIntOr(COUNT_KEY, 1);

            if (count > 0)
            {
                slots.put(stack.getIntOr(SLOT_KEY, i), this.keyOf(stack) + " x" + count);
            }
        }

        return slots;
    }

    /** The item id, plus the data components when compared strictly. */
    private String keyOf(CompoundTag stack)
    {
        String id = stack.getStringOr(ID_KEY, "");

        if (this.strict == false)
        {
            return id;
        }

        Tag components = stack.get(COMPONENTS_KEY);

        return components != null ? id + Objects.toString(components) : id;
    }
}
