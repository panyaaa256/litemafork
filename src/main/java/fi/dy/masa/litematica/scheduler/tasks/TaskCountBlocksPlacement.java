package fi.dy.masa.litematica.scheduler.tasks;

import java.util.Collection;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.malilib.util.position.IntBoundingBox;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.IMaterialList;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.litematica.util.BlockUtils;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.InclusionType;

public class TaskCountBlocksPlacement extends TaskCountBlocksBase
{
    protected final SchematicPlacement schematicPlacement;
    protected final boolean ignoreState;

    public TaskCountBlocksPlacement(SchematicPlacement schematicPlacement, IMaterialList materialList)
    {
        this(schematicPlacement, materialList, false);
    }

    public TaskCountBlocksPlacement(SchematicPlacement schematicPlacement, IMaterialList materialList, boolean ignoreState)
    {
        super(materialList, "litematica.gui.label.task_name.material_list");

        this.schematicPlacement = schematicPlacement;
        this.ignoreState = ignoreState;
        Collection<Box> boxes = schematicPlacement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).values();

        // Filter/clamp the boxes to intersect with the render layer
        if (materialList.getMaterialListType() == BlockInfoListType.RENDER_LAYERS)
        {
            this.addPerChunkBoxes(boxes, DataManager.getRenderLayerRange());
        }
        else
        {
            this.addPerChunkBoxes(boxes);
        }

    }

    @Override
    public boolean canExecute()
    {
        return super.canExecute() && this.schematicWorld != null;
    }

    @Override
    protected void countEntitiesInChunk(ChunkPos pos)
    {
        // The schematic world holds every placement's entities, and those of the sub regions
        // left out of this one too. Only the entities within the boxes the blocks are counted
        // in belong to this list, and within the render layers the blocks are clamped to.
        List<IntBoundingBox> boxes = this.getBoxesInChunk(pos);
        List<Entity> entities = this.schematicWorld.getEntitiesByChunk(pos.x(), pos.z(), EntityUtils.NOT_PLAYER);

        for (Entity entity : entities)
        {
            BlockPos entityPos = entity.blockPosition();

            // Any box will do, so that an entity where two boxes overlap is counted once
            if (boxes.stream().anyMatch(box -> box.contains(entityPos)) && this.layerRange.isPositionWithinRange(entityPos))
            {
                this.countEntity(entity);
            }
        }
    }

    @Override
    protected List<MaterialListEntry> buildMaterialListEntries()
    {
        return MaterialListUtils.buildEntriesForPlacement(this.countsTotal, this.countsMissing, this.countsMismatch,
                                                           this.entitiesTotal, this.entitiesInclusionType,
                                                           this.containersTotal, this.containersInclusionType,
                                                           this.mc.player);
    }

    @Override
    protected void countAtPosition(BlockPos pos)
    {
        BlockState stateSchematic = this.schematicWorld.getBlockState(pos);

        if (stateSchematic.isAir() == false)
        {
            BlockState stateClient = this.clientWorld.getBlockState(pos);

            this.countsTotal.addTo(stateSchematic, 1);

            if (stateClient.isAir())
            {
                this.countsMissing.addTo(stateSchematic, 1);
            }
            else if (stateClient != stateSchematic &&
                    (this.ignoreState == false || stateClient.getBlock() != stateSchematic.getBlock()))
            {
                if (Configs.Visuals.IGNORE_CROP_AGE.getBooleanValue() == false ||
                    BlockUtils.areStatesEqualIgnoringAge(stateSchematic, stateClient) == false)
                {
                    this.countsMissing.addTo(stateSchematic, 1);
                    this.countsMismatch.addTo(stateSchematic, 1);
                }
            }

            if (this.containersInclusionType != InclusionType.NONE)
            {
                BlockEntity blockEntity = this.schematicWorld.getBlockEntity(pos);

                if (blockEntity instanceof Container container)
                {
                    this.addContainerItems(container);
                }
            }
        }
    }
}
