package net.easecation.bedrockloader.bedrock.entity.components

data class ComponentShooter(
        val def: String?,
        val power: Float?,
        val aux_val: Int?,
        val sound: String?,
        val magic: Boolean?
) : IEntityComponent

