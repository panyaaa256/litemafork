package fi.dy.masa.litematica.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction.ListType;
import fi.dy.masa.litematica.config.Configs;

/**
 * Parses the Generic -&gt; verifierListType / verifierBlacklist / verifierWhitelist
 * config values and answers whether the Schematic Verifier should ignore a given
 * position or block state difference.<br>
 * Supported entry formats (in both lists):<br>
 * 'minecraft:lever' - match this block<br>
 * '#minecraft:buttons' - match all blocks in this tag<br>
 * 'minecraft:observer[powered=*]' - list the comma-separated properties
 * for this block (or '#tag[prop1=*,prop2=*]' for a tag); '=*' means every
 * value of the property. Bare property names without '=' are invalid.<br>
 * '[waterlogged=*]' or '*[waterlogged=*]' - list the properties for all blocks<br>
 * 'minecraft:lever[powered=true]' - match only the states of this block where
 * all the given property values match (also works for tags and globally)<br>
 * 'minecraft:lever[powered!=true]' - 'key!=value' inverts the condition,
 * matching all the values other than the given one<br>
 * In Blacklist mode a block or state ('key=value') entry excludes the matching
 * positions from the verification entirely, and listed property names are
 * ignored when comparing states.<br>
 * In Whitelist mode only positions involving a listed block or matching state
 * are verified (all blocks, if there are no such entries), and listed property
 * names are the only ones compared for that block.<br>
 * Block and state entries are matched against the schematic (expected) state;
 * only where the schematic is air (extra blocks) the world state is matched
 * instead.<br>
 * Property names and 'key=value' pairs can be mixed in one entry; they are
 * registered independently.
 */
public class VerifierListRegistry
{
    private final ListType listType;
    private final BlockAndTagSet listedBlocks = new BlockAndTagSet();
    private final Scoped<Set<String>> listedProperties = new Scoped<>();
    private final Scoped<Map<String, PropertyCondition>> stateMatchers = new Scoped<>();
    /** Worked out per block on demand, since shouldTreatAsCorrect() asks per position. */
    private final Map<Block, Set<String>> listedPropertiesForBlock = new HashMap<>();

    /** One 'key=value' or 'key!=value' condition of a state matcher; negated = true for '!=' */
    private record PropertyCondition(String value, boolean negated) {}

    public VerifierListRegistry()
    {
        this.listType = (ListType) Configs.Generic.VERIFIER_LIST_TYPE.getOptionListValue();

        List<String> entries;

        if (this.listType == ListType.BLACKLIST)
        {
            entries = Configs.Generic.VERIFIER_BLACKLIST.getStrings();
        }
        else if (this.listType == ListType.WHITELIST)
        {
            entries = Configs.Generic.VERIFIER_WHITELIST.getStrings();
        }
        else
        {
            entries = Collections.emptyList();
        }

        for (String value : entries)
        {
            String trimmed = value.trim();

            if (trimmed.isEmpty())
            {
                continue;
            }

            Set<String> properties = null;
            Map<String, PropertyCondition> conditions = null;
            int index = trimmed.indexOf('[');

            if (index != -1 && trimmed.charAt(trimmed.length() - 1) == ']')
            {
                for (String item : trimmed.substring(index + 1, trimmed.length() - 1).split(","))
                {
                    item = item.trim();

                    if (item.isEmpty())
                    {
                        continue;
                    }

                    int eq = item.indexOf('=');

                    // Bare property names are ambiguous (key or value?), so they are
                    // not accepted; 'key=*' is the explicit "every value" form
                    if (eq == -1)
                    {
                        continue;
                    }

                    boolean negated = eq > 0 && item.charAt(eq - 1) == '!';
                    String key = item.substring(0, negated ? eq - 1 : eq).trim();
                    String val = item.substring(eq + 1).trim();

                    if (key.isEmpty() || val.isEmpty())
                    {
                        continue;
                    }

                    if (val.equals("*"))
                    {
                        // 'key!=*' would never match any state, so it is just ignored
                        if (negated == false)
                        {
                            if (properties == null)
                            {
                                properties = new HashSet<>();
                            }

                            properties.add(key);
                        }
                    }
                    else
                    {
                        if (conditions == null)
                        {
                            conditions = new HashMap<>();
                        }

                        conditions.put(key, new PropertyCondition(val, negated));
                    }
                }

                trimmed = trimmed.substring(0, index);
            }

            final Set<String> propertiesFinal = properties;
            final Map<String, PropertyCondition> conditionsFinal = conditions;

            if (trimmed.isEmpty() || trimmed.equals("*"))
            {
                if (propertiesFinal != null)
                {
                    this.listedProperties.addGlobal(propertiesFinal);
                }

                if (conditionsFinal != null)
                {
                    this.stateMatchers.addGlobal(conditionsFinal);
                }
            }
            else if (propertiesFinal == null && conditionsFinal == null)
            {
                // A bare block or tag name, with nothing said about its properties
                this.listedBlocks.add(trimmed);
            }
            else if (trimmed.startsWith("#"))
            {
                Optional<TagKey<Block>> tag = BlockUtils.getBlockTagFromString(trimmed);
                tag.ifPresent((t) -> {
                    if (propertiesFinal != null)
                    {
                        this.listedProperties.addForTag(t, propertiesFinal);
                    }

                    if (conditionsFinal != null)
                    {
                        this.stateMatchers.addForTag(t, conditionsFinal);
                    }
                });
            }
            else
            {
                Optional<Block> block = BlockUtils.getBlockFromString(trimmed);
                block.ifPresent((b) -> {
                    if (propertiesFinal != null)
                    {
                        this.listedProperties.addForBlock(b, propertiesFinal);
                    }

                    if (conditionsFinal != null)
                    {
                        this.stateMatchers.addForBlock(b, conditionsFinal);
                    }
                });
            }
        }
    }

