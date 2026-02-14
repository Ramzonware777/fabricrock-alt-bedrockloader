package net.easecation.bedrockloader.loader.context

import net.easecation.bedrockloader.bedrock.definition.BlockBehaviourDefinition
import net.easecation.bedrockloader.bedrock.definition.EntityBehaviourDefinition
import net.easecation.bedrockloader.bedrock.definition.SpawnRulesDefinition
import net.minecraft.util.Identifier

class BedrockBehaviorContext {

    val blocks: MutableMap<Identifier, BlockBehaviourDefinition.BlockBehaviour> = mutableMapOf()
    val entities: MutableMap<Identifier, EntityBehaviourDefinition.EntityBehaviour> = mutableMapOf()
    val spawnRules: MutableMap<Identifier, SpawnRulesDefinition.SpawnRules> = mutableMapOf()

    fun putAll(other: BedrockBehaviorContext) {
        blocks.putAll(other.blocks)
        entities.putAll(other.entities)
        spawnRules.putAll(other.spawnRules)
    }

}
