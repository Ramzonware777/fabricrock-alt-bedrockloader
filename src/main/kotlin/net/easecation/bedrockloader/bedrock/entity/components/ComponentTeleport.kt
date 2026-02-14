package net.easecation.bedrockloader.bedrock.entity.components

import com.google.gson.annotations.SerializedName

data class ComponentTeleport(
        @SerializedName("dark_teleport_chance") val darkTeleportChance: Float? = null,
        @SerializedName("light_teleport_chance") val lightTeleportChance: Float? = null,
        @SerializedName("max_random_teleport_time") val maxRandomTeleportTime: Float? = null,
        @SerializedName("min_random_teleport_time") val minRandomTeleportTime: Float? = null,
        @SerializedName("random_teleport_cube") val randomTeleportCube: List<Float> = emptyList(),
        @SerializedName("random_teleports") val randomTeleports: Boolean? = null,
        @SerializedName("target_distance") val targetDistance: Float? = null,
        @SerializedName("target_teleport_chance") val targetTeleportChance: Float? = null
) : IEntityComponent