    /**
     * Whether the position should be completely excluded from the verification
     * results, based on the blocks/states involved. In Blacklist mode positions
     * with a fully blacklisted block or a matching 'key=value' state are
     * excluded, in Whitelist mode positions without any listed block
     * or matching state are excluded.<br>
     * The entries are matched against the schematic (expected) state, so that
     * the result doesn't change with the world state (e.g. a lever being
     * toggled). Only where the schematic is air (extra blocks) the world state
     * is matched instead.
     */
    public boolean isPositionIgnored(BlockState stateSchematic, BlockState stateClient)
    {
        BlockState stateToMatch = stateSchematic.isAir() == false ? stateSchematic : stateClient;

        if (stateToMatch.isAir())
        {
            return false;
        }

        if (this.listType == ListType.BLACKLIST)
        {
            return this.isStateBlacklisted(stateToMatch);
        }
        else if (this.listType == ListType.WHITELIST)
        {
            // Property-name-only or empty whitelists don't restrict which positions get verified
            return this.hasRestrictingEntries() && this.isStateWhitelisted(stateToMatch) == false;
        }

        return false;
    }

    /**
     * Whether the two (different) states of the same block only differ in
     * properties that are excluded from the comparison, and thus the position
     * should be treated as a correct state by the verifier.
     */
    public boolean shouldTreatAsCorrect(BlockState stateExpected, BlockState stateFound)
    {
        Block block = stateExpected.getBlock();

        if (this.listType == ListType.NONE || block != stateFound.getBlock())
        {
            return false;
        }

        Set<String> listedProperties = this.getListedProperties(block);

        if (listedProperties.isEmpty())
        {
            return false;
        }

        // Blacklist mode: the listed properties are ignored, so a difference
        // in an unlisted property is a mismatch.
        // Whitelist mode: only the listed properties are compared, so a
        // difference in a listed property is a mismatch.
        final boolean listedAreIgnored = this.listType == ListType.BLACKLIST;

        for (Property<?> prop : stateExpected.getProperties())
        {
            if (stateExpected.getValue(prop).equals(stateFound.getValue(prop)) == false &&
                listedProperties.contains(prop.getName()) != listedAreIgnored)
            {
                return false;
            }
        }

        return true;
    }

    private boolean isStateBlacklisted(BlockState state)
    {
        return this.isBlockListedWithoutProperties(state.getBlock()) || this.matchesAnyStateMatcher(state);
    }

    private boolean isStateWhitelisted(BlockState state)
    {
        // A global property list says nothing about which blocks are verified, only about
        // which of their properties are compared, so it whitelists nothing by itself
        return this.isBlockListedWithoutProperties(state.getBlock()) ||
               this.listedProperties.hasNamedEntryFor(state) ||
               this.matchesAnyStateMatcher(state);
    }

