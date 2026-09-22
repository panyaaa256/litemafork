package fi.dy.masa.litematica.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A set of blocks named the way the block list config values name them: one by one
 * ({@code minecraft:lever}), or by tag ({@code #minecraft:buttons}).
 * <p>
 * A name this client does not know is dropped rather than remembered, so a list written for
 * another mod set simply matches less.
 */
public class BlockAndTagSet
{
    private final Set<Block> blocks = new HashSet<>();
    private final List<TagKey<Block>> tags = new ArrayList<>();

    /** Adds one config entry, which names a tag when it starts with {@code #}. */
    public void add(String entry)
    {
        String trimmed = entry.trim();

        if (trimmed.startsWith("#"))
        {
            BlockUtils.getBlockTagFromString(trimmed).ifPresent(this.tags::add);
        }
        else if (trimmed.isEmpty() == false)
        {
            BlockUtils.getBlockFromString(trimmed).ifPresent(this.blocks::add);
        }
    }

    public boolean contains(Block block)
    {
        if (this.blocks.contains(block))
        {
            return true;
        }

        BlockState state = block.defaultBlockState();

        for (TagKey<Block> tag : this.tags)
        {
            if (state.is(tag))
            {
                return true;
            }
        }

        return false;
    }

    public boolean isEmpty()
    {
        return this.blocks.isEmpty() && this.tags.isEmpty();
    }
}
