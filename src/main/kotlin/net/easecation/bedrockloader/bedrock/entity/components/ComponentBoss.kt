package net.easecation.bedrockloader.bedrock.entity.components

import com.google.gson.annotations.SerializedName

data class ComponentBoss(
        @SerializedName("hud_range") val hudRange: Int? = null,
        val name: String? = null,
        @SerializedName("should_darken_sky") val shouldDarkenSky: Boolean? = null
) : IEntityComponent

