package net.easecation.bedrockloader.loader

import net.easecation.bedrockloader.BedrockLoader
import net.easecation.bedrockloader.bedrock.definition.SpawnRulesDefinition
import net.easecation.bedrockloader.block.BlockContext
import net.easecation.bedrockloader.block.entity.BlockEntityDataDriven
import net.easecation.bedrockloader.loader.context.BedrockPackContext
import net.easecation.bedrockloader.loader.error.LoadingError
import net.easecation.bedrockloader.loader.error.LoadingErrorCollector
import net.easecation.bedrockloader.util.normalizeIdentifier
import net.easecation.bedrockloader.entity.EntityDataDriven
import net.fabricmc.fabric.api.biome.v1.BiomeModifications
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors
import net.minecraft.block.Block
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnLocationTypes
import net.fabricmc.fabric.api.`object`.builder.v1.entity.FabricDefaultAttributeRegistry
import net.minecraft.entity.SpawnRestriction
import net.minecraft.entity.SpawnGroup
import net.minecraft.entity.mob.MobEntity
import net.minecraft.item.BlockItem
import net.minecraft.item.Item
import net.minecraft.item.SpawnEggItem
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.registry.tag.FluidTags
import net.minecraft.util.math.BlockPos
import net.minecraft.util.Identifier
import net.minecraft.world.Heightmap
import net.minecraft.world.ServerWorldAccess
import java.util.Optional

