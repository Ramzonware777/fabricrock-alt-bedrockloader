package net.easecation.bedrockloader.bedrock.entity.components

data class ComponentBehaviorRangedAttack(
        val priority: Int?,
        val speed_multiplier: Float?,
        val attack_interval_min: Int?,
        val attack_interval_max: Int?,
        val attack_radius: Float?,
        val burst_shots: Int?,
        val burst_interval: Float?
) : IEntityComponent

