package fi.dy.masa.litematica.materials;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.network.task.ServerMaterialListSession;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class MaterialListPlacement extends MaterialListBase
{
    private final SchematicPlacement placement;

    public MaterialListPlacement(SchematicPlacement placement)
    {
        this(placement, false);
    }

    public MaterialListPlacement(SchematicPlacement placement, boolean reCreate)
    {
        super();

        this.placement = placement;

        if (reCreate)
        {
            this.reCreateMaterialList();
        }
    }

    @Override
    public boolean supportsRenderLayers()
    {
        return true;
    }

    @Override
    public String getName()
    {
        return this.placement.getName();
    }

    @Override
    public String getTitle()
    {
        return StringUtils.translate("litematica.gui.title.material_list.placement", this.getName());
    }

    @Override
    public void reCreateMaterialList()
    {
        boolean ignoreState = Configs.Generic.MATERIAL_LIST_IGNORE_STATE.getBooleanValue();

        // The server can see the whole placement, where the local task only sees what is
        // loaded within the render distance and counts everything beyond it as missing
        if (Configs.Generic.MATERIAL_LIST_USING_SERVUX.getBooleanValue() && this.startServerMaterialList(ignoreState))
        {
            return;
        }

        // A server run still in flight would land on top of the local count and replace it
        if (ServerMaterialListSession.getInstance().isActiveFor(this))
        {
            ServerMaterialListSession.getInstance().cancel();
        }

        TaskCountBlocksPlacement task = new TaskCountBlocksPlacement(this.placement, this, ignoreState);
        TaskScheduler.getInstanceClient().scheduleTask(task, 20);
        InfoUtils.showGuiOrInGameMessage(MessageType.INFO, "litematica.message.scheduled_task_added");
    }

    /**
     * @return false when the server cannot do it, so that the local task still runs; the
     *         list a player gets then is the one they would have got without the option
     */
    private boolean startServerMaterialList(boolean ignoreState)
    {
        ServerMaterialListSession session = ServerMaterialListSession.getInstance();

        if (session.isSupportedByServer() == false)
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.WARNING, "litematica.message.warn.material_list.no_server_support");
            return false;
        }

        if (session.start(this, this.placement, ignoreState))
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.INFO, "litematica.message.material_list.server_requested");
            return true;
        }

        return false;
    }
}
