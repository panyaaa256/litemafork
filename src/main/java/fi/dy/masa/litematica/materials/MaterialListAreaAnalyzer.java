package fi.dy.masa.litematica.materials;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.network.task.ServerAnalyzeSession;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.scheduler.tasks.TaskCountBlocksArea;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;

public class MaterialListAreaAnalyzer extends MaterialListBase
{
    private final AreaSelection selection;

    public MaterialListAreaAnalyzer(AreaSelection selection)
    {
        super();

        this.selection = selection;
    }

    @Override
    public String getName()
    {
        return this.selection.getName();
    }

    @Override
    public String getTitle()
    {
        return StringUtils.translate("litematica.gui.title.material_list.area_analyzer", this.getName());
    }

    @Override
    public void reCreateMaterialList()
    {
        // Prefer the server: it is not limited to the loaded chunks within the render
        // distance, and it can read container contents, which the client is never told about
        if (Configs.Generic.ANALYZE_USING_SERVUX.getBooleanValue() && this.startServerAnalysis())
        {
            return;
        }

        // A server run still in flight would land on top of the local count and replace it
        if (ServerAnalyzeSession.getInstance().isActiveFor(this))
        {
            ServerAnalyzeSession.getInstance().cancel();
        }

        TaskCountBlocksArea task = new TaskCountBlocksArea(this.selection, this);
        TaskScheduler.getInstanceClient().scheduleTask(task, 20);
        InfoUtils.showGuiOrInGameMessage(MessageType.INFO, "litematica.message.scheduled_task_added");
    }

    /**
     * @return false when the server cannot do it, so that the local task still runs; the
     *         list a player gets then is the one they would have got without the option
     */
    private boolean startServerAnalysis()
    {
        ServerAnalyzeSession session = ServerAnalyzeSession.getInstance();

        if (session.isSupportedByServer() == false)
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.WARNING, "litematica.message.warn.area_analyzer.no_server_support");
            return false;
        }

        if (session.start(this.selection, this))
        {
            InfoUtils.showGuiOrInGameMessage(MessageType.INFO, "litematica.message.area_analyzer.server_requested");
            return true;
        }

        return false;
    }
}
