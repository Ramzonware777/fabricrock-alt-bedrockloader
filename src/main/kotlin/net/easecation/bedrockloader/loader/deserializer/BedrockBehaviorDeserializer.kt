package net.easecation.bedrockloader.loader.deserializer

import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import net.easecation.bedrockloader.BedrockLoader
import net.easecation.bedrockloader.bedrock.definition.BlockBehaviourDefinition
import net.easecation.bedrockloader.bedrock.definition.EntityBehaviourDefinition
import net.easecation.bedrockloader.bedrock.definition.FeatureRuleDefinition
import net.easecation.bedrockloader.bedrock.definition.SpawnRulesDefinition
import net.easecation.bedrockloader.bedrock.definition.StructureTemplateFeatureDefinition
import net.easecation.bedrockloader.loader.context.BedrockBehaviorContext
import net.easecation.bedrockloader.util.GsonUtil
import net.easecation.bedrockloader.util.normalizeIdentifier
import java.io.InputStream
import net.minecraft.util.Identifier
import java.io.InputStreamReader
import java.util.zip.ZipFile

object BedrockBehaviorDeserializer : PackDeserializer<BedrockBehaviorContext> {

    override fun deserialize(file: ZipFile): BedrockBehaviorContext {
        return deserialize(file, "")
    }

    override fun deserialize(file: ZipFile, pathPrefix: String): BedrockBehaviorContext {
        val context = BedrockBehaviorContext()

        fun parseJsonObject(stream: InputStream, name: String): JsonObject? {
            return try {
                InputStreamReader(stream).use { reader ->
                    val jsonReader = JsonReader(reader)
                    jsonReader.isLenient = true
                    val root: JsonObject? = GsonUtil.GSON.fromJson(jsonReader, JsonObject::class.java)
                    if (root == null) {
                        BedrockLoader.logger.warn("Skipping empty behavior JSON: $name")
                    }
                    root
                }
            } catch (e: Exception) {
                BedrockLoader.logger.error("Error parsing behavior JSON: $name", e)
                null
            }
        }

        fun parseIdentifierSafe(raw: String?): Identifier? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            val normalized = if (trimmed.contains(':')) trimmed else "minecraft:$trimmed"
            return runCatching { Identifier.of(normalized.normalizeIdentifier()) }
                .recoverCatching {
                    val namespace = normalized.substringBefore(':').lowercase()
                    val path = normalized.substringAfter(':')
                        .lowercase()
                        .replace(Regex("[^a-z0-9/._-]"), "_")
                        .trim('_')
                        .ifBlank { "unknown" }
                    Identifier.of(namespace, path)
                }
                .getOrNull()
        }

        val entries = file.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name

            // 如果有路径前缀，只处理以该前缀开头的文件
            if (pathPrefix.isNotEmpty() && !name.startsWith(pathPrefix)) {
                continue
            }

            // 移除前缀后的相对路径
            val relativeName = if (pathPrefix.isNotEmpty()) name.removePrefix(pathPrefix) else name

            if (relativeName.startsWith("entities/") && relativeName.endsWith(".json")) {
                file.getInputStream(entry).use { stream ->
                    try {
                        val root = parseJsonObject(stream, name) ?: return@use
                        val entityBehaviourDefinition: EntityBehaviourDefinition = GsonUtil.GSON.fromJson(root, EntityBehaviourDefinition::class.java)
                        val entityBehaviour: EntityBehaviourDefinition.EntityBehaviour = entityBehaviourDefinition.minecraftEntity
                        val identifier: Identifier = entityBehaviour.description.identifier
                        context.entities[identifier] = entityBehaviour
                    } catch (e: Exception) {
                        BedrockLoader.logger.error("Error parsing behavior entity: $name", e)
                    }
                }
            } else if (relativeName.startsWith("blocks/") && relativeName.endsWith(".json")) {
                file.getInputStream(entry).use { stream ->
                    try {
                        val root = parseJsonObject(stream, name) ?: return@use
                        val blockBehaviourDefinition: BlockBehaviourDefinition = GsonUtil.GSON.fromJson(root, BlockBehaviourDefinition::class.java)
                        val blockBehaviour: BlockBehaviourDefinition.BlockBehaviour = blockBehaviourDefinition.minecraftBlock
                        val identifier: Identifier = blockBehaviour.description.identifier
                        context.blocks[identifier] = blockBehaviour
                    } catch (e: Exception) {
                        BedrockLoader.logger.error("Error parsing behavior block: $name", e)
                    }
                }
            } else if (relativeName.startsWith("spawn_rules/") && relativeName.endsWith(".json")) {
                file.getInputStream(entry).use { stream ->
                    try {
                        val root = parseJsonObject(stream, name) ?: return@use
                        val spawnRulesDefinition: SpawnRulesDefinition = GsonUtil.GSON.fromJson(root, SpawnRulesDefinition::class.java)
                        val spawnRules = spawnRulesDefinition.minecraftSpawnRules
                        val identifier: Identifier = spawnRules.description.identifier
                        context.spawnRules[identifier] = spawnRules
                    } catch (e: Exception) {
                        BedrockLoader.logger.error("Error parsing spawn rule: $name", e)
                    }
                }
            } else if (relativeName.startsWith("feature_rules/") && relativeName.endsWith(".json")) {
                file.getInputStream(entry).use { stream ->
                    try {
                        val root = parseJsonObject(stream, name) ?: return@use
                        val definition = GsonUtil.GSON.fromJson(root, FeatureRuleDefinition::class.java)
                        val rule = definition.minecraftFeatureRules ?: return@use
                        val identifier = parseIdentifierSafe(rule.description?.identifier)
                        if (identifier == null) {
                            BedrockLoader.logger.warn("Skipping feature rule with invalid identifier: $name")
                            return@use
                        }
                        context.featureRules[identifier] = rule
                    } catch (e: Exception) {
                        BedrockLoader.logger.error("Error parsing feature rule: $name", e)
                    }
                }
            } else if (relativeName.startsWith("features/") && relativeName.endsWith(".json")) {
                file.getInputStream(entry).use { stream ->
                    try {
                        val root = parseJsonObject(stream, name) ?: return@use
                        if (!root.has("minecraft:structure_template_feature")) {
                            return@use
                        }
                        val definition = GsonUtil.GSON.fromJson(root, StructureTemplateFeatureDefinition::class.java)
                        val feature = definition.structureTemplateFeature ?: return@use
                        val identifier = parseIdentifierSafe(feature.description?.identifier)
                        if (identifier == null) {
                            BedrockLoader.logger.warn("Skipping structure template feature with invalid identifier: $name")
                            return@use
                        }
                        context.structureTemplateFeatures[identifier] = feature
                    } catch (e: Exception) {
                        BedrockLoader.logger.error("Error parsing feature: $name", e)
                    }
                }
            }
        }
        return context
    }

}
