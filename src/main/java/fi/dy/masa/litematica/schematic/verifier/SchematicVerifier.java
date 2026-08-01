package fi.dy.masa.litematica.schematic.verifier;

import java.util.*;
import javax.annotation.Nullable;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.Color4f;
import fi.dy.masa.malilib.util.position.IntBoundingBox;
import fi.dy.masa.malilib.util.position.LayerRange;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.render.infohud.IInfoHudRenderer;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.render.infohud.RenderPhase;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.scheduler.tasks.TaskBase;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.util.*;
import fi.dy.masa.litematica.world.WorldSchematic;

public class SchematicVerifier extends TaskBase implements IInfoHudRenderer
{
    private static final MutablePair<BlockState, BlockState> MUTABLE_PAIR = new MutablePair<>();
    private static final BlockPos.MutableBlockPos MUTABLE_POS = new BlockPos.MutableBlockPos();
    private static final List<SchematicVerifier> ACTIVE_VERIFIERS = new ArrayList<>();

    private final ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> missingBlocksPositions = ArrayListMultimap.create();
    private final ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> extraBlocksPositions = ArrayListMultimap.create();
    private final ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> wrongBlocksPositions = ArrayListMultimap.create();
    private final ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> wrongStatesPositions = ArrayListMultimap.create();
    private final ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> diffBlocksPositions = ArrayListMultimap.create();
    private final ArrayListMultimap<EntityType<?>, MissingEntityEntry> missingEntitiesPositions = ArrayListMultimap.create();
    private final Object2ObjectOpenHashMap<EntityType<?>, ItemStack> entityMismatchStacks = new Object2ObjectOpenHashMap<>();
    private final Set<UUID> usedClientEntityUuids = new HashSet<>();
    private final Map<UUID, MissingEntityEntry> matchedClientEntities = new HashMap<>();
    private final Set<EntityMismatch> selectedEntityEntries = new HashSet<>();
    private final Set<UUID> entityHighlightUuids = new HashSet<>();
    private final List<MismatchRenderPos> entityMismatchPositionsForRender = new ArrayList<>();
    private final Object2IntOpenHashMap<BlockState> correctStateCounts = new Object2IntOpenHashMap<>();
    private final Object2ObjectOpenHashMap<BlockPos, BlockMismatch> blockMismatches = new Object2ObjectOpenHashMap<>();
    private final HashSet<Pair<BlockState, BlockState>> ignoredMismatches = new HashSet<>();
    private final List<BlockPos> missingBlocksPositionsClosest = new ArrayList<>();
    private final List<BlockPos> extraBlocksPositionsClosest = new ArrayList<>();
    private final List<BlockPos> mismatchedBlocksPositionsClosest = new ArrayList<>();
    private final List<BlockPos> mismatchedStatesPositionsClosest = new ArrayList<>();
    private final List<BlockPos> diffBlocksPositionsClosest = new ArrayList<>();
    private final Set<MismatchType> selectedCategories = new HashSet<>();
    private final HashMultimap<MismatchType, BlockMismatch> selectedEntries = HashMultimap.create();
    private final Set<ChunkPos> requiredChunks = new HashSet<>();
    private final Set<BlockPos> recheckQueue = new HashSet<>();
    private final Minecraft mc = Minecraft.getInstance();
    private ClientLevel worldClient;
    private WorldSchematic worldSchematic;
    private SchematicPlacement schematicPlacement;
    private final List<MismatchRenderPos> mismatchPositionsForRender = new ArrayList<>();
    private final List<BlockPos> mismatchBlockPositionsForRender = new ArrayList<>();
    private SortCriteria sortCriteria = SortCriteria.NAME_EXPECTED;
    private boolean sortReverse;
    private boolean verificationStarted;
    private boolean verificationActive;
    private boolean shouldRenderInfoHud = true;
    private int totalRequiredChunks;
    private int schematicBlocks;
    private int clientBlocks;
    private int correctStatesCount;
    private IgnoreBlockRegistry ignoreBlockRegistry;
    private VerifierListRegistry verifierListRegistry;

    public SchematicVerifier()
    {
        this.name = StringUtils.translate("litematica.gui.label.schematic_verifier.verifier");
    }

    public static void clearActiveVerifiers()
    {
        ACTIVE_VERIFIERS.clear();
    }

    public static void markVerifierBlockChanges(BlockPos pos)
    {
	    for (SchematicVerifier activeVerifier : ACTIVE_VERIFIERS)
	    {
		    activeVerifier.markBlockChanged(pos);
	    }
    }

    public static void markVerifierEntityAdded(Entity entity)
    {
        for (SchematicVerifier activeVerifier : ACTIVE_VERIFIERS)
        {
            activeVerifier.onClientEntityAdded(entity);
        }
    }

    public static void markVerifierEntityRemoved(Entity entity)
    {
        for (SchematicVerifier activeVerifier : ACTIVE_VERIFIERS)
        {
            activeVerifier.onClientEntityRemoved(entity);
        }
    }

