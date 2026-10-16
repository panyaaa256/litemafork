package fi.dy.masa.litematica.util;

import net.minecraft.world.level.block.Block;
import fi.dy.masa.litematica.config.Configs;

public class IgnoreBlockRegistry
{
    private final BlockAndTagSet blocks = new BlockAndTagSet();

    public boolean hasBlock(Block block)
    {
        return this.blocks.contains(block);
    }

    public boolean isEmpty()
    {
        return this.blocks.isEmpty();
    }

    public IgnoreBlockRegistry()
    {
        if (Configs.Visuals.IGNORE_EXISTING_BLOCKS.getBooleanValue())
        {
            for (String value : Configs.Visuals.IGNORABLE_EXISTING_BLOCKS.getStrings())
            {
                this.blocks.add(value);
            }
        }
    }
}