    private boolean hasRestrictingEntries()
    {
        return this.listedBlocks.isEmpty() == false ||
               this.listedProperties.hasNamedEntries() ||
               this.stateMatchers.isEmpty() == false;
    }

    private boolean isBlockListedWithoutProperties(Block block)
    {
        return this.listedBlocks.contains(block);
    }

    private boolean matchesAnyStateMatcher(BlockState state)
    {
        return this.stateMatchers.anyMatch(state, VerifierListRegistry::stateMatches);
    }

    private static boolean stateMatches(BlockState state, Map<String, PropertyCondition> conditions)
    {
        StateDefinition<Block, BlockState> stateDefinition = state.getBlock().getStateDefinition();

        for (Map.Entry<String, PropertyCondition> entry : conditions.entrySet())
        {
            Property<?> prop = stateDefinition.getProperty(entry.getKey());

            if (prop == null)
            {
                return false;
            }

            PropertyCondition condition = entry.getValue();
            Comparable<?> value = BlockUtils.getPropertyValueByName(prop, condition.value());

            // An unparseable value never matches, also for '!=', so that typos
            // don't silently match every state of the block
            if (value == null || state.getValue(prop).equals(value) == condition.negated())
            {
                return false;
            }
        }

        return true;
    }

    private Set<String> getListedProperties(Block block)
    {
        return this.listedPropertiesForBlock.computeIfAbsent(block, (b) -> {
            Set<String> combined = new HashSet<>();

            this.listedProperties.collectMatching(b.defaultBlockState(), combined::addAll);

            return combined.isEmpty() ? Collections.emptySet() : combined;
        });
    }

    /**
     * What the list says about one block, one block tag, or every block. The three scopes
     * answer the same questions, and only the whitelist tells them apart: a global entry
     * lists properties, and never makes a block one of the verified ones.
     */
    private static class Scoped<T>
    {
        private final Map<Block, List<T>> perBlock = new HashMap<>();
        private final List<Map.Entry<TagKey<Block>, T>> perTag = new ArrayList<>();
        private final List<T> global = new ArrayList<>();

        private void addForBlock(Block block, T value)
        {
            this.perBlock.computeIfAbsent(block, (k) -> new ArrayList<>()).add(value);
        }

        private void addForTag(TagKey<Block> tag, T value)
        {
            this.perTag.add(Map.entry(tag, value));
        }

        private void addGlobal(T value)
        {
            this.global.add(value);
        }

        private boolean isEmpty()
        {
            return this.hasNamedEntries() == false && this.global.isEmpty();
        }

        /** True when anything at all is listed for a named block or tag. */
        private boolean hasNamedEntries()
        {
            return this.perBlock.isEmpty() == false || this.perTag.isEmpty() == false;
        }

        /** True when a named block or tag entry - not a global one - covers this state. */
        private boolean hasNamedEntryFor(BlockState state)
        {
            if (this.perBlock.containsKey(state.getBlock()))
            {
                return true;
            }

            for (Map.Entry<TagKey<Block>, T> entry : this.perTag)
            {
                if (state.is(entry.getKey()))
                {
                    return true;
                }
            }

            return false;
        }

        /**
         * True when anything covering this state satisfies the test. Asked for every
         * position of a verification, so it walks the scopes rather than collecting them.
         */
        private boolean anyMatch(BlockState state, BiPredicate<BlockState, T> predicate)
        {
            for (T value : this.global)
            {
                if (predicate.test(state, value))
                {
                    return true;
                }
            }

            for (T value : this.perBlock.getOrDefault(state.getBlock(), List.of()))
            {
                if (predicate.test(state, value))
                {
                    return true;
                }
            }

            for (Map.Entry<TagKey<Block>, T> entry : this.perTag)
            {
                if (state.is(entry.getKey()) && predicate.test(state, entry.getValue()))
                {
                    return true;
                }
            }

            return false;
        }

        /** Hands everything covering this state, from all three scopes, to the consumer. */
        private void collectMatching(BlockState state, Consumer<T> consumer)
        {
            this.global.forEach(consumer);
            this.perBlock.getOrDefault(state.getBlock(), List.<T>of()).forEach(consumer);

            for (Map.Entry<TagKey<Block>, T> entry : this.perTag)
            {
                if (state.is(entry.getKey()))
                {
                    consumer.accept(entry.getValue());
                }
            }
        }
    }
}
