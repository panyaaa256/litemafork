package fi.dy.masa.litematica.gui.widgets;

import java.util.Set;
import javax.annotation.Nullable;

import net.minecraft.world.Container;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.render.GuiContext;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.render.InventoryOverlay.InventoryProperties;
import fi.dy.masa.malilib.render.InventoryOverlayType;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.litematica.schematic.verifier.ContentsMismatch;

/**
 * The hover box for a Wrong Contents entry: the schematic's inventory and the world's, side
 * by side, with the slots that make them differ tinted red.
 * <p>
 * Much like Technical Utilities' inventory comparison for its own verifier category, but fed
 * from what Servux sent rather than from the client world, so it works for containers well
 * outside the render distance.
 */
public class ContentsMismatchInfo
{
    private static final int PADDING = 6;
    private static final int GAP = 8;
    private static final int LINE_HEIGHT = 12;
    private static final int HIGHLIGHT_COLOR = 0xA0FF2020;

    private final Panel left;
    private final Panel right;
    private final String posLine;
    private final String legend;
    private final int totalWidth;
    private final int totalHeight;

    /**
     * @return null when either side cannot be shown as a container, in which case the
     *         caller should fall back to its usual hover box
     */
    @Nullable
    public static ContentsMismatchInfo create(ContentsMismatch mismatch, BlockState state, boolean slotExact, boolean strict)
    {
        Container expected = mismatch.getExpected(state);
        Container found = mismatch.getFound(state);

        if (expected == null || found == null)
        {
            return null;
        }

        return new ContentsMismatchInfo(mismatch,
                                        new Panel(expected, mismatch.getExpectedData(), mismatch.getDifferingExpectedSlots(slotExact, strict)),
                                        new Panel(found, mismatch.getFoundData(), mismatch.getDifferingFoundSlots(slotExact, strict)),
                                        slotExact);
    }

    private ContentsMismatchInfo(ContentsMismatch mismatch, Panel left, Panel right, boolean slotExact)
    {
        this.left = left;
        this.right = right;
        this.posLine = GuiBase.TXT_GRAY + mismatch.getPos().toShortString() + GuiBase.TXT_RST;
        this.legend = StringUtils.translate(slotExact ? "litematica.gui.label.schematic_verifier.contents.legend_slots"
                                                      : "litematica.gui.label.schematic_verifier.contents.legend");

        int panelsWidth = left.width + GAP + right.width;
        int textWidth = Math.max(StringUtils.getStringWidth(this.posLine), StringUtils.getStringWidth(this.legend));

        this.totalWidth = Math.max(panelsWidth, textWidth) + PADDING * 2;
        this.totalHeight = PADDING * 2 + LINE_HEIGHT * 3 + Math.max(left.height, right.height);
    }

    public int getTotalWidth()
    {
        return this.totalWidth;
    }

    public int getTotalHeight()
    {
        return this.totalHeight;
    }

    /** Renders next to the mouse, flipped to the other side when it would leave the screen. */
    public void renderAtMouse(GuiContext ctx, int mouseX, int mouseY)
    {
        int x = mouseX + 10;
        int y = mouseY;

        if (x + this.totalWidth > GuiUtils.getCurrentScreenWidth())
        {
            x = mouseX - this.totalWidth - 10;
        }

        if (y + this.totalHeight > GuiUtils.getCurrentScreenHeight())
        {
            y = mouseY - this.totalHeight - 2;
        }

        this.render(ctx, x, y);
    }

