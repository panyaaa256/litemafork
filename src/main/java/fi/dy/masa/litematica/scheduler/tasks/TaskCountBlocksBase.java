package fi.dy.masa.litematica.scheduler.tasks;

import java.util.List;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.malilib.util.data.ItemType;
import fi.dy.masa.malilib.util.position.IntBoundingBox;
import fi.dy.masa.malilib.util.position.LayerRange;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.IMaterialList;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.InclusionType;
import fi.dy.masa.litematica.util.SchematicWorldRefresher;

public abstract class TaskCountBlocksBase extends TaskProcessChunkBase
{
    protected final Object2IntOpenHashMap<BlockState> countsTotal = new Object2IntOpenHashMap<>();
    protected final Object2IntOpenHashMap<BlockState> countsMissing = new Object2IntOpenHashMap<>();
    protected final Object2IntOpenHashMap<BlockState> countsMismatch = new Object2IntOpenHashMap<>();
    protected final Object2IntOpenHashMap<ItemType> entitiesTotal = new Object2IntOpenHashMap<>();
    protected final Object2IntOpenHashMap<ItemType> containersTotal = new Object2IntOpenHashMap<>();
    protected final IMaterialList materialList;
    protected final InclusionType entitiesInclusionType;
    protected final InclusionType containersInclusionType;
    protected final LayerRange layerRange;

    protected TaskCountBlocksBase(IMaterialList materialList, String nameOnHud)
    {
        super(nameOnHud);

        this.materialList = materialList;
        this.entitiesInclusionType = materialList.getEntitiesInclusionType();
        this.containersInclusionType = materialList.getContainersInclusionType();

        if (materialList.getMaterialListType() == BlockInfoListType.ALL)
        {
            this.layerRange = new LayerRange(SchematicWorldRefresher.INSTANCE);
        }
        else
        {
            this.layerRange = DataManager.getRenderLayerRange();
        }
    }

    @Override
    protected boolean canProcessChunk(ChunkPos pos)
    {
        return this.areSurroundingChunksLoaded(pos, this.clientWorld, 0);
    }

    @Override
    protected boolean processChunk(ChunkPos pos)
    {
        this.countBlocksInChunk(pos);

        if (this.entitiesInclusionType != InclusionType.NONE || this.containersInclusionType != InclusionType.NONE)
        {
            this.countEntitiesInChunk(pos);
        }

        return true;
    }

    /**
     * Counts the entities of one chunk, if this kind of count includes any. Which world
     * they come from, and which of them are in range, is up to the subclass; what each one
     * costs is {@link #countEntity}.
     */
    protected void countEntitiesInChunk(ChunkPos pos)
    {
    }

    /** Counts one entity as the item it stands for, and its contents if it holds any. */
    protected void countEntity(Entity entity)
    {
        if (this.entitiesInclusionType != InclusionType.NONE)
        {
            ItemStack stack = EntityUtils.getEntityItem(entity);

            if (stack.isEmpty() == false)
            {
                this.entitiesTotal.addTo(new ItemType(stack, false), 1);
            }
        }

        if (this.containersInclusionType != InclusionType.NONE && entity instanceof Container container)
        {
            this.addContainerItems(container);
        }
    }

    protected void addContainerItems(Container container)
    {
        Object2IntOpenHashMap<ItemType> items = MaterialListUtils.getInventoryItemCounts(container);

        for (ItemType itemType : items.keySet())
        {
            this.containersTotal.addTo(itemType, items.getInt(itemType));
        }
    }

    protected void countBlocksInChunk(ChunkPos pos)
    {
        LayerRange range = this.layerRange;
        Direction.Axis axis = range.getAxis();
        BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos();

        for (IntBoundingBox bb : this.getBoxesInChunk(pos))
        {
            final int startX = axis == Direction.Axis.X ? Math.max(bb.minX(), range.getMinLayerBoundary()) : bb.minX();
            final int startY = axis == Direction.Axis.Y ? Math.max(bb.minY(), range.getMinLayerBoundary()) : bb.minY();
            final int startZ = axis == Direction.Axis.Z ? Math.max(bb.minZ(), range.getMinLayerBoundary()) : bb.minZ();
            final int endX = axis == Direction.Axis.X ? Math.min(bb.maxX(), range.getMaxLayerBoundary()) : bb.maxX();
            final int endY = axis == Direction.Axis.Y ? Math.min(bb.maxY(), range.getMaxLayerBoundary()) : bb.maxY();
            final int endZ = axis == Direction.Axis.Z ? Math.min(bb.maxZ(), range.getMaxLayerBoundary()) : bb.maxZ();

            for (int y = startY; y <= endY; ++y)
            {
                for (int z = startZ; z <= endZ; ++z)
                {
                    for (int x = startX; x <= endX; ++x)
                    {
                        posMutable.set(x, y, z);
                        this.countAtPosition(posMutable);
                    }
                }
            }
        }
    }

    protected abstract void countAtPosition(BlockPos pos);

    @Override
    protected void onStop()
    {
        if (this.finished && this.isInWorld())
        {
            this.materialList.setMaterialListEntries(this.buildMaterialListEntries());
        }

        InfoHud.getInstance().removeInfoHudRenderer(this, false);

        super.onStop();
    }

    protected List<MaterialListEntry> buildMaterialListEntries()
    {
        return MaterialListUtils.buildEntriesFromBlockCounts(this.countsTotal, this.countsMissing, this.countsMismatch, this.mc.player);
    }
}