    /**
     * Whether the schematic world entity with the given UUID should be rendered
     * with the vanilla glow outline, because it is a currently selected missing entity
     * in one of the active verifiers.
     */
    public static boolean shouldHighlightSchematicEntity(UUID uuid)
    {
        for (SchematicVerifier activeVerifier : ACTIVE_VERIFIERS)
        {
            if (activeVerifier.entityHighlightUuids.contains(uuid))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Entities that move around on their own (mobs) or are transient (items, XP orbs,
     * projectiles) can't be meaningfully position-verified, so they are skipped.
     */
    public static boolean isVerifiableEntity(Entity entity)
    {
        if (entity instanceof LivingEntity && (entity instanceof ArmorStand) == false)
        {
            return false;
        }

        return (entity instanceof ItemEntity ||
                entity instanceof ExperienceOrb ||
                entity instanceof Projectile) == false;
    }

    @Override
    public boolean getShouldRenderText(RenderPhase phase)
    {
        return this.shouldRenderInfoHud && phase == RenderPhase.POST &&
               Configs.InfoOverlays.VERIFIER_OVERLAY_ENABLED.getBooleanValue();
    }

    public void toggleShouldRenderInfoHUD()
    {
        this.shouldRenderInfoHud = ! this.shouldRenderInfoHud;
    }

    public boolean isActive()
    {
        return this.verificationActive;
    }

    public boolean isPaused()
    {
        return this.verificationStarted && this.verificationActive == false && this.finished == false;
    }

    public boolean isFinished()
    {
        return this.finished;
    }

    public int getTotalChunks()
    {
        return this.totalRequiredChunks;
    }

    public int getUnseenChunks()
    {
        return this.requiredChunks.size();
    }

    /** The chunks that have not been seen loaded and verified yet. Do not modify. */
    public Set<ChunkPos> getRequiredChunks()
    {
        return this.requiredChunks;
    }

    public int getSchematicTotalBlocks()
    {
        return this.schematicBlocks;
    }

    public int getRealWorldTotalBlocks()
    {
        return this.clientBlocks;
    }

    public int getMissingBlocks()
    {
        return this.missingBlocksPositions.size();
    }

    public int getExtraBlocks()
    {
        return this.extraBlocksPositions.size();
    }

    public int getMismatchedBlocks()
    {
        return this.wrongBlocksPositions.size();
    }

    public int getMismatchedStates()
    {
        return this.wrongStatesPositions.size();
    }

    public int getDiffBlocks()
    {
        return this.diffBlocksPositions.size();
    }

    public int getMissingEntities()
    {
        return this.missingEntitiesPositions.size();
    }

    public int getCorrectStatesCount()
    {
        return this.correctStatesCount;
    }

    public int getTotalErrors()
    {
        return this.getMismatchedBlocks() +
                this.getMismatchedStates() +
                this.getExtraBlocks() +
                this.getMissingBlocks() +
                this.getDiffBlocks() +
                this.getMissingEntities();
    }

    public SortCriteria getSortCriteria()
    {
        return this.sortCriteria;
    }

    public boolean getSortInReverse()
    {
        return this.sortReverse;
    }

    public void setSortCriteria(SortCriteria criteria)
    {
        if (this.sortCriteria == criteria)
        {
            this.sortReverse = ! this.sortReverse;
        }
        else
        {
            this.sortCriteria = criteria;
            this.sortReverse = criteria != SortCriteria.COUNT;
        }
    }

    public void toggleMismatchCategorySelected(MismatchType type)
    {
        if (type == MismatchType.CORRECT_STATE)
        {
            return;
        }

        if (this.selectedCategories.contains(type))
        {
            this.selectedCategories.remove(type);
        }
        else
        {
            this.selectedCategories.add(type);

            // Remove any existing selected individual entries within this category
            this.removeSelectedEntriesOfType(type);
        }

        this.updateMismatchOverlays();
    }

    public void toggleMismatchEntrySelected(BlockMismatch mismatch)
    {
        MismatchType type = mismatch.mismatchType;

        if (this.selectedEntries.containsValue(mismatch))
        {
            this.selectedEntries.remove(type, mismatch);
        }
        else
        {
            this.selectedCategories.remove(type);
            this.selectedEntries.put(type, mismatch);
        }

        this.updateMismatchOverlays();
    }

    private void removeSelectedEntriesOfType(MismatchType type)
    {
        this.selectedEntries.removeAll(type);

        if (type == MismatchType.MISSING_ENTITY)
        {
            this.selectedEntityEntries.clear();
        }
    }

    public void toggleEntityMismatchEntrySelected(EntityMismatch mismatch)
    {
        if (this.selectedEntityEntries.contains(mismatch))
        {
            this.selectedEntityEntries.remove(mismatch);
        }
        else
        {
            this.selectedCategories.remove(MismatchType.MISSING_ENTITY);
            this.selectedEntityEntries.add(mismatch);
        }

        this.updateMismatchOverlays();
    }

    public boolean isEntityMismatchEntrySelected(EntityMismatch mismatch)
    {
        return this.selectedEntityEntries.contains(mismatch);
    }

    public boolean isMismatchCategorySelected(MismatchType type)
    {
        return this.selectedCategories.contains(type);
    }

    public boolean isMismatchEntrySelected(BlockMismatch mismatch)
    {
        return this.selectedEntries.containsValue(mismatch);
    }

    private void clearActiveMismatchRenderPositions()
    {
        this.mismatchPositionsForRender.clear();
        this.mismatchBlockPositionsForRender.clear();
        this.entityMismatchPositionsForRender.clear();
        this.entityHighlightUuids.clear();
        this.infoHudLines.clear();
    }

    public List<MismatchRenderPos> getSelectedMismatchPositionsForRender()
    {
        return this.mismatchPositionsForRender;
    }

    public List<BlockPos> getSelectedMismatchBlockPositionsForRender()
    {
        return this.mismatchBlockPositionsForRender;
    }

    @Override
    public boolean shouldRemove()
    {
        return this.canExecute() == false;
    }

    @Override
    public boolean execute(ProfilerFiller profiler)
    {
        this.verifyChunks(profiler);
        this.checkChangedPositions(profiler);
        return false;
    }

    @Override
    public void stop()
    {
        // This gets called when the task is removed from the scheduler,
        // for example when the user cancels it from the task manager GUI.
        // Clean up the verifier state so that the unseen chunks info HUD
        // entry doesn't linger around after the task is gone.
        // The scheduler is already removing this task, so this must not
        // call TaskScheduler.removeTask() (which would call stop() again).
        // Don't call notifyListeners
        this.stopVerification();
        this.clearReferences();
        this.clearState();
    }

    public void startVerification(ClientLevel worldClient, WorldSchematic worldSchematic,
            SchematicPlacement schematicPlacement, ICompletionListener completionListener)
    {
        this.reset();

        this.worldClient = worldClient;
        this.worldSchematic = worldSchematic;
        this.schematicPlacement = schematicPlacement;
        this.ignoreBlockRegistry = new IgnoreBlockRegistry();
        this.verifierListRegistry = new VerifierListRegistry();

        this.setCompletionListener(completionListener);
        this.requiredChunks.addAll(schematicPlacement.getTouchedChunks(SubRegionPlacement.RequiredEnabled.RENDERING_ENABLED));
        this.totalRequiredChunks = this.requiredChunks.size();
        this.verificationStarted = true;

        TaskScheduler.getInstanceClient().scheduleTask(this, 10);
        InfoHud.getInstance().addInfoHudRenderer(this, true);
        ACTIVE_VERIFIERS.add(this);

        this.verificationActive = true;

        this.updateRequiredChunksStringList();
    }

    public void resume()
    {
        if (this.verificationStarted)
        {
            this.verificationActive = true;
            this.updateRequiredChunksStringList();
        }
    }

    public void stopVerification()
    {
        this.verificationActive = false;
    }

    public void reset()
    {
        this.stopVerification();
        this.clearReferences();
        this.clearData();
    }

    private void clearReferences()
    {
        this.worldClient = null;
        this.worldSchematic = null;
        this.schematicPlacement = null;
    }

    private void clearData()
    {
        this.clearState();
        TaskScheduler.getInstanceClient().removeTask(this);
    }

    private void clearState()
    {
        this.verificationActive = false;
        this.verificationStarted = false;
        this.finished = false;
        this.totalRequiredChunks = 0;
        this.correctStatesCount = 0;
        this.schematicBlocks = 0;
        this.clientBlocks = 0;
        this.requiredChunks.clear();
        this.recheckQueue.clear();

        this.missingBlocksPositions.clear();
        this.diffBlocksPositions.clear();
        this.extraBlocksPositions.clear();
        this.wrongBlocksPositions.clear();
        this.wrongStatesPositions.clear();
        this.blockMismatches.clear();
        this.correctStateCounts.clear();
        this.selectedCategories.clear();
        this.selectedEntries.clear();
        this.mismatchBlockPositionsForRender.clear();
        this.mismatchPositionsForRender.clear();
        this.missingEntitiesPositions.clear();
        this.entityMismatchStacks.clear();
        this.usedClientEntityUuids.clear();
        this.matchedClientEntities.clear();
        this.selectedEntityEntries.clear();
        this.entityHighlightUuids.clear();
        this.entityMismatchPositionsForRender.clear();

        ACTIVE_VERIFIERS.remove(this);

        InfoHud.getInstance().removeInfoHudRenderer(this, false);
        this.clearActiveMismatchRenderPositions();
    }

    public void markBlockChanged(BlockPos pos)
    {
        if (this.finished)
        {
            BlockMismatch mismatch = this.blockMismatches.get(pos);

            if (mismatch != null)
            {
                this.recheckQueue.add(pos.immutable());
            }
        }
    }

    private void checkChangedPositions(ProfilerFiller profiler)
    {
        profiler.push("verify_check_pos");
        if (this.finished && this.recheckQueue.isEmpty() == false)
        {
            Iterator<BlockPos> iter = this.recheckQueue.iterator();

            while (iter.hasNext())
            {
                BlockPos pos = iter.next();
                @SuppressWarnings("deprecation")
                boolean isLoadedClient = this.worldClient.hasChunkAt(pos);
                @SuppressWarnings("deprecation")
                boolean isLoadedSchematic = this.worldSchematic.hasChunkAt(pos);

                if (isLoadedClient && isLoadedSchematic)
                {
                    BlockMismatch mismatch = this.blockMismatches.get(pos);

                    if (mismatch != null)
                    {
                        this.blockMismatches.remove(pos);

                        BlockState stateFound = this.worldClient.getBlockState(pos);
                        MUTABLE_PAIR.setLeft(mismatch.stateExpected);
                        MUTABLE_PAIR.setRight(mismatch.stateFound);

                        this.getMapForMismatchType(mismatch.mismatchType).remove(MUTABLE_PAIR, pos);
                        this.checkBlockStates(pos.getX(), pos.getY(), pos.getZ(), mismatch.stateExpected, stateFound);

                        if (stateFound.isAir() == false && mismatch.stateFound.isAir())
                        {
                            this.clientBlocks++;
                        }
                    }
                    else
                    {
                        BlockState stateExpected = this.worldSchematic.getBlockState(pos);
                        BlockState stateFound = this.worldClient.getBlockState(pos);
                        this.checkBlockStates(pos.getX(), pos.getY(), pos.getZ(), stateExpected, stateFound);
                    }

                    iter.remove();
                }
            }

            if (this.recheckQueue.isEmpty())
            {
                this.updateMismatchOverlays();
            }
        }

        profiler.pop();
    }

    private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> getMapForMismatchType(MismatchType mismatchType)
    {
        return switch (mismatchType)
        {
            case MISSING -> this.missingBlocksPositions;
            case EXTRA -> this.extraBlocksPositions;
            case WRONG_BLOCK -> this.wrongBlocksPositions;
            case WRONG_STATE -> this.wrongStatesPositions;
            case DIFF_BLOCK -> this.diffBlocksPositions;
            default -> null;
        };
    }

    private boolean verifyChunks(ProfilerFiller profiler)
    {
        profiler.push("verify_chunks");
        if (this.verificationActive)
        {
            Iterator<ChunkPos> iter = this.requiredChunks.iterator();
            boolean checkedSome = false;

            while (iter.hasNext())
            {
                if ((System.nanoTime() - DataManager.getClientTickStartTime()) >= 50000000L)
                {
                    break;
                }

                ChunkPos pos = iter.next();
                int count = 0;

                for (int cx = pos.x() - 1; cx <= pos.x() + 1; ++cx)
                {
                    for (int cz = pos.z() - 1; cz <= pos.z() + 1; ++cz)
                    {
                        if (WorldUtils.isClientChunkLoaded(this.worldClient, cx, cz))
                        {
                            ++count;
                        }
                    }
                }

                // Require the surrounding chunks in the client world to be loaded as well
                if (count == 9 && this.worldSchematic.getChunkSource().hasChunk(pos.x(), pos.z()))
                {
                    ChunkAccess chunkClient = this.worldClient.getChunk(pos.x(), pos.z());
                    ChunkAccess chunkSchematic = this.worldSchematic.getChunk(pos.x(), pos.z());
                    Map<String, IntBoundingBox> boxes = this.schematicPlacement.getBoxesWithinChunk(pos.x(), pos.z(), SubRegionPlacement.RequiredEnabled.RENDERING_ENABLED);

                    for (IntBoundingBox box : boxes.values())
                    {
                        this.verifyChunk(chunkClient, chunkSchematic, box);
                    }

                    iter.remove();
                    checkedSome = true;
                }
            }

            if (checkedSome)
            {
                this.updateRequiredChunksStringList();
            }

            if (this.requiredChunks.isEmpty())
            {
                this.verificationActive = false;
                this.verificationStarted = false;
                this.finished = true;

                this.notifyListener();
            }
        }

        profiler.pop();
        return this.verificationActive == false; // finished or stopped
    }

    public void ignoreStateMismatch(BlockMismatch mismatch)
    {
        this.ignoreStateMismatch(mismatch, true);
    }

    private void ignoreStateMismatch(BlockMismatch mismatch, boolean updateOverlay)
    {
        Pair<BlockState, BlockState> ignore = Pair.of(mismatch.stateExpected, mismatch.stateFound);

        if (this.ignoredMismatches.contains(ignore) == false)
        {
            this.ignoredMismatches.add(ignore);
            this.getMapForMismatchType(mismatch.mismatchType).removeAll(ignore);
            this.blockMismatches.entrySet().removeIf(entry -> entry.getValue().equals(mismatch));
        }

        if (updateOverlay)
        {
            this.updateMismatchOverlays();
        }
    }

    public void addIgnoredStateMismatches(Collection<BlockMismatch> ignore)
    {
        for (BlockMismatch mismatch : ignore)
        {
            this.ignoreStateMismatch(mismatch, false);
        }

        this.updateMismatchOverlays();
    }

    public void resetIgnoredStateMismatches()
    {
        this.ignoredMismatches.clear();
    }

    public Set<Pair<BlockState, BlockState>> getIgnoredMismatches()
    {
        return this.ignoredMismatches;
    }

    public Object2IntOpenHashMap<BlockState> getCorrectStates()
    {
        return this.correctStateCounts;
    }

    @Nullable
    public BlockMismatch getMismatchForPosition(BlockPos pos)
    {
        return this.blockMismatches.get(pos);
    }

    public List<BlockMismatch> getMismatchOverviewFor(MismatchType type)
    {
        List<BlockMismatch> list = new ArrayList<>();

        if (type == MismatchType.ALL)
        {
            return this.getMismatchOverviewCombined();
        }
        else
        {
            this.addCountFor(type, this.getMapForMismatchType(type), list);
        }

        return list;
    }

    public List<BlockMismatch> getMismatchOverviewCombined()
    {
        List<BlockMismatch> list = new ArrayList<>();

        this.addCountFor(MismatchType.MISSING, this.missingBlocksPositions, list);
        this.addCountFor(MismatchType.EXTRA, this.extraBlocksPositions, list);
        this.addCountFor(MismatchType.WRONG_BLOCK, this.wrongBlocksPositions, list);
        this.addCountFor(MismatchType.WRONG_STATE, this.wrongStatesPositions, list);
        this.addCountFor(MismatchType.DIFF_BLOCK, this.diffBlocksPositions, list);

        Collections.sort(list);

        return list;
    }

    private void addCountFor(MismatchType mismatchType, ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> map, List<BlockMismatch> list)
    {
        for (Pair<BlockState, BlockState> pair : map.keySet())
        {
            list.add(new BlockMismatch(mismatchType, pair.getLeft(), pair.getRight(), map.get(pair).size()));
        }
    }

    public List<EntityMismatch> getEntityMismatchOverview()
    {
        List<EntityMismatch> list = new ArrayList<>();

        for (EntityType<?> type : this.missingEntitiesPositions.keySet())
        {
            ItemStack stack = this.entityMismatchStacks.getOrDefault(type, ItemStack.EMPTY);
            list.add(new EntityMismatch(MismatchType.MISSING_ENTITY, type, stack, this.missingEntitiesPositions.get(type).size()));
        }

        return list;
    }

    public List<Pair<BlockState, BlockState>> getIgnoredStateMismatchPairs(GuiBase gui)
    {
        List<Pair<BlockState, BlockState>> list = Lists.newArrayList(this.ignoredMismatches);

        try
        {
            list.sort((o1, o2) -> {
                String name1 = BuiltInRegistries.BLOCK.getKey(o1.getLeft().getBlock()).toString();
                String name2 = BuiltInRegistries.BLOCK.getKey(o2.getLeft().getBlock()).toString();

                int val = name1.compareTo(name2);

                if (val < 0)
                {
                    return -1;
                }
                else if (val > 0)
                {
                    return 1;
                }
                else
                {
                    name1 = BuiltInRegistries.BLOCK.getKey(o1.getRight().getBlock()).toString();
                    name2 = BuiltInRegistries.BLOCK.getKey(o2.getRight().getBlock()).toString();

                    return name1.compareTo(name2);
                }
            });
        }
        catch (Exception e)
        {
            gui.addMessage(MessageType.ERROR, "litematica.error.generic.failed_to_sort_list_of_ignored_states");
        }

        return list;
    }

    private boolean verifyChunk(ChunkAccess chunkClient, ChunkAccess chunkSchematic, IntBoundingBox box)
    {
        LayerRange range = DataManager.getRenderLayerRange();
        Direction.Axis axis = range.getAxis();
        boolean ranged = this.schematicPlacement.getSchematicVerifierType() == BlockInfoListType.RENDER_LAYERS;

        final int startX = ranged && axis == Direction.Axis.X ? Math.max(box.minX(), range.getMinLayerBoundary()) : box.minX();
        final int startY = ranged && axis == Direction.Axis.Y ? Math.max(box.minY(), range.getMinLayerBoundary()) : box.minY();
        final int startZ = ranged && axis == Direction.Axis.Z ? Math.max(box.minZ(), range.getMinLayerBoundary()) : box.minZ();
        final int endX = ranged && axis == Direction.Axis.X ? Math.min(box.maxX(), range.getMaxLayerBoundary()) : box.maxX();
        final int endY = ranged && axis == Direction.Axis.Y ? Math.min(box.maxY(), range.getMaxLayerBoundary()) : box.maxY();
        final int endZ = ranged && axis == Direction.Axis.Z ? Math.min(box.maxZ(), range.getMaxLayerBoundary()) : box.maxZ();

        for (int y = startY; y <= endY; ++y)
        {
            for (int z = startZ; z <= endZ; ++z)
            {
                for (int x = startX; x <= endX; ++x)
                {
                    MUTABLE_POS.set(x, y, z);
                    BlockState stateClient = chunkClient.getBlockState(MUTABLE_POS);
                    BlockState stateSchematic = chunkSchematic.getBlockState(MUTABLE_POS);

                    this.checkBlockStates(x, y, z, stateSchematic, stateClient);

                    if (stateSchematic.isAir() == false)
                    {
                        this.schematicBlocks++;
                    }

                    if (stateClient.isAir() == false)
                    {
                        this.clientBlocks++;
                    }
                }
            }
        }

        this.verifyEntitiesInBox(chunkSchematic.getPos(), startX, startY, startZ, endX, endY, endZ);

        return true;
    }

    private void verifyEntitiesInBox(ChunkPos chunkPos, int startX, int startY, int startZ, int endX, int endY, int endZ)
    {
        List<Entity> schematicEntities = this.worldSchematic.getEntitiesByChunk(chunkPos.x, chunkPos.z, SchematicVerifier::isVerifiableEntity);
        final double tolerance = Configs.Generic.VERIFIER_ENTITY_TOLERANCE.getDoubleValue();

        for (Entity schematicEntity : schematicEntities)
        {
            int x = (int) Math.floor(schematicEntity.getX());
            int y = (int) Math.floor(schematicEntity.getY());
            int z = (int) Math.floor(schematicEntity.getZ());

            if (x < startX || x > endX || y < startY || y > endY || z < startZ || z > endZ)
            {
                continue;
            }

            MissingEntityEntry entry = new MissingEntityEntry(schematicEntity.getType(), schematicEntity.getUUID(), schematicEntity.position());
            this.cacheEntityMismatchStack(schematicEntity);
            Entity match = this.findMatchingClientEntity(schematicEntity.getType(), schematicEntity.position(), tolerance);

            if (match != null)
            {
                this.usedClientEntityUuids.add(match.getUUID());
                this.matchedClientEntities.put(match.getUUID(), entry);
            }
            else
            {
                this.missingEntitiesPositions.put(schematicEntity.getType(), entry);
            }
        }
    }

    /**
     * Finds the closest not-yet-matched client world entity of the given type
     * within the position tolerance. Already matched entities are tracked by their UUID,
     * so overlapping entities (e.g. two minecarts in the same spot) each require
     * their own counterpart in the client world.
     */
    @Nullable
    private Entity findMatchingClientEntity(EntityType<?> type, Vec3 pos, double tolerance)
    {
        AABB searchBox = new AABB(pos, pos).inflate(Math.max(tolerance, 0.001));
        List<Entity> candidates = this.worldClient.getEntities((Entity) null, searchBox,
                e -> e.getType() == type &&
                     isVerifiableEntity(e) &&
                     this.usedClientEntityUuids.contains(e.getUUID()) == false &&
                     e.position().distanceTo(pos) <= tolerance);

        Entity closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (Entity candidate : candidates)
        {
            double distSq = candidate.position().distanceToSqr(pos);

            if (distSq < closestDistSq)
            {
                closestDistSq = distSq;
                closest = candidate;
            }
        }

        return closest;
    }

    private void cacheEntityMismatchStack(Entity schematicEntity)
    {
        EntityType<?> type = schematicEntity.getType();

        if (this.entityMismatchStacks.containsKey(type) == false)
        {
            ItemStack stack = ItemStack.EMPTY;

            try
            {
                stack = schematicEntity.getPickResult();
            }
            catch (Exception ignored) { }

            if (stack == null || stack.isEmpty())
            {
                SpawnEggItem egg = SpawnEggItem.byId(type);
                stack = egg != null ? new ItemStack(egg) : ItemStack.EMPTY;
            }

            this.entityMismatchStacks.put(type, stack);
        }
    }

    private void onClientEntityAdded(Entity entity)
    {
        if (this.finished == false || isVerifiableEntity(entity) == false ||
            this.usedClientEntityUuids.contains(entity.getUUID()))
        {
            return;
        }

        final double tolerance = Configs.Generic.VERIFIER_ENTITY_TOLERANCE.getDoubleValue();
        List<MissingEntityEntry> entries = this.missingEntitiesPositions.get(entity.getType());
        MissingEntityEntry closest = null;
        double closestDistSq = Double.MAX_VALUE;

        for (MissingEntityEntry entry : entries)
        {
            double distSq = entry.pos.distanceToSqr(entity.position());

            if (distSq <= tolerance * tolerance && distSq < closestDistSq)
            {
                closestDistSq = distSq;
                closest = entry;
            }
        }

        if (closest != null)
        {
            this.missingEntitiesPositions.remove(entity.getType(), closest);
            this.usedClientEntityUuids.add(entity.getUUID());
            this.matchedClientEntities.put(entity.getUUID(), closest);
            this.updateMismatchOverlays();
        }
    }

    private void onClientEntityRemoved(Entity entity)
    {
        if (this.finished == false)
        {
            return;
        }

        MissingEntityEntry entry = this.matchedClientEntities.remove(entity.getUUID());

        if (entry != null)
        {
            this.usedClientEntityUuids.remove(entity.getUUID());
            this.missingEntitiesPositions.put(entry.entityType, entry);
            this.updateMismatchOverlays();
        }
    }

    private void checkBlockStates(int x, int y, int z, BlockState stateSchematic, BlockState stateClient)
    {
        BlockPos pos = new BlockPos(x, y, z);

        if (stateClient != stateSchematic && (stateClient.isAir() == false || stateSchematic.isAir() == false))
        {
            // Blocks excluded by the verifier black-/whitelist are left out of the results
            // entirely, the same way as mismatch pairs ignored from the verifier GUI
            if (this.verifierListRegistry.isPositionIgnored(stateSchematic, stateClient))
            {
                return;
            }

            // If the states only differ in properties excluded from the comparison by the
            // black-/whitelist, or only in the crop age while that is ignored, count the
            // position as correct
            if (this.verifierListRegistry.shouldTreatAsCorrect(stateSchematic, stateClient) ||
                (Configs.Visuals.IGNORE_CROP_AGE.getBooleanValue() &&
                 BlockUtils.areStatesEqualIgnoringAge(stateSchematic, stateClient)))
            {
                ItemUtils.setItemForBlock(this.worldClient, pos, stateClient);
                this.correctStateCounts.addTo(stateClient, 1);

                if (stateSchematic.isAir() == false)
                {
                    ++this.correctStatesCount;
                }

                return;
            }

            MUTABLE_PAIR.setLeft(stateSchematic);
            MUTABLE_PAIR.setRight(stateClient);

            if (this.ignoredMismatches.contains(MUTABLE_PAIR) == false)
            {
                BlockMismatch mismatch = null;

                if (stateSchematic.isAir() == false)
                {
                    if (stateClient.isAir())
                    {
                        mismatch = new BlockMismatch(MismatchType.MISSING, stateSchematic, stateClient, 1);
                        this.missingBlocksPositions.put(Pair.of(stateSchematic, stateClient), pos);
                    }
                    else
                    {
                        if (stateSchematic.getBlock() != stateClient.getBlock())
                        {
							// FIXME TODO
                            if (Configs.Generic.ENABLE_DIFFERENT_BLOCKS.getBooleanValue() &&
                                fi.dy.masa.malilib.util.game.BlockUtils.isInSameGroup(stateSchematic, stateClient))
                            {
                                if (fi.dy.masa.malilib.util.game.BlockUtils.matchPropertiesOnly(stateSchematic, stateClient))
                                {
                                    mismatch = new BlockMismatch(MismatchType.DIFF_BLOCK, stateSchematic, stateClient, 1);
                                    this.diffBlocksPositions.put(Pair.of(stateSchematic, stateClient), pos);
                                }
                                else
                                {
                                    mismatch = new BlockMismatch(MismatchType.WRONG_STATE, stateSchematic, stateClient, 1);
                                    this.wrongStatesPositions.put(Pair.of(stateSchematic, stateClient), pos);
                                }
                            }
                            else
                            {
                                mismatch = new BlockMismatch(MismatchType.WRONG_BLOCK, stateSchematic, stateClient, 1);
                                this.wrongBlocksPositions.put(Pair.of(stateSchematic, stateClient), pos);
                            }
                        }
                        else
                        {
                            mismatch = new BlockMismatch(MismatchType.WRONG_STATE, stateSchematic, stateClient, 1);
                            this.wrongStatesPositions.put(Pair.of(stateSchematic, stateClient), pos);
                        }
                    }
                }
                else if ((Configs.Visuals.IGNORE_EXISTING_FLUIDS.getBooleanValue() == false || stateClient.liquid() == false) &&
                    !ignoreBlockRegistry.hasBlock(stateClient.getBlock()))
                {
                    mismatch = new BlockMismatch(MismatchType.EXTRA, stateSchematic, stateClient, 1);
                    this.extraBlocksPositions.put(Pair.of(stateSchematic, stateClient), pos);
                }

                if (mismatch != null)
                {
                    this.blockMismatches.put(pos, mismatch);

                    ItemUtils.setItemForBlock(this.worldClient, pos, stateClient);
                    ItemUtils.setItemForBlock(this.worldSchematic, pos, stateSchematic);
                }
            }
        }
        else
        {
            ItemUtils.setItemForBlock(this.worldClient, pos, stateClient);
            this.correctStateCounts.addTo(stateClient, 1);

            if (stateSchematic.isAir() == false)
            {
                ++this.correctStatesCount;
            }
        }
    }

    private void updateMismatchOverlays()
    {
        if (this.mc.player != null)
        {
            int maxEntries = Configs.InfoOverlays.VERIFIER_ERROR_HILIGHT_MAX_POSITIONS.getIntegerValue();

            // This needs to happen first
            BlockPos centerPos = BlockPos.containing(this.mc.player.position());
            this.updateClosestPositions(centerPos, maxEntries);
            this.combineClosestPositions(centerPos, maxEntries);
            this.updateSelectedEntityHighlights(centerPos);

            List<MismatchRenderPos> hudList = this.mismatchPositionsForRender;

            // The missing entities are not rendered as boxes, but they should still
            // show up in the Info HUD position list
            if (this.entityMismatchPositionsForRender.isEmpty() == false)
            {
                List<MismatchRenderPos> combined = new ArrayList<>(this.mismatchPositionsForRender);
                combined.addAll(this.entityMismatchPositionsForRender);
                combined.sort(new RenderPosComparator(centerPos, true));

                if (combined.size() > maxEntries)
                {
                    combined = combined.subList(0, maxEntries);
                }

                hudList = combined;
            }

            // Only one category selected, show the title
            if (this.selectedCategories.size() == 1 && this.selectedEntries.size() == 0 && this.selectedEntityEntries.isEmpty())
            {
                MismatchType type = hudList.size() > 0 ? hudList.get(0).type : null;
                this.updateMismatchPositionStringList(type, hudList);
            }
            else
            {
                this.updateMismatchPositionStringList(null, hudList);
            }
        }
    }

    private void updateSelectedEntityHighlights(BlockPos centerPos)
    {
        this.entityHighlightUuids.clear();
        this.entityMismatchPositionsForRender.clear();

        List<MissingEntityEntry> entries;

        if (this.selectedCategories.contains(MismatchType.MISSING_ENTITY))
        {
            entries = new ArrayList<>(this.missingEntitiesPositions.values());
        }
        else
        {
            entries = new ArrayList<>();

            for (EntityMismatch mismatch : this.selectedEntityEntries)
            {
                entries.addAll(this.missingEntitiesPositions.get(mismatch.entityType));
            }
        }

        for (MissingEntityEntry entry : entries)
        {
            this.entityHighlightUuids.add(entry.schematicEntityUuid);
            this.entityMismatchPositionsForRender.add(new MismatchRenderPos(MismatchType.MISSING_ENTITY, BlockPos.containing(entry.pos)));
        }

        this.entityMismatchPositionsForRender.sort(new RenderPosComparator(centerPos, true));
    }

    private void updateClosestPositions(BlockPos centerPos, int maxEntries)
    {
        PositionUtils.BLOCK_POS_COMPARATOR.setReferencePosition(centerPos);
        PositionUtils.BLOCK_POS_COMPARATOR.setClosestFirst(true);

        this.addAndSortPositions(MismatchType.DIFF_BLOCK,   this.diffBlocksPositions, this.diffBlocksPositionsClosest, maxEntries);
        this.addAndSortPositions(MismatchType.WRONG_BLOCK,  this.wrongBlocksPositions, this.mismatchedBlocksPositionsClosest, maxEntries);
        this.addAndSortPositions(MismatchType.WRONG_STATE,  this.wrongStatesPositions, this.mismatchedStatesPositionsClosest, maxEntries);
        this.addAndSortPositions(MismatchType.EXTRA,        this.extraBlocksPositions, this.extraBlocksPositionsClosest, maxEntries);
        this.addAndSortPositions(MismatchType.MISSING,      this.missingBlocksPositions, this.missingBlocksPositionsClosest, maxEntries);
    }

    private void addAndSortPositions(MismatchType type,
            ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> sourceMap,
            List<BlockPos> listOut, int maxEntries)
    {
        listOut.clear();

        //List<BlockPos> tempList = new ArrayList<>();

        if (this.selectedCategories.contains(type))
        {
            listOut.addAll(sourceMap.values());
        }
        else
        {
            Collection<BlockMismatch> mismatches = this.selectedEntries.get(type);

            for (BlockMismatch mismatch : mismatches)
            {
                MUTABLE_PAIR.setLeft(mismatch.stateExpected);
                MUTABLE_PAIR.setRight(mismatch.stateFound);
                listOut.addAll(sourceMap.get(MUTABLE_PAIR));
            }
        }

        listOut.sort(PositionUtils.BLOCK_POS_COMPARATOR);

        /*
        final int max = Math.min(maxEntries, tempList.size());

        for (int i = 0; i < max; ++i)
        {
            listOut.add(tempList.get(i));
        }
        */
    }

    private void combineClosestPositions(BlockPos centerPos, int maxEntries)
    {
        this.mismatchPositionsForRender.clear();
        this.mismatchBlockPositionsForRender.clear();

        List<MismatchRenderPos> tempList = new ArrayList<>();

        this.getMismatchRenderPositionFor(MismatchType.WRONG_BLOCK, tempList);
        this.getMismatchRenderPositionFor(MismatchType.DIFF_BLOCK, tempList);
        this.getMismatchRenderPositionFor(MismatchType.WRONG_STATE, tempList);
        this.getMismatchRenderPositionFor(MismatchType.EXTRA, tempList);
        this.getMismatchRenderPositionFor(MismatchType.MISSING, tempList);

        tempList.sort(new RenderPosComparator(centerPos, true));

        final int max = Math.min(maxEntries, tempList.size());

        for (int i = 0; i < max; ++i)
        {
            MismatchRenderPos entry = tempList.get(i);
            this.mismatchPositionsForRender.add(entry);
            this.mismatchBlockPositionsForRender.add(entry.pos);
        }
    }

    private void getMismatchRenderPositionFor(MismatchType type, List<MismatchRenderPos> listOut)
    {
        List<BlockPos> list = this.getClosestMismatchedPositionsFor(type);

        for (BlockPos pos : list)
        {
            listOut.add(new MismatchRenderPos(type, pos));
        }
    }

    private List<BlockPos> getClosestMismatchedPositionsFor(MismatchType type)
    {
        return switch (type)
        {
            case MISSING -> this.missingBlocksPositionsClosest;
            case EXTRA -> this.extraBlocksPositionsClosest;
            case WRONG_BLOCK -> this.mismatchedBlocksPositionsClosest;
            case WRONG_STATE -> this.mismatchedStatesPositionsClosest;
            case DIFF_BLOCK -> this.diffBlocksPositionsClosest;
            default -> Collections.emptyList();
        };
    }

    private void updateMismatchPositionStringList(@Nullable MismatchType mismatchType, List<MismatchRenderPos> positionList)
    {
        this.infoHudLines.clear();

        if (positionList.isEmpty() == false)
        {
            String rst = GuiBase.TXT_RST;

            if (mismatchType != null)
            {
                this.infoHudLines.add(String.format("%s%s%s", mismatchType.getFormattingCode(), mismatchType.getDisplayname(), rst));
            }
            else
            {
                String title = StringUtils.translate("litematica.gui.title.schematic_verifier_errors");
                this.infoHudLines.add(String.format("%s%s%s", GuiBase.TXT_BOLD, title, rst));
            }

            final int count = Math.min(positionList.size(), Configs.InfoOverlays.INFO_HUD_MAX_LINES.getIntegerValue());

            for (int i = 0; i < count; ++i)
            {
                MismatchRenderPos entry = positionList.get(i);
                BlockPos pos = entry.pos;
                String pre = entry.type.getColorCode();
                this.infoHudLines.add(String.format("%sx: %5d, y: %3d, z: %5d%s", pre, pos.getX(), pos.getY(), pos.getZ(), rst));
            }
        }
    }

    public void updateRequiredChunksStringList()
    {
        this.updateInfoHudLinesPendingChunks(this.requiredChunks);
    }

    /**
     * Prepares/caches the strings, and returns a provider for the data.<br>
     * <b>NOTE:</b> This is actually the instance of this class, there are no separate providers for different data types atm!
     */
    /*
    public IInfoHudRenderer getClosestMismatchedPositionListProviderFor(MismatchType type)
    {
        return this;
    }
    */

    public record BlockMismatch(MismatchType mismatchType, BlockState stateExpected, BlockState stateFound, int count)
        implements Comparable<BlockMismatch>
    {
        @Override
        public int compareTo(BlockMismatch other)
        {
            return this.count > other.count ? -1 : (this.count < other.count ? 1 : 0);
        }

        @Override
        public int hashCode()
        {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((this.mismatchType == null) ? 0 : this.mismatchType.hashCode());
            result = prime * result + ((this.stateExpected == null) ? 0 : this.stateExpected.hashCode());
            result = prime * result + ((this.stateFound == null) ? 0 : this.stateFound.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) { return true; }
            if (obj == null) { return false; }
            if (getClass() != obj.getClass()) { return false; }

            BlockMismatch other = (BlockMismatch) obj;

            if (this.mismatchType != other.mismatchType) { return false; }
            if (this.stateExpected == null)
            {
                if (other.stateExpected != null) { return false; }
            }
            else if (this.stateExpected != other.stateExpected) { return false; }
            if (this.stateFound == null)
            {
	            return other.stateFound == null;
            }
            else
            {
                return this.stateFound == other.stateFound;
            }
        }
    }

    /**
     * A per-EntityType summary row of missing entities, for the verifier GUI list.
     */
    public static class EntityMismatch implements Comparable<EntityMismatch>
    {
        public final MismatchType mismatchType;
        public final EntityType<?> entityType;
        public final ItemStack stack;
        public final int count;

        public EntityMismatch(MismatchType mismatchType, EntityType<?> entityType, ItemStack stack, int count)
        {
            this.mismatchType = mismatchType;
            this.entityType = entityType;
            this.stack = stack;
            this.count = count;
        }

        public String getDisplayName()
        {
            return this.entityType.getDescription().getString();
        }

        @Override
        public int compareTo(EntityMismatch other)
        {
            return this.count > other.count ? -1 : (this.count < other.count ? 1 : 0);
        }

        @Override
        public int hashCode()
        {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((mismatchType == null) ? 0 : mismatchType.hashCode());
            result = prime * result + ((entityType == null) ? 0 : entityType.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            EntityMismatch other = (EntityMismatch) obj;
            return this.mismatchType == other.mismatchType && this.entityType == other.entityType;
        }
    }

    /**
     * One individual schematic entity that has no matching client world entity (yet).
     */
    public static class MissingEntityEntry
    {
        public final EntityType<?> entityType;
        public final UUID schematicEntityUuid;
        public final Vec3 pos;

        public MissingEntityEntry(EntityType<?> entityType, UUID schematicEntityUuid, Vec3 pos)
        {
            this.entityType = entityType;
            this.schematicEntityUuid = schematicEntityUuid;
            this.pos = pos;
        }
    }

    public record MismatchRenderPos(MismatchType type, BlockPos pos)
    { }

    private record RenderPosComparator(BlockPos posReference, boolean closestFirst)
            implements Comparator<MismatchRenderPos>
    {
        @Override
        public int compare(MismatchRenderPos pos1, MismatchRenderPos pos2)
        {
            double dist1 = pos1.pos.distSqr(this.posReference);
            double dist2 = pos2.pos.distSqr(this.posReference);

            if (dist1 == dist2)
            {
                return 0;
            }

            return dist1 < dist2 == this.closestFirst ? -1 : 1;
        }
    }

    public enum MismatchType
    {
        ALL             (0xFF0000, "litematica.gui.label.schematic_verifier_display_type.all", GuiBase.TXT_WHITE),
        MISSING         (0x00FFFF, "litematica.gui.label.schematic_verifier_display_type.missing", GuiBase.TXT_AQUA),
        MISSING_ENTITY  (0xAA00AA, "litematica.gui.label.schematic_verifier_display_type.missing_entities", GuiBase.TXT_DARK_PURPLE),
        EXTRA           (0xFF00CF, "litematica.gui.label.schematic_verifier_display_type.extra", GuiBase.TXT_LIGHT_PURPLE),
        WRONG_BLOCK     (0xFF0000, "litematica.gui.label.schematic_verifier_display_type.wrong_blocks", GuiBase.TXT_RED),
        WRONG_STATE     (0xFFAF00, "litematica.gui.label.schematic_verifier_display_type.wrong_state", GuiBase.TXT_GOLD),
        CORRECT_STATE   (0x11FF11, "litematica.gui.label.schematic_verifier_display_type.correct_state", GuiBase.TXT_GREEN),
        DIFF_BLOCK      (0xFAF000, "litematica.gui.label.schematic_verifier_display_type.diff_blocks", GuiBase.TXT_YELLOW);

        private final String unlocName;
        private final String colorCode;
        private final Color4f color;

        MismatchType(int color, String unlocName, String colorCode)
        {
            this.color = Color4f.fromColor(color, 1f);
            this.unlocName = unlocName;
            this.colorCode = colorCode;
        }

        public Color4f getColor()
        {
            return this.color;
        }

        public String getDisplayname()
        {
            return StringUtils.translate(this.unlocName);
        }

        public String getColorCode()
        {
            return this.colorCode;
        }

        public String getFormattingCode()
        {
            return this.colorCode + GuiBase.TXT_BOLD;
        }
    }

    public enum SortCriteria
    {
        NAME_EXPECTED,
        NAME_FOUND,
        COUNT;
    }
}
