package net.easecation.bedrockloader.entity

import net.easecation.bedrockloader.BedrockLoader
import net.easecation.bedrockloader.bedrock.definition.EntityBehaviourDefinition
import net.easecation.bedrockloader.bedrock.entity.components.ComponentBehaviorRangedAttack
import net.easecation.bedrockloader.bedrock.entity.components.EntityComponents
import net.easecation.bedrockloader.loader.BedrockAddonsRegistry
import net.easecation.bedrockloader.util.GsonUtil
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.SpawnGroup
import net.minecraft.entity.ai.goal.ActiveTargetGoal
import net.minecraft.entity.ai.goal.Goal
import net.minecraft.entity.ai.goal.LookAroundGoal
import net.minecraft.entity.ai.goal.LookAtEntityGoal
import net.minecraft.entity.ai.goal.MeleeAttackGoal
import net.minecraft.entity.ai.goal.RevengeGoal
import net.minecraft.entity.ai.goal.SwimGoal
import net.minecraft.entity.ai.goal.WanderAroundFarGoal
import net.minecraft.entity.ai.pathing.MobNavigation
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageTypes
import net.minecraft.entity.mob.PathAwareEntity
import net.minecraft.entity.passive.IronGolemEntity
import net.minecraft.entity.passive.VillagerEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.projectile.ArrowEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.world.World
import kotlin.math.max
import kotlin.math.sqrt

