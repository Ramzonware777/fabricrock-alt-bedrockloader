package net.easecation.bedrockloader.loader

import net.easecation.bedrockloader.BedrockLoader
import net.easecation.bedrockloader.bedrock.definition.FeatureRuleDefinition
import net.easecation.bedrockloader.bedrock.definition.StructureTemplateFeatureDefinition
import net.easecation.bedrockloader.loader.context.BedrockPackContext
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Heightmap
import net.minecraft.world.chunk.WorldChunk
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.math.max
import kotlin.math.min

object BedrockFeatureRuntime {
    private data class RuntimeFeatureRule(
        val ruleId: Identifier,
        val structureTemplateFeatureId: Identifier,
        val structureId: Identifier,
        val biomeFilter: Any?,
        val placementPass: String,
        val iterations: Int,
        val scatterChance: Double,
        val xMin: Int,
        val xMax: Int,
        val zMin: Int,
        val zMax: Int,
        val ySpec: Any?
    )

    private data class CompiledFeature(
        val featureId: Identifier,
        val structureId: Identifier,
        val structureNameRaw: String,
        val structureBytes: ByteArray?
    )

    @Volatile
    private var registered = false

    @Volatile
    private var executeWithPrefixMethod: Method? = null

    @Volatile
    private var runtimeRules: List<RuntimeFeatureRule> = emptyList()

    @Volatile
    private var compiledFeatures: Map<Identifier, CompiledFeature> = emptyMap()

    private val missingStructureWarnings = Collections.synchronizedSet(mutableSetOf<String>())
    private val placeStructureWarnings = Collections.synchronizedSet(mutableSetOf<String>())

    private val processedChunksByWorld = Collections.synchronizedMap(mutableMapOf<Identifier, MutableSet<Long>>())

    fun initialize(context: BedrockPackContext) {
        val behavior = context.behavior
        if (behavior.featureRules.isEmpty() || behavior.structureTemplateFeatures.isEmpty()) {
            runtimeRules = emptyList()
            compiledFeatures = emptyMap()
            return
        }

        val compiled = compileFeatures(
            structureFeatures = behavior.structureTemplateFeatures,
            structureBytes = behavior.structures
        )
        compiledFeatures = compiled

        val rules = compileRules(
            featureRules = behavior.featureRules,
            compiledFeatures = compiled
        )
        runtimeRules = rules

        BedrockLoader.logger.info(
            "[Features] Runtime generation prepared: ${compiled.size} structure template feature(s), ${rules.size} executable feature rule(s)"
        )

        registerCallbacksIfNeeded()
    }