    public void render(GuiContext ctx, int x, int y)
    {
        fi.dy.masa.litematica.render.RenderUtils.renderBackgroundMask(ctx, x + 1, y + 1, this.totalWidth - 1, this.totalHeight - 1);
        RenderUtils.drawOutlinedBox(ctx, x, y, this.totalWidth, this.totalHeight, 0xFF000000, GuiBase.COLOR_HORIZONTAL_BAR);

        int xLeft = x + PADDING;
        int xRight = xLeft + this.left.width + GAP;
        int yText = y + PADDING;

        ctx.drawString(ctx.fontRenderer(), this.posLine, xLeft, yText, 0xFFFFFFFF, false);
        yText += LINE_HEIGHT;

        String pre = GuiBase.TXT_WHITE + GuiBase.TXT_BOLD;
        ctx.drawString(ctx.fontRenderer(), pre + StringUtils.translate("litematica.gui.label.schematic_verifier.expected") + GuiBase.TXT_RST, xLeft, yText, 0xFFFFFFFF, false);
        ctx.drawString(ctx.fontRenderer(), pre + StringUtils.translate("litematica.gui.label.schematic_verifier.found") + GuiBase.TXT_RST, xRight, yText, 0xFFFFFFFF, false);
        yText += LINE_HEIGHT;

        this.left.render(ctx, xLeft, yText);
        this.right.render(ctx, xRight, yText);

        int yLegend = yText + Math.max(this.left.height, this.right.height) + 3;
        ctx.drawString(ctx.fontRenderer(), this.legend, xLeft, yLegend, 0xFFB0B0B0, false);
    }

    /** One side's inventory, laid out the way malilib's inventory overlay draws it. */
    private static class Panel
    {
        private final Container inv;
        private final InventoryOverlayType type;
        private final Set<Integer> highlighted;
        private final int width;
        private final int height;
        private final int slotsPerRow;
        private final int totalSlots;
        private final int slotOffsetX;
        private final int slotOffsetY;

        private Panel(Container inv, CompoundData data, Set<Integer> highlighted)
        {
            this.inv = inv;
            this.type = InventoryOverlay.getBestInventoryType(inv, data);
            this.highlighted = highlighted;

            // A shared instance that the next call overwrites, so take what is needed now
            InventoryProperties props = InventoryOverlay.getInventoryPropsTemp(this.type, inv.getContainerSize());
            this.width = props.width;
            this.height = props.height;
            this.slotsPerRow = props.slotsPerRow;
            this.totalSlots = props.totalSlots;
            this.slotOffsetX = props.slotOffsetX;
            this.slotOffsetY = props.slotOffsetY;
        }

        private void render(GuiContext ctx, int x, int y)
        {
            final int startX = x + this.slotOffsetX;
            final int startY = y + this.slotOffsetY;

            InventoryOverlay.renderInventoryBackground(ctx, this.type, x, y, this.slotsPerRow, this.totalSlots);

            // Under the stacks rather than over them, so the item stays readable
            for (int slot : this.highlighted)
            {
                int[] offset = this.getSlotOffset(slot);

                if (offset != null)
                {
                    RenderUtils.drawRect(ctx, startX + offset[0], startY + offset[1], 16, 16, HIGHLIGHT_COLOR);
                }
            }

            InventoryOverlay.renderInventoryStacks(ctx, this.type, this.inv, startX, startY, this.slotsPerRow,
                                                   0, this.inv.getContainerSize(), Set.of());
        }

        /** Where malilib draws this slot's stack, relative to the first slot; null if it does not. */
        @Nullable
        private int[] getSlotOffset(int slot)
        {
            if (slot < 0 || slot >= this.inv.getContainerSize())
            {
                return null;
            }

            if (this.type == InventoryOverlayType.FURNACE)
            {
                return switch (slot)
                {
                    case 0 -> new int[] { 8, 8 };
                    case 1 -> new int[] { 8, 44 };
                    case 2 -> new int[] { 68, 26 };
                    default -> null;
                };
            }

            if (this.type == InventoryOverlayType.BREWING_STAND)
            {
                return switch (slot)
                {
                    case 0 -> new int[] { 47, 42 };
                    case 1 -> new int[] { 70, 49 };
                    case 2 -> new int[] { 93, 42 };
                    case 3 -> new int[] { 70, 8 };
                    case 4 -> new int[] { 8, 8 };
                    default -> null;
                };
            }

            return new int[] { (slot % this.slotsPerRow) * 18, (slot / this.slotsPerRow) * 18 };
        }
    }
}
