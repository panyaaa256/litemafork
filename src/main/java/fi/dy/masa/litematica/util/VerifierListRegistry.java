package fi.dy.masa.litematica.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final Set<Block> listedBlocks = new HashSet<>();
    private final List<TagKey<Block>> listedBlockTags = new ArrayList<>();
    private final Map<Block, Set<String>> listedPropertiesPerBlock = new HashMap<>();
    private final List<Map.Entry<TagKey<Block>, Set<String>>> listedPropertiesPerTag = new ArrayList<>();
    private final Set<String> globalListedProperties = new HashSet<>();
    private final Map<Block, List<Map<String, PropertyCondition>>> stateMatchersPerBlock = new HashMap<>();
    private final List<Map.Entry<TagKey<Block>, Map<String, PropertyCondition>>> stateMatchersPerTag = new ArrayList<>();
    private final List<Map<String, PropertyCondition>> globalStateMatchers = new ArrayList<>();

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
                    this.globalListedProperties.addAll(propertiesFinal);
                }

                if (conditionsFinal != null)
                {
                    this.globalStateMatchers.add(conditionsFinal);
                }
            }
            else if (trimmed.startsWith("#"))
            {
                Optional<TagKey<Block>> tag = BlockUtils.getBlockTagFromString(trimmed);
                tag.ifPresent((t) -> {
                    if (propertiesFinal != null)
                    {
                        this.listedPropertiesPerTag.add(Map.entry(t, propertiesFinal));
                    }

                    if (conditionsFinal != null)
                    {
                        this.stateMatchersPerTag.add(Map.entry(t, conditionsFinal));
                    }

                    if (propertiesFinal == null && conditionsFinal == null)
                    {
                        this.listedBlockTags.add(t);
                    }
                });
            }
            else
            {
                Optional<Block> block = BlockUtils.getBlockFromString(trimmed);
                block.ifPresent((b) -> {
                    if (propertiesFinal != null)
                    {
                        this.listedPropertiesPerBlock.merge(b, propertiesFinal, (oldSet, newSet) -> {
                            oldSet.addAll(newSet);
                            return oldSet;
                        });
                    }

                    if (conditionsFinal != null)
                    {
                        this.stateMatchersPerBlock.computeIfAbsent(b, (k) -> new ArrayList<>()).add(conditionsFinal);
                    }

                    if (propertiesFinal == null && conditionsFinal == null)
                    {
                        this.listedBlocks.add(b);
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
        Block block = state.getBlock();

        if (this.isBlockListedWithoutProperties(block) || this.listedPropertiesPerBlock.containsKey(block))
        {
            return true;
        }

        for (Map.Entry<TagKey<Block>, Set<String>> entry : this.listedPropertiesPerTag)
        {
            if (state.is(entry.getKey()))
            {
                return true;
            }
        }

        return this.matchesAnyStateMatcher(state);
    }

    private boolean hasRestrictingEntries()
    {
        return this.listedBlocks.isEmpty() == false || this.listedBlockTags.isEmpty() == false ||
               this.listedPropertiesPerBlock.isEmpty() == false || this.listedPropertiesPerTag.isEmpty() == false ||
               this.stateMatchersPerBlock.isEmpty() == false || this.stateMatchersPerTag.isEmpty() == false ||
               this.globalStateMatchers.isEmpty() == false;
    }

    private boolean isBlockListedWithoutProperties(Block block)
    {
        if (this.listedBlocks.contains(block))
        {
            return true;
        }

        for (TagKey<Block> tag : this.listedBlockTags)
        {
            if (block.defaultBlockState().is(tag))
            {
                return true;
            }
        }

        return false;
    }

    private boolean matchesAnyStateMatcher(BlockState state)
    {
        List<Map<String, PropertyCondition>> matchers = this.stateMatchersPerBlock.get(state.getBlock());

        if (matchers != null)
        {
            for (Map<String, PropertyCondition> conditions : matchers)
            {
                if (stateMatches(state, conditions))
                {
                    return true;
                }
            }
        }

        for (Map.Entry<TagKey<Block>, Map<String, PropertyCondition>> entry : this.stateMatchersPerTag)
        {
            if (state.is(entry.getKey()) && stateMatches(state, entry.getValue()))
            {
                return true;
            }
        }

        for (Map<String, PropertyCondition> conditions : this.globalStateMatchers)
        {
            if (stateMatches(state, conditions))
            {
                return true;
            }
        }

        return false;
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
        Set<String> properties = this.listedPropertiesPerBlock.get(block);

        if (this.globalListedProperties.isEmpty() && this.listedPropertiesPerTag.isEmpty())
        {
            return properties != null ? properties : Collections.emptySet();
        }

        Set<String> combined = new HashSet<>(this.globalListedProperties);

        if (properties != null)
        {
            combined.addAll(properties);
        }

        BlockState defaultState = block.defaultBlockState();

        for (Map.Entry<TagKey<Block>, Set<String>> entry : this.listedPropertiesPerTag)
        {
            if (defaultState.is(entry.getKey()))
            {
                combined.addAll(entry.getValue());
            }
        }

        return combined;
    }
}