    private fun registerCallbacksIfNeeded() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            ServerLifecycleEvents.SERVER_STARTED.register { server ->
                exportStructureTemplates(server)
            }
            ServerChunkEvents.CHUNK_LOAD.register(ServerChunkEvents.Load { world, chunk ->
                handleChunkLoad(world, chunk)
            })
            registered = true
        }
    }

    private fun compileFeatures(
        structureFeatures: Map<Identifier, StructureTemplateFeatureDefinition.StructureTemplateFeature>,
        structureBytes: Map<String, ByteArray>
    ): Map<Identifier, CompiledFeature> {
        val map = mutableMapOf<Identifier, CompiledFeature>()
        structureFeatures.forEach { (featureId, feature) ->
            val structureNameRaw = feature.structureName?.trim().orEmpty()
            val structureId = parseStructureIdentifierSafe(structureNameRaw)
                ?: run {
                    BedrockLoader.logger.warn("[Features] Invalid structure_name '$structureNameRaw' for feature $featureId")
                    return@forEach
                }

            val bytes = resolveStructureBytes(structureBytes, structureId.path)
            if (bytes == null) {
                BedrockLoader.logger.warn(
                    "[Features] No matching .mcstructure found for $featureId (structure_name=$structureNameRaw, resolved=${structureId.path})"
                )
            }

            map[featureId] = CompiledFeature(
                featureId = featureId,
                structureId = structureId,
                structureNameRaw = structureNameRaw,
                structureBytes = bytes
            )
        }
        return map
    }

    private fun compileRules(
        featureRules: Map<Identifier, FeatureRuleDefinition.FeatureRule>,
        compiledFeatures: Map<Identifier, CompiledFeature>
    ): List<RuntimeFeatureRule> {
        val result = mutableListOf<RuntimeFeatureRule>()
        featureRules.forEach { (ruleId, rule) ->
            val placesFeatureId = parseFeatureIdentifierSafe(rule.description?.placesFeature) ?: return@forEach
            val compiledFeature = compiledFeatures[placesFeatureId] ?: return@forEach
            val distribution = asMap(rule.resolvedDistribution()) ?: emptyMap()
            val placementPass = rule.conditions?.placementPass?.lowercase() ?: "surface_pass"
            val iterations = (distribution["iterations"] as? Number)?.toInt()?.coerceIn(1, 64) ?: 1
            val scatterChance = parseScatterChance(distribution["scatter_chance"])
            val xExtent = parseExtent(distribution["x"])
            val zExtent = parseExtent(distribution["z"])

            result += RuntimeFeatureRule(
                ruleId = ruleId,
                structureTemplateFeatureId = placesFeatureId,
                structureId = compiledFeature.structureId,
                biomeFilter = rule.conditions?.biomeFilter,
                placementPass = placementPass,
                iterations = iterations,
                scatterChance = scatterChance,
                xMin = xExtent.first,
                xMax = xExtent.second,
                zMin = zExtent.first,
                zMax = zExtent.second,
                ySpec = distribution["y"]
            )
        }
        return result
    }

    private fun exportStructureTemplates(server: MinecraftServer) {
        if (compiledFeatures.isEmpty()) return
        val root = resolveWorldRoot(server) ?: run {
            BedrockLoader.logger.warn("[Features] Unable to resolve world root path, skipping structure export")
            return
        }

        val generatedRoot = root.resolve("generated")
        var exported = 0
        compiledFeatures.values.forEach { feature ->
            val bytes = feature.structureBytes ?: return@forEach
            try {
                val structurePath = generatedRoot
                    .resolve(feature.structureId.namespace)
                    .resolve("structures")
                    .resolve("${feature.structureId.path}.nbt")
                val parent = structurePath.parent
                if (parent != null) Files.createDirectories(parent)
                Files.write(structurePath, bytes)
                exported++
            } catch (e: Exception) {
                BedrockLoader.logger.warn(
                    "[Features] Failed to export structure ${feature.structureId} from feature ${feature.featureId}: ${e.message}"
                )
            }
        }
        BedrockLoader.logger.info("[Features] Exported $exported structure template file(s) to ${generatedRoot.toAbsolutePath()}")
    }

    private fun handleChunkLoad(world: ServerWorld, chunk: WorldChunk) {
        if (runtimeRules.isEmpty()) return

        val worldKey = world.registryKey.value
        val processed = processedChunksByWorld.computeIfAbsent(worldKey) { Collections.synchronizedSet(mutableSetOf()) }
        val chunkKey = toChunkKey(chunk.pos.x, chunk.pos.z)
        if (!processed.add(chunkKey)) return

        val random = world.random
        runtimeRules.forEach { rule ->
            if (rule.scatterChance <= 0.0) return@forEach
            repeat(rule.iterations) {
                if (random.nextDouble() > rule.scatterChance) return@repeat

                val x = chunk.pos.startX + randomBetween(rule.xMin, rule.xMax, random.nextInt())
                val z = chunk.pos.startZ + randomBetween(rule.zMin, rule.zMax, random.nextInt())
                val y = resolveY(world, x, z, rule.ySpec)
                val pos = BlockPos(x, y, z)
                if (!matchesBiomeFilter(rule.biomeFilter, world, pos)) return@repeat

                placeStructure(world, rule, x, y, z)
            }
        }
    }

    private fun placeStructure(world: ServerWorld, rule: RuntimeFeatureRule, x: Int, y: Int, z: Int) {
        val structureFeature = compiledFeatures[rule.structureTemplateFeatureId]
        if (structureFeature?.structureBytes == null) {
            val key = rule.structureTemplateFeatureId.toString()
            if (missingStructureWarnings.add(key)) {
                BedrockLoader.logger.warn(
                    "[Features] Structure bytes missing for ${rule.structureTemplateFeatureId}; cannot place ${rule.structureId}"
                )
            }
            return
        }

        val command = "place structure ${rule.structureId} $x $y $z"
        val source = world.server.commandSource
            .withWorld(world)
            .withPosition(Vec3d(x + 0.5, y.toDouble(), z + 0.5))
            .withSilent()
            .withLevel(2)

        runCatching {
            executeCommand(world.server, source, command)
        }.onFailure { throwable ->
            val key = "${rule.ruleId}|${rule.structureId}"
            if (placeStructureWarnings.add(key)) {
                BedrockLoader.logger.warn(
                    "[Features] Failed to place structure ${rule.structureId} from rule ${rule.ruleId}: ${throwable.message}"
                )
            }
        }
    }

    private fun executeCommand(server: MinecraftServer, source: ServerCommandSource, command: String) {
        val manager = server.commandManager
        var method = executeWithPrefixMethod
        if (method == null) {
            method = manager.javaClass.methods.firstOrNull {
                it.name == "executeWithPrefix" && it.parameterCount == 2
            }
            executeWithPrefixMethod = method
        }
        if (method != null) {
            method.invoke(manager, source, command)
            return
        }
        manager.dispatcher.execute(command, source)
    }

    private fun parseFeatureIdentifierSafe(raw: String?): Identifier? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val normalized = if (trimmed.contains(':')) trimmed else "minecraft:$trimmed"
        val namespace = normalized.substringBefore(':').lowercase().ifBlank { "minecraft" }
        val path = normalized.substringAfter(':')
            .replace('\\', '/')
            .trim('/')
            .lowercase()
            .replace(Regex("[^a-z0-9/._-]"), "_")
            .ifBlank { "unknown" }
        return runCatching { Identifier.of(namespace, path) }.getOrNull()
    }

    private fun parseStructureIdentifierSafe(raw: String?): Identifier? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val normalized = if (trimmed.contains(':')) trimmed else "minecraft:$trimmed"
        val namespace = normalized.substringBefore(':').lowercase().ifBlank { "minecraft" }
        val path = normalized.substringAfter(':')
            .replace('\\', '/')
            .trim('/')
            .lowercase()
            .replace(Regex("[^a-z0-9/._-]"), "_")
            .ifBlank { "unknown" }
        return runCatching { Identifier.of(namespace, path) }.getOrNull()
    }

    private fun resolveStructureBytes(structureBytes: Map<String, ByteArray>, structurePath: String): ByteArray? {
        val key = structurePath.lowercase().trim('/')
        structureBytes[key]?.let { return it }
        val suffix = "/$key"
        return structureBytes.entries.firstOrNull { it.key.endsWith(suffix) || it.key == key }?.value
    }

    private fun asMap(value: Any?): Map<String, Any?>? {
        val raw = value as? Map<*, *> ?: return null
        return raw.entries
            .mapNotNull { (k, v) -> (k as? String)?.let { it to v } }
            .toMap()
    }

    private fun parseExtent(spec: Any?): Pair<Int, Int> {
        val map = asMap(spec) ?: return 0 to 16
        val extent = map["extent"] as? List<*> ?: return 0 to 16
        val minVal = (extent.getOrNull(0) as? Number)?.toInt() ?: 0
        val maxVal = (extent.getOrNull(1) as? Number)?.toInt() ?: 16
        return min(minVal, maxVal) to max(minVal, maxVal)
    }

    private fun parseScatterChance(spec: Any?): Double {
        return when (spec) {
            null -> 1.0
            is Number -> {
                val value = spec.toDouble()
                if (value > 1.0) (value / 100.0).coerceIn(0.0, 1.0) else value.coerceIn(0.0, 1.0)
            }
            is String -> {
                val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(spec).map { it.value.toDoubleOrNull() ?: 0.0 }.toList()
                if (numbers.isEmpty()) return 1.0
                val maxValue = numbers.maxOrNull() ?: 1.0
                if (maxValue > 1.0) (maxValue / 100.0).coerceIn(0.0, 1.0) else maxValue.coerceIn(0.0, 1.0)
            }
            is Map<*, *> -> {
                val numerator = (spec["numerator"] as? Number)?.toDouble()
                val denominator = (spec["denominator"] as? Number)?.toDouble()
                if (numerator == null || denominator == null || denominator == 0.0) 1.0
                else (numerator / denominator).coerceIn(0.0, 1.0)
            }
            else -> 1.0
        }
    }

    private fun resolveY(world: ServerWorld, x: Int, z: Int, ySpec: Any?): Int {
        return when (ySpec) {
            is Number -> ySpec.toInt()
            is String -> parseYExpression(world, x, z, ySpec)
            is Map<*, *> -> {
                val extent = (ySpec["extent"] as? List<*>) ?: return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
                val minY = (extent.getOrNull(0) as? Number)?.toInt() ?: 0
                val maxY = (extent.getOrNull(1) as? Number)?.toInt() ?: minY
                val low = min(minY, maxY)
                val high = max(minY, maxY)
                low + world.random.nextInt((high - low + 1).coerceAtLeast(1))
            }
            else -> world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
        }
    }

    private fun parseYExpression(world: ServerWorld, x: Int, z: Int, expr: String): Int {
        val raw = expr.lowercase().trim()
        val direct = raw.toIntOrNull()
        if (direct != null) return direct

        if (raw.contains("heightmap")) {
            val base = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
            val offset = Regex("heightmap\\([^)]*\\)\\s*\\+\\s*(-?\\d+)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: 0
            return base + offset
        }
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
    }

    private fun matchesBiomeFilter(filterNode: Any?, world: ServerWorld, pos: BlockPos): Boolean {
        if (filterNode == null) return true
        val biomeId = world.getBiome(pos).key.map { it.value }.orElse(null) ?: return true
        return evaluateBiomeFilterNode(filterNode, biomeId)
    }

    private fun evaluateBiomeFilterNode(node: Any?, biomeId: Identifier): Boolean {
        if (node == null) return true
        return when (node) {
            is List<*> -> node.all { child -> evaluateBiomeFilterNode(child, biomeId) }
            is Map<*, *> -> evaluateBiomeFilterMap(node, biomeId)
            else -> true
        }
    }

    private fun evaluateBiomeFilterMap(node: Map<*, *>, biomeId: Identifier): Boolean {
        val map = node.entries
            .mapNotNull { (k, v) -> (k as? String)?.let { it to v } }
            .toMap()

        (map["all_of"] as? List<*>)?.let { children ->
            return children.all { child -> evaluateBiomeFilterNode(child, biomeId) }
        }
        (map["any_of"] as? List<*>)?.let { children ->
            return children.any { child -> evaluateBiomeFilterNode(child, biomeId) }
        }
        (map["none_of"] as? List<*>)?.let { children ->
            return children.none { child -> evaluateBiomeFilterNode(child, biomeId) }
        }

        val test = (map["test"] as? String)?.lowercase() ?: return true
        val operator = (map["operator"] as? String)?.lowercase() ?: "=="
        val value = map["value"]?.toString()?.lowercase() ?: return true

        val match = when (test) {
            "has_biome_tag" -> matchesBiomeTagLike(biomeId, value)
            "is_biome" -> if (value.contains(':')) biomeId.toString().lowercase() == value else biomeId.path.lowercase() == value
            else -> true
        }

        return when (operator) {
            "!=", "not", "not_equals" -> !match
            else -> match
        }
    }

    private fun matchesBiomeTagLike(biomeId: Identifier, rawTag: String): Boolean {
        val tag = rawTag.substringAfter(':').lowercase()
        val path = biomeId.path.lowercase()

        return when (tag) {
            "overworld", "overworld_generation" -> biomeId.namespace == "minecraft" && !path.contains("nether") && !path.contains("end")
            "nether" -> path.contains("nether")
            "the_end", "end" -> path.contains("end")
            "ocean" -> path.contains("ocean")
            "deep" -> path.contains("deep")
            "beach" -> path.contains("beach")
            "forest" -> path.contains("forest")
            "plains" -> path.contains("plains")
            else -> path.contains(tag)
        }
    }

    private fun resolveWorldRoot(server: MinecraftServer): Path? {
        return runCatching {
            val worldSavePathClass = Class.forName("net.minecraft.util.WorldSavePath")
            val rootField = worldSavePathClass.fields.firstOrNull { it.name == "ROOT" } ?: return@runCatching null
            val rootConstant = rootField.get(null) ?: return@runCatching null
            val getSavePathMethod = server.javaClass.methods.firstOrNull {
                it.name == "getSavePath" && it.parameterCount == 1
            } ?: return@runCatching null
            getSavePathMethod.invoke(server, rootConstant) as? Path
        }.getOrNull()
    }

    private fun randomBetween(minValue: Int, maxValue: Int, seed: Int): Int {
        val low = min(minValue, maxValue)
        val high = max(minValue, maxValue)
        if (low == high) return low
        val span = (high - low + 1).coerceAtLeast(1)
        return low + (seed and Int.MAX_VALUE) % span
    }

    private fun toChunkKey(chunkX: Int, chunkZ: Int): Long {
        return (chunkX.toLong() shl 32) xor (chunkZ.toLong() and 0xffffffffL)
    }
}
