package fi.dy.masa.litematica.data;

import javax.annotation.Nullable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Binary compatibility shim for external addon mods (e.g. TechUtils) that were
 * compiled against the old {@code EntitiesDataStorage} class, which was renamed
 * to {@link EntityDataManager} upstream. Without this class those mods throw a
 * {@link NoClassDefFoundError} from their mixin handlers (for example inside
 * SchematicVerifier#clearData()), which silently breaks features like starting
 * the Schematic Verifier.
 *
 * All methods delegate to the {@link EntityDataManager} singleton, so the state
 * is shared with the rest of the mod. Only the members that external mods are
 * known to reference are provided here.
 *
 * @deprecated Use {@link EntityDataManager} instead.
 */
@Deprecated
public class EntitiesDataStorage
{
    private static final EntitiesDataStorage INSTANCE = new EntitiesDataStorage();

    public static EntitiesDataStorage getInstance()
    {
        return INSTANCE;
    }

    private EntitiesDataStorage()
    {
    }

    @Nullable
    public Level getWorld()
    {
        return EntityDataManager.getInstance().getBestWorld();
    }

    public ClientLevel getClientWorld()
    {
        return EntityDataManager.getInstance().getClientWorld();
    }

    public void reset(boolean isLogout)
    {
        EntityDataManager.getInstance().reset(isLogout);
    }

    public boolean hasServuxServer()
    {
        return EntityDataManager.getInstance().hasServuxServer();
    }

    public boolean getIfReceivedBackupPackets()
    {
        return EntityDataManager.getInstance().getIfReceivedBackupPackets();
    }

    public boolean hasPendingChunk(ChunkPos pos)
    {
        return EntityDataManager.getInstance().hasPendingChunk(pos);
    }

    public boolean hasCompletedChunk(ChunkPos pos)
    {
        return EntityDataManager.getInstance().hasCompletedChunk(pos);
    }

    public void requestServuxBulkEntityData(ChunkPos chunkPos, int minY, int maxY)
    {
        EntityDataManager.getInstance().requestServuxBulkEntityData(chunkPos, minY, maxY);
    }

    public void requestBackupBulkEntityData(ChunkPos chunkPos, int minY, int maxY)
    {
        EntityDataManager.getInstance().requestBackupBulkEntityData(chunkPos, minY, maxY);
    }
}