class EntityDataDriven(
    val identifier: Identifier,
    val components: EntityComponents,
    entityType: EntityType<EntityDataDriven>,
    world: World
) : PathAwareEntity(entityType, world) {

    var animationManager: Any? = null

    private var activeComponents: EntityComponents = components
    private val activeComponentGroups: LinkedHashSet<String> = linkedSetOf()
    private val pendingEvents: ArrayDeque<String> = ArrayDeque()
    private val runtimeTimers: MutableMap<String, RuntimeTimer> = mutableMapOf()

    private var runtimeStateInitialized: Boolean = false
    private var fallbackGoalsInitialized: Boolean = false
    private var activeGroupsDirty: Boolean = false
    private var pendingTransformation: PendingTransformation? = null

    private data class RuntimeTimer(
        val key: String,
        var remainingTicks: Int,
        val periodTicks: Int,
        val looping: Boolean,
        val timeDownEventNode: Any?
    )

    private data class PendingTransformation(
        val intoRaw: String,
        var remainingTicks: Int,
        val dropEquipment: Boolean
    )

    companion object {
        fun buildEntityType(identifier: Identifier): EntityType<EntityDataDriven> {
            return EntityType.Builder.create({ type, world ->
                val components = BedrockAddonsRegistry.entityComponents[identifier]
                    ?: throw IllegalStateException("[EntityDataDriven] Entity $identifier has no components")
                EntityDataDriven(identifier, components, type, world)
            }, SpawnGroup.CREATURE)
                .dimensions(1f, 1f)
                .build(identifier.toString())
        }

        private fun resolveAttackDamage(components: EntityComponents): Double? {
            return components.minecraftAttack
                ?.resolveDamageValueOrNull()
                ?.coerceAtLeast(0.0)
        }

        fun buildEntityAttributes(components: EntityComponents): DefaultAttributeContainer.Builder {
            val builder = createMobAttributes()
            builder.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, resolveAttackDamage(components) ?: 2.0)

            components.minecraftHealth?.max?.let { value ->
                builder.add(EntityAttributes.GENERIC_MAX_HEALTH, value.toDouble())
            } ?: components.minecraftHealth?.value?.let { value ->
                builder.add(EntityAttributes.GENERIC_MAX_HEALTH, value.toDouble())
            }
            components.minecraftKnockbackResistance?.value?.let { value ->
                builder.add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, value.toDouble())
            }
            components.minecraftMovement?.value?.let { value ->
                builder.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, value.toDouble())
            }
            return builder
        }
    }

    private fun behaviourDefinition(): EntityBehaviourDefinition.EntityBehaviour? {
        return BedrockAddonsRegistry.entityBehaviours[identifier]
    }

    private fun currentNavigation() = activeComponents.minecraftNavigationWalk ?: activeComponents.minecraftNavigationGeneric

    private fun ensureRuntimeStateInitialized() {
        if (runtimeStateInitialized) return
        runtimeStateInitialized = true

        enqueueEvent("minecraft:entity_spawned")
        enqueueEvent("entity_spawned")
        processPendingEvents(maxEvents = 64)

        applyRuntimeAttributesFromComponents()
        applyRuntimeNavigationSettings()
        applyRuntimeEquipmentDefaults()
    }

    private fun enqueueEvent(eventName: String?) {
        if (eventName.isNullOrBlank()) return
        pendingEvents.addLast(eventName)
    }

    private fun processPendingEvents(maxEvents: Int) {
        val behavior = behaviourDefinition() ?: return
        var processed = 0

        while (pendingEvents.isNotEmpty() && processed < maxEvents) {
            processed++
            val eventName = pendingEvents.removeFirst()
            val eventNode = behavior.events?.get(eventName)
            applyEventNode(eventNode)
        }

        if (pendingEvents.isNotEmpty()) {
            pendingEvents.clear()
            BedrockLoader.logger.warn("[EntityDataDriven] Dropped overflowing queued events for $identifier")
        }

        if (activeGroupsDirty) {
            activeGroupsDirty = false
            rebuildActiveComponentsFromGroups(behavior)
            applyRuntimeAttributesFromComponents()
            applyRuntimeNavigationSettings()
        }
    }

    private fun applyEventNode(node: Any?) {
        when (node) {
            null -> return
            is List<*> -> node.forEach { child -> applyEventNode(child) }
            is Map<*, *> -> {
                if (!evaluateFilters(node["filters"])) return

                (node["event"] as? String)?.let { enqueueEvent(it) }

                val removeGroups = extractComponentGroupNames(node["remove"])
                val addGroups = extractComponentGroupNames(node["add"])

                if (removeGroups.isNotEmpty()) {
                    if (activeComponentGroups.removeAll(removeGroups.toSet())) {
                        activeGroupsDirty = true
                    }
                }
                if (addGroups.isNotEmpty()) {
                    val before = activeComponentGroups.size
                    activeComponentGroups.addAll(addGroups)
                    if (activeComponentGroups.size != before) {
                        activeGroupsDirty = true
                    }
                }

                (node["sequence"] as? List<*>)?.forEach { sequenceNode ->
                    applyEventNode(sequenceNode)
                }

                val randomizeEntries = (node["randomize"] as? List<*>)?.filterNotNull() ?: emptyList()
                if (randomizeEntries.isNotEmpty()) {
                    chooseRandomizedEventEntry(randomizeEntries)?.let { randomNode ->
                        applyEventNode(randomNode)
                    }
                }
            }
        }
    }

    private fun chooseRandomizedEventEntry(entries: List<Any>): Any? {
        val weighted = entries.map { entry ->
            val weight = ((entry as? Map<*, *>)?.get("weight") as? Number)?.toDouble() ?: 1.0
            entry to weight.coerceAtLeast(0.0)
        }.filter { it.second > 0.0 }

        if (weighted.isEmpty()) return entries.firstOrNull()

        val totalWeight = weighted.sumOf { it.second }
        var roll = random.nextDouble() * totalWeight
        for ((entry, weight) in weighted) {
            roll -= weight
            if (roll <= 0.0) return entry
        }
        return weighted.last().first
    }

    private fun extractComponentGroupNames(actionNode: Any?): List<String> {
        val actionMap = actionNode as? Map<*, *> ?: return emptyList()
        val groups = actionMap["component_groups"] ?: return emptyList()
        return when (groups) {
            is String -> listOf(groups)
            is List<*> -> groups.mapNotNull { it as? String }
            else -> emptyList()
        }
    }

    private fun toStringAnyMap(raw: Any?): Map<String, Any?>? {
        val map = raw as? Map<*, *> ?: return null
        return map.entries.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }.toMap()
    }

    private fun activeGroupMaps(): List<Map<String, Any?>> {
        val groups = behaviourDefinition()?.component_groups ?: return emptyList()
        return activeComponentGroups.mapNotNull { groupName ->
            toStringAnyMap(groups[groupName])
        }
    }

    private fun rebuildActiveComponentsFromGroups(behaviour: EntityBehaviourDefinition.EntityBehaviour) {
        var merged = components
        val groups = behaviour.component_groups ?: run {
            activeComponents = merged
            return
        }

        activeComponentGroups.forEach { groupName ->
            val rawGroup = groups[groupName] ?: return@forEach
            val groupComponents = parseEntityComponents(rawGroup) ?: return@forEach
            merged = mergeEntityComponents(merged, groupComponents)
        }
        activeComponents = merged
    }

    private fun parseEntityComponents(raw: Any?): EntityComponents? {
        if (raw == null) return null
        return try {
            GsonUtil.GSON.fromJson(GsonUtil.GSON.toJsonTree(raw), EntityComponents::class.java)
        } catch (e: Exception) {
            BedrockLoader.logger.warn("[EntityDataDriven] Failed to parse component group for $identifier: ${e.message}")
            null
        }
    }

    private fun mergeEntityComponents(base: EntityComponents, overlay: EntityComponents): EntityComponents {
        val baseJson = GsonUtil.GSON.toJsonTree(base).asJsonObject
        val overlayJson = GsonUtil.GSON.toJsonTree(overlay).asJsonObject
        overlayJson.entrySet().forEach { (key, value) ->
            if (!value.isJsonNull) baseJson.add(key, value)
        }
        return GsonUtil.GSON.fromJson(baseJson, EntityComponents::class.java)
    }

    private fun applyRuntimeAttributesFromComponents() {
        val maxHealth = activeComponents.minecraftHealth?.max ?: activeComponents.minecraftHealth?.value
        maxHealth?.let { value ->
            getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH)?.baseValue = value.toDouble()
            if (health > value) health = value
        }
        activeComponents.minecraftKnockbackResistance?.value?.let { value ->
            getAttributeInstance(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE)?.baseValue = value.toDouble()
        }
        activeComponents.minecraftMovement?.value?.let { value ->
            getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED)?.baseValue = value.toDouble()
        }
        val attackDamage = activeComponents.minecraftAttack?.resolveDamageValueOrNull()
        attackDamage?.let { value ->
            getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE)?.baseValue = value
        }
    }

    private fun applyRuntimeNavigationSettings() {
        val nav = currentNavigation() ?: return
        val minecraftNavigation = navigation as? MobNavigation ?: return

        val canSwim = nav.can_swim == true ||
                nav.can_float == true ||
                nav.is_amphibious == true ||
                nav.can_path_over_water == true

        minecraftNavigation.setCanSwim(canSwim)
        nav.can_open_doors?.let { minecraftNavigation.setCanEnterOpenDoors(it) }
        nav.can_pass_doors?.let { minecraftNavigation.setCanPathThroughDoors(it) }
    }

    private fun applyRuntimeEquipmentDefaults() {
        if (world.isClient) return
        if (activeComponents.minecraftBehaviorRangedAttack != null && mainHandStack.isEmpty) {
            equipStack(EquipmentSlot.MAINHAND, ItemStack(Items.BOW))
        }
    }

    private fun ensureFallbackGoalsInitialized() {
        if (fallbackGoalsInitialized) return
        fallbackGoalsInitialized = true

        val nav = currentNavigation()
        val shouldFloat = nav?.can_swim == true ||
                nav?.can_float == true ||
                nav?.is_amphibious == true ||
                nav?.can_path_over_water == true ||
                nav == null
        if (shouldFloat) {
            goalSelector.add(0, SwimGoal(this))
        }

        if (!hasBasicMobility()) return

        val moveSpeed = activeComponents.minecraftMovement?.value?.toDouble()?.coerceAtLeast(0.1) ?: 1.0
        goalSelector.add(5, WanderAroundFarGoal(this, moveSpeed))
        goalSelector.add(6, LookAtEntityGoal(this, PlayerEntity::class.java, 8.0f))
        goalSelector.add(7, LookAroundGoal(this))

        val ranged = activeComponents.minecraftBehaviorRangedAttack
        if (ranged != null && activeComponents.minecraftShooter != null) {
            goalSelector.add(ranged.priority ?: 2, GenericRangedAttackGoal(this, ranged))
        }

        if (isLikelyHostile()) {
            if (ranged == null) {
                goalSelector.add(2, MeleeAttackGoal(this, max(1.0, moveSpeed), true))
            }
            targetSelector.add(1, RevengeGoal(this))
            targetSelector.add(2, ActiveTargetGoal(this, PlayerEntity::class.java, true))
            targetSelector.add(3, ActiveTargetGoal(this, VillagerEntity::class.java, false))
            targetSelector.add(4, ActiveTargetGoal(this, IronGolemEntity::class.java, true))
        }
    }

    override fun tick() {
        ensureRuntimeStateInitialized()

        tickEnvironmentSensors()
        tickRuntimeTimers()
        processPendingEvents(maxEvents = 128)
        tickTransformation()
        applyDaylightBurning()

        if (activeComponents.minecraftCanFly != null) {
            fallDistance = 0f
        }

        ensureFallbackGoalsInitialized()
        super.tick()
    }

    private fun tickEnvironmentSensors() {
        val sensors = mutableListOf<Any?>()
        sensors.add(activeComponents.minecraftEnvironmentSensor)
        activeGroupMaps().forEach { sensors.add(it["minecraft:environment_sensor"]) }

        sensors.filterNotNull().forEach { sensorNode ->
            val sensorMap = toStringAnyMap(sensorNode) ?: return@forEach
            when (val triggersNode = sensorMap["triggers"]) {
                is List<*> -> triggersNode.forEach { trigger -> applyEventNode(trigger) }
                else -> applyEventNode(triggersNode)
            }
        }
    }

    private fun tickRuntimeTimers() {
        val timerSources = mutableMapOf<String, Any?>()
        activeComponents.minecraftTimer?.let { timerSources["__base__"] = it }

        activeGroupMaps().forEach { groupMap ->
            val timerNode = groupMap["minecraft:timer"] ?: return@forEach
            val groupKey = groupMap.hashCode().toString() + ":" + groupMap.keys.sorted().joinToString("|")
            timerSources[groupKey] = timerNode
        }

        val activeKeys = timerSources.keys.toSet()
        runtimeTimers.keys.toList().forEach { key -> if (key !in activeKeys) runtimeTimers.remove(key) }

        timerSources.forEach { (key, timerNode) ->
            val timerMap = toStringAnyMap(timerNode) ?: return@forEach
            val periodTicks = parseTimerTicks(timerMap["time"])
            if (periodTicks <= 0) return@forEach
            val looping = timerMap["looping"] as? Boolean ?: false
            val eventNode = timerMap["time_down_event"]

            val existing = runtimeTimers[key]
            if (existing == null || existing.periodTicks != periodTicks || existing.looping != looping) {
                runtimeTimers[key] = RuntimeTimer(key, periodTicks, periodTicks, looping, eventNode)
            }
        }

        runtimeTimers.values.toList().forEach { timer ->
            timer.remainingTicks -= 1
            if (timer.remainingTicks > 0) return@forEach

            applyEventNode(timer.timeDownEventNode)
            if (timer.looping) timer.remainingTicks = timer.periodTicks else runtimeTimers.remove(timer.key)
        }
    }

    private fun parseTimerTicks(rawTime: Any?): Int {
        val seconds = when (rawTime) {
            is Number -> rawTime.toDouble()
            is String -> rawTime.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
        return (seconds * 20.0).toInt().coerceAtLeast(0)
    }

    private fun tickTransformation() {
        val transformationNode = resolveActiveTransformationNode()

        if (transformationNode == null) {
            pendingTransformation = null
            return
        }

        if (pendingTransformation == null) {
            val map = toStringAnyMap(transformationNode) ?: return
            val intoRaw = map["into"] as? String ?: return
            val delayTicks = parseTransformationDelayTicks(map["delay"])
            val dropEquipment = map["drop_equipment"] as? Boolean ?: true
            pendingTransformation = PendingTransformation(intoRaw, delayTicks, dropEquipment)
        }

        val pending = pendingTransformation ?: return
        pending.remainingTicks -= 1
        if (pending.remainingTicks > 0) return

        performTransformation(pending)
        pendingTransformation = null
    }

    private fun resolveActiveTransformationNode(): Any? {
        if (activeComponents.minecraftTransformation != null) return activeComponents.minecraftTransformation
        activeGroupMaps().forEach { groupMap ->
            val node = groupMap["minecraft:transformation"]
            if (node != null) return node
        }
        return null
    }

    private fun parseTransformationDelayTicks(delayNode: Any?): Int {
        val delaySeconds = when (delayNode) {
            is Number -> delayNode.toDouble()
            is Map<*, *> -> (toStringAnyMap(delayNode)?.get("value") as? Number)?.toDouble() ?: 0.0
            else -> 0.0
        }
        return (delaySeconds * 20.0).toInt().coerceAtLeast(1)
    }

    private fun performTransformation(pending: PendingTransformation) {
        val serverWorld = world as? ServerWorld ?: return
        val cleanTarget = pending.intoRaw.substringBefore('<').trim()
        val targetId = parseIdentifierSafe(cleanTarget) ?: return

        val entityType = Registries.ENTITY_TYPE.get(targetId)
        val transformed = entityType.create(serverWorld) ?: return

        transformed.refreshPositionAndAngles(x, y, z, yaw, pitch)
        transformed.velocity = velocity

        if (customName != null) {
            transformed.customName = customName
            transformed.isCustomNameVisible = isCustomNameVisible
        }

        if (!pending.dropEquipment && transformed is LivingEntity) {
            EquipmentSlot.entries.forEach { slot ->
                transformed.equipStack(slot, getEquippedStack(slot).copy())
            }
        }

        serverWorld.spawnEntity(transformed)
        discard()
    }

    private fun parseIdentifierSafe(raw: String): Identifier? {
        return try {
            if (raw.contains(':')) Identifier.of(raw) else Identifier.of("minecraft", raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun applyDaylightBurning() {
        if (activeComponents.minecraftBurnsInDaylight == null) return
        if (world.isClient || !world.isDay) return
        if (isWet || isSubmergedInWater || isTouchingWater) return
        if (!world.isSkyVisible(blockPos)) return
        setOnFireFor(8.0f)
    }

    private fun evaluateFilters(filtersNode: Any?): Boolean {
        if (filtersNode == null) return true
        return when (filtersNode) {
            is List<*> -> filtersNode.all { child -> evaluateFilters(child) }
            is Map<*, *> -> evaluateFilterMap(toStringAnyMap(filtersNode) ?: return false)
            else -> true
        }
    }

    private fun evaluateFilterMap(filter: Map<String, Any?>): Boolean {
        (filter["all_of"] as? List<*>)?.let { return it.all { child -> evaluateFilters(child) } }
        (filter["any_of"] as? List<*>)?.let { return it.any { child -> evaluateFilters(child) } }
        (filter["none_of"] as? List<*>)?.let { return it.none { child -> evaluateFilters(child) } }

        val test = filter["test"] as? String ?: return true
        val operator = (filter["operator"] as? String)?.lowercase() ?: "=="
        val value = filter["value"]
        return evaluateLeafTest(test.lowercase(), operator, value)
    }

    private fun evaluateLeafTest(test: String, operator: String, value: Any?): Boolean {
        fun opEquals(actual: Boolean): Boolean {
            return when (operator) {
                "!=", "not", "not_equals" -> !actual
                else -> actual
            }
        }

        return when (test) {
            "is_underwater" -> opEquals(isSubmergedInWater)
            "in_water" -> opEquals(isTouchingWater)
            "on_fire" -> opEquals(isOnFire)
            "has_nametag" -> opEquals(customName != null)
            "in_overworld" -> compareBooleans(world.registryKey == World.OVERWORLD, (value as? Boolean) ?: true, operator)
            "is_difficulty" -> compareStrings(world.difficulty.name.lowercase(), (value as? String)?.lowercase() ?: return false, operator)
            "has_component" -> opEquals(hasComponent(value as? String ?: return false))
            "actor_health" -> compareNumbers(health.toDouble(), (value as? Number)?.toDouble() ?: return false, operator)
            "is_variant" -> {
                val expected = (value as? Number)?.toInt() ?: return false
                val actual = when (val variant = activeComponents.minecraftVariant) {
                    is Number -> variant.toInt()
                    else -> null
                } ?: return false
                compareNumbers(actual.toDouble(), expected.toDouble(), operator)
            }
            "is_biome" -> {
                val expected = value as? String ?: return false
                val biomeId = world.getBiome(blockPos).key.map { it.value.toString() }.orElse("")
                opEquals(biomeId == expected || biomeId.endsWith(expected))
            }
            else -> false
        }
    }

    private fun compareBooleans(actual: Boolean, expected: Boolean, operator: String): Boolean =
        if (operator in setOf("!=", "not", "not_equals")) actual != expected else actual == expected

    private fun compareStrings(actual: String, expected: String, operator: String): Boolean =
        if (operator in setOf("!=", "not", "not_equals")) actual != expected else actual == expected

    private fun compareNumbers(actual: Double, expected: Double, operator: String): Boolean {
        return when (operator) {
            "<" -> actual < expected
            "<=" -> actual <= expected
            ">" -> actual > expected
            ">=" -> actual >= expected
            "!=", "not", "not_equals" -> actual != expected
            else -> actual == expected
        }
    }

    private fun hasComponent(componentId: String): Boolean {
        val normalized = componentId.lowercase()
        return when (normalized) {
            "minecraft:is_baby" -> activeComponents.minecraftIsBaby != null
            "minecraft:attack" -> activeComponents.minecraftAttack != null
            "minecraft:burns_in_daylight" -> activeComponents.minecraftBurnsInDaylight != null
            "minecraft:can_fly" -> activeComponents.minecraftCanFly != null
            "minecraft:shooter" -> activeComponents.minecraftShooter != null
            "minecraft:behavior.ranged_attack" -> activeComponents.minecraftBehaviorRangedAttack != null
            "minecraft:timer" -> activeComponents.minecraftTimer != null
            "minecraft:environment_sensor" -> activeComponents.minecraftEnvironmentSensor != null
            "minecraft:transformation" -> activeComponents.minecraftTransformation != null
            else -> activeGroupMaps().any { groupMap -> normalized in groupMap.keys.map { it.lowercase() } }
        }
    }

    private fun hasBasicMobility(): Boolean {
        if (activeComponents.minecraftIsImmobile?.value == true) return false

        val nav = currentNavigation()
        val hasMobilityComponent = activeComponents.minecraftMovement != null ||
                activeComponents.minecraftUnderwaterMovement != null ||
                activeComponents.minecraftMovementBasic != null ||
                activeComponents.minecraftMovementGeneric != null ||
                nav != null ||
                activeComponents.minecraftCanFly != null
        if (!hasMobilityComponent) return false

        val movementSpeed = activeComponents.minecraftMovement?.value
        if (movementSpeed != null && movementSpeed <= 0f) return false

        if (nav != null && nav.can_walk == false && nav.can_swim != true && nav.is_amphibious != true && nav.can_float != true && nav.can_path_over_water != true && activeComponents.minecraftCanFly == null) {
            return false
        }
        return true
    }

    private fun isLikelyHostile(): Boolean {
        val familySet = activeComponents.minecraftTypeFamily?.family?.map { it.lowercase() }?.toSet() ?: emptySet()
        val hostileFamilyMarkers = setOf("monster", "hostile", "undead", "illager", "raider", "piglin", "zombie", "skeleton", "creeper", "spider", "blaze", "ghast", "wither", "witch")
        if (familySet.any { it in hostileFamilyMarkers }) return true

        val passiveFamilyMarkers = setOf("animal", "villager", "tame", "tamable")
        if (familySet.any { it in passiveFamilyMarkers }) return false

        val idPath = identifier.path.lowercase()
        val hostilePathMarkers = listOf("zombie", "skeleton", "creeper", "spider", "illager", "pillager", "vindicator", "evoker", "witch", "wither", "blaze", "ghast", "ravager", "piglin", "warthen")
        return hostilePathMarkers.any { idPath.contains(it) }
    }

    fun performGenericRangedAttack(target: LivingEntity, shooterPower: Float) {
        if (world.isClient) return

        val arrow = EntityType.ARROW.create(world) as? ArrowEntity ?: return
        arrow.owner = this
        arrow.setPosition(x, eyeY - 0.1, z)

        val dx = target.x - x
        val dz = target.z - z
        val horizontal = sqrt(dx * dx + dz * dz)
        val targetBodyY = target.y + target.height * 0.3333333333333333
        val dy = targetBodyY - arrow.y + horizontal * 0.2

        arrow.setVelocity(dx, dy, dz, shooterPower, 12.0f)
        val damage = (activeComponents.minecraftAttack?.resolveDamageValueOrNull() ?: 2.0).coerceAtLeast(0.0)
        arrow.damage = damage
        world.spawnEntity(arrow)
    }

    override fun isPushable(): Boolean {
        return activeComponents.minecraftPushable?.is_pushable != false
    }

    override fun isCollidable(): Boolean {
        return activeComponents.minecraftPhysics?.has_collision != false && super.isCollidable()
    }

    override fun pushAwayFrom(entity: Entity?) {
        if (isPushable) super.pushAwayFrom(entity)
    }

    override fun pushAway(entity: Entity?) {
        if (isPushable) super.pushAway(entity)
    }

    override fun hasNoGravity(): Boolean {
        if (activeComponents.minecraftCanFly != null) return true
        return activeComponents.minecraftPhysics?.has_gravity == false
    }

    override fun damage(source: DamageSource, amount: Float): Boolean {
        return activeComponents.minecraftHealth?.min?.let {
            when {
                health - amount > 1 && !source.isOf(DamageTypes.OUT_OF_WORLD) -> super.damage(source, amount)
                else -> {
                    if (source.isOf(DamageTypes.OUT_OF_WORLD)) return false
                    health += amount
                    super.damage(source, amount)
                }
            }
        } ?: super.damage(source, amount)
    }
}

private class GenericRangedAttackGoal(
    private val mob: EntityDataDriven,
    private val behavior: ComponentBehaviorRangedAttack
) : Goal() {
    private var cooldownTicks: Int = 0

    override fun canStart(): Boolean {
        val target = mob.target
        return target != null && target.isAlive
    }

    override fun shouldContinue(): Boolean = canStart()

    override fun stop() {
        cooldownTicks = 0
        mob.navigation.stop()
    }

    override fun tick() {
        val target = mob.target ?: return

        val speed = behavior.speed_multiplier?.toDouble()?.coerceAtLeast(0.1) ?: 1.0
        val attackRadius = behavior.attack_radius?.coerceAtLeast(1.0f) ?: 12.0f
        val attackRadiusSq = attackRadius * attackRadius
        val distanceSq = mob.squaredDistanceTo(target)
        val canSee = mob.canSee(target)

        mob.lookControl.lookAt(target, 30.0f, 30.0f)

        if (distanceSq > attackRadiusSq || !canSee) {
            mob.navigation.startMovingTo(target, speed)
        } else {
            mob.navigation.stop()
        }

        if (cooldownTicks > 0) {
            cooldownTicks -= 1
            return
        }
        if (!canSee) return

        val minInterval = behavior.attack_interval_min?.coerceAtLeast(1) ?: 20
        val maxInterval = max(minInterval, behavior.attack_interval_max ?: minInterval)
        val interval = if (maxInterval == minInterval) minInterval else mob.random.nextBetween(minInterval, maxInterval)

        val power = mob.components.minecraftShooter?.power ?: 1.6f
        val burstShots = behavior.burst_shots?.coerceAtLeast(1) ?: 1
        repeat(burstShots) { mob.performGenericRangedAttack(target, power) }

        cooldownTicks = interval
    }
}