class BedrockBehaviorPackLoader(
        val context: BedrockPackContext
) {

    @OptIn(ExperimentalStdlibApi::class)
    fun load() {
        // 按包注册（保持加载顺序）
        context.packs.forEach { packContext ->
            loadPackContent(packContext)
        }
        BedrockFeatureRuntime.initialize(context)
        BedrockFunctionRuntime.initialize(context)
    }

    /**
     * 加载单个包的内容
     * @param packContext 包上下文
     */
    @OptIn(ExperimentalStdlibApi::class)
    private fun loadPackContent(packContext: net.easecation.bedrockloader.loader.context.SinglePackContext) {
        val packId = packContext.packId
        val packName = packContext.packInfo.name

        BedrockLoader.logger.info("加载行为包内容: $packName [$packId]")

        // 注册方块
        packContext.behavior.blocks.forEach { (id, beh) ->
            try {
                BedrockLoader.logger.info("Registering block $id from pack $packName")
                //? if >=1.21.4 {
                /*// 1.21.4: 使用Blocks.register()来正确设置registry key
                // 注意：createWithSettings() 内部会在 Block 构造之前预验证属性，
                // 避免 Block 构造函数部分执行后抛异常导致 intrusive holder 孤立
                val registryKey = net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.BLOCK, id)
                val block = net.minecraft.block.Blocks.register(
                    registryKey,
                    java.util.function.Function { settings: net.minecraft.block.AbstractBlock.Settings ->
                        BlockContext.createWithSettings(id, beh, settings)
                    },
                    net.minecraft.block.AbstractBlock.Settings.create()
                )
                BedrockLoader.logger.info("Successfully registered block $id: $block")
                *///?} else {
                val block = BlockContext.create(id, beh)
                Registry.register(Registries.BLOCK, id, block)
                //?}
                BedrockAddonsRegistry.blocks[id] = block

                // 保存 BlockContext 用于导出映射
                if (block is BlockContext.BlockDataDriven) {
                    BedrockAddonsRegistry.blockContexts[id] = block.getBlockContext()
                }

                //? if >=1.21.4 {
                /*// 1.21.4: 使用Items.register()来正确注册BlockItem
                val item = net.minecraft.item.Items.register(block)
                *///?} else {
                val item = BlockItem(block, Item.Settings())
                Registry.register(Registries.ITEM, id, item)
                //?}
                BedrockAddonsRegistry.items[id] = item

                // 关键：记录物品到包的映射
                BedrockAddonsRegistry.itemToPackMapping[id] = packId

                // 方块实体
                beh.components.neteaseBlockEntity?.let { blockEntity ->
                    BedrockLoader.logger.info("Registering block entity $id")
                    val blockEntityType = BlockEntityDataDriven.buildBlockEntityType(id)
                    Registry.register(Registries.BLOCK_ENTITY_TYPE, id, blockEntityType)
                    BedrockAddonsRegistry.blockEntities[id] = blockEntityType
                }
            } catch (e: Exception) {
                LoadingErrorCollector.addError(
                    source = "$id (包: $packName)",
                    phase = LoadingError.Phase.BLOCK_REGISTER,
                    message = "加载方块失败: ${e.message}",
                    exception = e
                )
            }
        }

        // 注册实体
        packContext.behavior.entities.forEach { (id, beh) ->
            try {
                BedrockLoader.logger.info("Registering entity $id from pack $packName")

                val spawnRules = packContext.behavior.spawnRules[id]
                if (spawnRules != null) {
                    BedrockAddonsRegistry.entitySpawnRules[id] = spawnRules
                    BedrockAddonsRegistry.entitySpawnGroups[id] = mapPopulationControlToSpawnGroup(spawnRules.description.population_control)
                }

                // entity type
                val entityType = EntityDataDriven.buildEntityType(id)
                Registry.register(Registries.ENTITY_TYPE, id, entityType)
                BedrockAddonsRegistry.entities[id] = entityType
                BedrockAddonsRegistry.entityComponents[id] = beh.components
                BedrockAddonsRegistry.entityBehaviours[id] = beh

                // entity attributes
                FabricDefaultAttributeRegistry.register(entityType, EntityDataDriven.buildEntityAttributes(beh.components))

                // spawn egg
                if (beh.description.is_spawnable == true) {
                    val clientEntity = packContext.resource.entities[id]?.description
                    val entityName = id.path
                    val itemIdentifier = Identifier.of(id.namespace, "${entityName}_spawn_egg")
                    //? if >=1.21.4 {
                    /*// 1.21.4: 使用Items.register()来正确注册SpawnEggItem
                    val spawnEggRegistryKey = net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.ITEM, itemIdentifier)
                    val spawnEggItem = net.minecraft.item.Items.register(
                        spawnEggRegistryKey,
                        java.util.function.Function { settings: net.minecraft.item.Item.Settings ->
                            SpawnEggItem(entityType, settings)
                        },
                        net.minecraft.item.Item.Settings()
                    )
                    *///?} else {
                    val spawnEggItem = SpawnEggItem(
                        entityType,
                        clientEntity?.spawn_egg?.base_color?.replace("#", "")?.hexToInt(HexFormat.Default) ?: 0xffffff,
                        clientEntity?.spawn_egg?.overlay_color?.replace("#", "")?.hexToInt(HexFormat.Default) ?: 0xffffff,
                        Item.Settings()
                    )
                    Registry.register(Registries.ITEM, itemIdentifier, spawnEggItem)
                    //?}
                    BedrockAddonsRegistry.items[itemIdentifier] = spawnEggItem

                    // 关键：记录刷怪蛋到包的映射
                    BedrockAddonsRegistry.itemToPackMapping[itemIdentifier] = packId
                }
            } catch (e: Exception) {
                LoadingErrorCollector.addError(
                    source = "$id (包: $packName)",
                    phase = LoadingError.Phase.ENTITY_REGISTER,
                    message = "加载实体失败: ${e.message}",
                    exception = e
                )
            }
        }

        registerPackSpawnRules(packContext, packName)
        registerPackFeatureData(packContext, packName)
    }

    private fun mapPopulationControlToSpawnGroup(populationControl: String?): SpawnGroup {
        val normalized = populationControl?.trim()?.lowercase() ?: return SpawnGroup.CREATURE

        fun byName(name: String, fallback: SpawnGroup = SpawnGroup.CREATURE): SpawnGroup {
            return SpawnGroup.values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback
        }

        return when (normalized) {
            "monster", "hostile" -> byName("MONSTER")
            "ambient" -> byName("AMBIENT")
            "water_animal", "water_creature" -> byName("WATER_CREATURE", byName("WATER_AMBIENT"))
            "water_ambient" -> byName("WATER_AMBIENT", byName("WATER_CREATURE"))
            "underground_water_creature" -> byName("UNDERGROUND_WATER_CREATURE", byName("WATER_CREATURE"))
            "axolotls", "axolotl" -> byName("AXOLOTLS", byName("WATER_CREATURE"))
            "misc" -> byName("MISC")
            else -> SpawnGroup.CREATURE
        }
    }

    private fun registerPackSpawnRules(
        packContext: net.easecation.bedrockloader.loader.context.SinglePackContext,
        packName: String
    ) {
        if (packContext.behavior.spawnRules.isEmpty()) return

        packContext.behavior.spawnRules.forEach { (entityId, spawnRules) ->
            val rawType = BedrockAddonsRegistry.entities[entityId]
                ?: if (Registries.ENTITY_TYPE.containsId(entityId)) Registries.ENTITY_TYPE.get(entityId) else null

            @Suppress("UNCHECKED_CAST")
            val entityType = rawType as? net.minecraft.entity.EntityType<out MobEntity>
            if (entityType == null) {
                BedrockLoader.logger.warn("[SpawnRules] Entity type not found or not mob entity for $entityId, skipping spawn rules")
                return@forEach
            }

            val spawnGroup = BedrockAddonsRegistry.entitySpawnGroups[entityId]
                ?: mapPopulationControlToSpawnGroup(spawnRules.description.population_control)

            if (spawnRules.conditions.isEmpty()) {
                BedrockLoader.logger.debug("[SpawnRules] No conditions for $entityId in pack $packName")
                return@forEach
            }

            spawnRules.conditions.forEachIndexed { index, condition ->
                val baseWeight = (condition.weight?.default ?: 0).coerceAtLeast(0)
                if (baseWeight <= 0) return@forEachIndexed

                val minGroupSize = (condition.herd?.min_size ?: 1).coerceAtLeast(1)
                val maxGroupSize = maxOf(minGroupSize, condition.herd?.max_size ?: minGroupSize)
                val spawnTargets = resolveSpawnTargets(entityId, entityType, condition, baseWeight)
                if (spawnTargets.isEmpty()) return@forEachIndexed

                spawnTargets.forEach { target ->
                    BiomeModifications.addSpawn(
                        BiomeSelectors.all()
                            .and { biomeContext -> shouldApplyConditionToBiome(biomeContext, condition) },
                        spawnGroup,
                        target.entityType,
                        target.weight,
                        minGroupSize,
                        maxGroupSize
                    )

                    BedrockLoader.logger.info(
                        "[SpawnRules] Registered spawn for ${target.entityId} (group=$spawnGroup, weight=${target.weight}, herd=$minGroupSize-$maxGroupSize, condition=${index + 1}/${spawnRules.conditions.size})"
                    )
                }
            }

            if (BedrockAddonsRegistry.entities.containsKey(entityId)) {
                registerSpawnRestrictions(entityId, entityType, spawnRules)
            }
        }
    }

    private data class SpawnTarget(
        val entityId: Identifier,
        val entityType: EntityType<out MobEntity>,
        val weight: Int
    )

    private fun resolveSpawnTargets(
        defaultEntityId: Identifier,
        defaultEntityType: EntityType<out MobEntity>,
        condition: SpawnRulesDefinition.Condition,
        baseWeight: Int
    ): List<SpawnTarget> {
        val permute = condition.permuteType.orEmpty()
            .filter { (it.weight ?: 0) > 0 && !it.entity_type.isNullOrBlank() }
        if (permute.isEmpty()) {
            return listOf(SpawnTarget(defaultEntityId, defaultEntityType, baseWeight))
        }

        val totalPermuteWeight = permute.sumOf { it.weight ?: 0 }.coerceAtLeast(1)
        val targets = mutableListOf<SpawnTarget>()

        permute.forEach { entry ->
            val parsedId = parsePermuteEntityIdentifier(entry.entity_type) ?: return@forEach
            val rawType = BedrockAddonsRegistry.entities[parsedId]
                ?: if (Registries.ENTITY_TYPE.containsId(parsedId)) Registries.ENTITY_TYPE.get(parsedId) else null
            @Suppress("UNCHECKED_CAST")
            val mobType = rawType as? EntityType<out MobEntity>
            if (mobType == null) {
                BedrockLoader.logger.warn("[SpawnRules] Permute target is not a mob entity or not found: $parsedId")
                return@forEach
            }

            val permuteWeight = (entry.weight ?: 0).coerceAtLeast(0)
            val scaledWeight = ((baseWeight.toDouble() * permuteWeight.toDouble()) / totalPermuteWeight.toDouble())
                .toInt()
                .coerceAtLeast(1)
            targets += SpawnTarget(parsedId, mobType, scaledWeight)
        }

        return if (targets.isNotEmpty()) {
            targets
        } else {
            listOf(SpawnTarget(defaultEntityId, defaultEntityType, baseWeight))
        }
    }

    private fun parsePermuteEntityIdentifier(raw: String?): Identifier? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.substringBefore('<').trim()
        if (normalized.isBlank()) return null
        runCatching { Identifier.of(normalized) }.getOrNull()?.let { return it }

        val lowerPath = normalized.substringAfter(':', normalized).lowercase()
        val candidates = Registries.ENTITY_TYPE.ids.filter {
            it.path.lowercase() == lowerPath || it.path.lowercase().startsWith(lowerPath)
        }
        return if (candidates.size == 1) candidates.first() else null
    }

    private fun registerSpawnRestrictions(
        entityId: Identifier,
        entityType: EntityType<out MobEntity>,
        spawnRules: SpawnRulesDefinition.SpawnRules
    ) {
        if (spawnRules.conditions.isEmpty()) return

        val spawnLocation = resolveSpawnLocation(spawnRules.conditions)
        val heightmapType = resolveHeightmapType(spawnRules.conditions)

        val predicate = SpawnRestriction.SpawnPredicate<MobEntity> { _, world, _, pos, _ ->
            matchesAnySpawnCondition(world, pos, spawnRules.conditions)
        }

        val registerMethod = findSpawnRestrictionRegisterMethod().getOrElse {
            BedrockLoader.logger.warn("[SpawnRules] Unable to access SpawnRestriction.register for $entityId: ${it.message}")
            return
        }

        runCatching {
            registerMethod.invoke(null, entityType, spawnLocation, heightmapType, predicate)
        }.onFailure {
            BedrockLoader.logger.warn("[SpawnRules] Failed to register spawn predicate for $entityId: ${it.message}")
        }.onSuccess {
            BedrockLoader.logger.info("[SpawnRules] Registered spawn predicate for $entityId")
        }
    }

    private fun findSpawnRestrictionRegisterMethod(): Result<java.lang.reflect.Method> {
        return runCatching {
            val direct = runCatching {
                SpawnRestriction::class.java.getDeclaredMethod(
                    "register",
                    EntityType::class.java,
                    net.minecraft.entity.SpawnLocation::class.java,
                    Heightmap.Type::class.java,
                    SpawnRestriction.SpawnPredicate::class.java
                )
            }.getOrNull()

            val fallback = SpawnRestriction::class.java.declaredMethods.firstOrNull { method ->
                method.name == "register" &&
                    method.parameterCount == 4 &&
                    EntityType::class.java.isAssignableFrom(method.parameterTypes[0])
            }

            (direct ?: fallback ?: throw NoSuchMethodException("register(EntityType, SpawnLocation, Heightmap, SpawnPredicate)"))
                .apply { isAccessible = true }
        }
    }

    private fun resolveSpawnLocation(conditions: List<SpawnRulesDefinition.Condition>): net.minecraft.entity.SpawnLocation {
        if (conditions.isEmpty()) return SpawnLocationTypes.UNRESTRICTED

        val allUnderwater = conditions.all { c ->
            c.spawnsUnderwater != null && c.spawnsOnSurface == null && c.spawnsUnderground == null
        }
        if (allUnderwater) return SpawnLocationTypes.IN_WATER

        val allGroundLike = conditions.all { c ->
            c.spawnsOnSurface != null || c.spawnsUnderground != null
        }
        if (allGroundLike) return SpawnLocationTypes.ON_GROUND

        return SpawnLocationTypes.UNRESTRICTED
    }

    private fun resolveHeightmapType(conditions: List<SpawnRulesDefinition.Condition>): Heightmap.Type {
        val allUnderwater = conditions.isNotEmpty() && conditions.all { c ->
            c.spawnsUnderwater != null && c.spawnsOnSurface == null && c.spawnsUnderground == null
        }
        return if (allUnderwater) Heightmap.Type.OCEAN_FLOOR else Heightmap.Type.MOTION_BLOCKING_NO_LEAVES
    }

    private fun matchesAnySpawnCondition(
        world: ServerWorldAccess,
        pos: BlockPos,
        conditions: List<SpawnRulesDefinition.Condition>
    ): Boolean {
        if (conditions.isEmpty()) return true
        return conditions.any { condition -> matchesSpawnCondition(world, pos, condition) }
    }

    private fun matchesSpawnCondition(
        world: ServerWorldAccess,
        pos: BlockPos,
        condition: SpawnRulesDefinition.Condition
    ): Boolean {
        if (!matchesDifficultyFilter(world, condition.difficultyFilter)) return false
        if (!matchesBrightnessFilter(world, pos, condition.brightnessFilter)) return false
        if (!matchesLocationFlags(world, pos, condition)) return false
        if (!matchesAllowedBlocks(world, pos, condition.spawnsOnBlockFilter)) return false
        if (!matchesPreventedBlocks(world, pos, condition.spawnsOnBlockPreventedFilter)) return false

        val biomeId = world.getBiome(pos).key.map { it.value }.orElse(null)
        if (biomeId != null && !evaluateBiomeFilterNode(condition.biomeFilter, biomeId)) return false

        return true
    }

    private fun matchesDifficultyFilter(
        world: ServerWorldAccess,
        difficultyFilter: SpawnRulesDefinition.DifficultyFilter?
    ): Boolean {
        if (difficultyFilter == null) return true

        fun difficultyRank(value: String): Int {
            return when (value.lowercase()) {
                "peaceful" -> 0
                "easy" -> 1
                "normal" -> 2
                "hard" -> 3
                else -> -1
            }
        }

        val currentRank = difficultyRank(world.toServerWorld().difficulty.name)
        if (currentRank < 0) return true

        val minRank = difficultyFilter.min?.let { difficultyRank(it) } ?: 0
        val maxRank = difficultyFilter.max?.let { difficultyRank(it) } ?: 3
        return currentRank in minRank..maxRank
    }

    private fun matchesBrightnessFilter(
        world: ServerWorldAccess,
        pos: BlockPos,
        brightnessFilter: SpawnRulesDefinition.BrightnessFilter?
    ): Boolean {
        if (brightnessFilter == null) return true

        val minLight = brightnessFilter.min ?: 0f
        val maxLight = brightnessFilter.max ?: 15f
        val lightLevel = world.getLightLevel(pos).toFloat()
        return lightLevel >= minLight && lightLevel <= maxLight
    }

    private fun matchesLocationFlags(
        world: ServerWorldAccess,
        pos: BlockPos,
        condition: SpawnRulesDefinition.Condition
    ): Boolean {
        val requiresUnderwater = condition.spawnsUnderwater != null
        val requiresSurface = condition.spawnsOnSurface != null
        val requiresUnderground = condition.spawnsUnderground != null

        if (requiresUnderwater && !world.getFluidState(pos).isIn(FluidTags.WATER)) return false

        if (requiresSurface || requiresUnderground) {
            val isSurface = world.isSkyVisible(pos)
            val surfaceOrUndergroundAllowed = when {
                requiresSurface && requiresUnderground -> true
                requiresSurface -> isSurface
                requiresUnderground -> !isSurface
                else -> true
            }
            if (!surfaceOrUndergroundAllowed) return false
        }

        return true
    }

    private fun matchesPreventedBlocks(
        world: ServerWorldAccess,
        pos: BlockPos,
        preventedBlocks: Any?
    ): Boolean {
        val prevented = parseStringListOrSingle(preventedBlocks)
        if (prevented.isEmpty()) return true

        val belowId = Registries.BLOCK.getId(world.getBlockState(pos.down()).block)
        return prevented.none { raw -> blockIdMatches(raw, belowId) }
    }

    private fun matchesAllowedBlocks(
        world: ServerWorldAccess,
        pos: BlockPos,
        allowedBlocks: Any?
    ): Boolean {
        val allowed = parseStringListOrSingle(allowedBlocks)
        if (allowed.isEmpty()) return true
        val belowId = Registries.BLOCK.getId(world.getBlockState(pos.down()).block)
        return allowed.any { raw -> blockIdMatches(raw, belowId) }
    }

    private fun parseStringListOrSingle(raw: Any?): List<String> {
        return when (raw) {
            null -> emptyList()
            is String -> listOf(raw)
            is List<*> -> raw.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    private fun blockIdMatches(raw: String, blockId: Identifier): Boolean {
        val normalized = raw.lowercase()
        val normalizedBelow = blockId.toString().lowercase()
        val normalizedPath = blockId.path.lowercase()
        return normalized == normalizedBelow ||
            normalized == normalizedPath ||
            (normalized.contains(':') && normalized.substringAfter(':') == normalizedPath)
    }

    private fun shouldApplyConditionToBiome(
        biomeSelectionContext: Any,
        condition: SpawnRulesDefinition.Condition
    ): Boolean {
        val biomeId = extractBiomeIdFromSelectionContext(biomeSelectionContext) ?: return true
        return evaluateBiomeFilterNode(condition.biomeFilter, biomeId)
    }

    private fun extractBiomeIdFromSelectionContext(context: Any): Identifier? {
        return try {
            val getBiomeKeyMethod = context.javaClass.methods.firstOrNull { it.name == "getBiomeKey" } ?: return null
            val biomeKeyOptional = getBiomeKeyMethod.invoke(context) as? Optional<*> ?: return null
            val biomeKey = biomeKeyOptional.orElse(null) ?: return null
            val getValueMethod = biomeKey.javaClass.methods.firstOrNull { it.name == "getValue" } ?: return null
            getValueMethod.invoke(biomeKey) as? Identifier
        } catch (_: Exception) {
            null
        }
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
            "is_biome" -> {
                if (value.contains(':')) {
                    biomeId.toString().lowercase() == value
                } else {
                    biomeId.path.lowercase() == value
                }
            }
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
            "warm_ocean" -> path.contains("warm_ocean")
            "monster" -> biomeId.namespace == "minecraft" && !path.contains("nether") && !path.contains("end")
            else -> path.contains(tag)
        }
    }

    private fun registerPackFeatureData(
        packContext: net.easecation.bedrockloader.loader.context.SinglePackContext,
        packName: String
    ) {
        if (packContext.behavior.structureTemplateFeatures.isNotEmpty()) {
            packContext.behavior.structureTemplateFeatures.forEach { (id, feature) ->
                BedrockAddonsRegistry.structureTemplateFeatures[id] = feature
                BedrockLoader.logger.info(
                    "[Features] Registered structure template feature $id from pack $packName (structure_name=${feature.structureName})"
                )
            }
        }

        if (packContext.behavior.featureRules.isNotEmpty()) {
            packContext.behavior.featureRules.forEach { (ruleId, rule) ->
                BedrockAddonsRegistry.featureRules[ruleId] = rule

                val placesFeatureId = parseFeatureIdentifierSafe(rule.description?.placesFeature)
                if (placesFeatureId != null) {
                    val isCustomKnown = BedrockAddonsRegistry.structureTemplateFeatures.containsKey(placesFeatureId)
                    val isVanilla = placesFeatureId.namespace == "minecraft"
                    if (!isCustomKnown && !isVanilla) {
                        BedrockLoader.logger.warn(
                            "[Features] feature_rule $ruleId references unknown feature $placesFeatureId (pack=$packName)"
                        )
                    }
                } else {
                    BedrockLoader.logger.warn(
                        "[Features] feature_rule $ruleId has invalid places_feature='${rule.description?.placesFeature}' (pack=$packName)"
                    )
                }

                val placementPass = rule.conditions?.placementPass ?: "unknown"
                BedrockLoader.logger.info(
                    "[Features] Registered feature rule $ruleId (placement_pass=$placementPass, distribution=${rule.resolvedDistribution() != null})"
                )
            }
        }
    }

    private fun parseFeatureIdentifierSafe(raw: String?): Identifier? {
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
}
