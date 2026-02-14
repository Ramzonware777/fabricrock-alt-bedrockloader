package net.easecation.bedrockloader.bedrock.entity.components

import com.google.gson.annotations.SerializedName

data class ComponentAddrider(
        @SerializedName("entity_type") val entityType: String? = null,
        val riders: List<Rider> = emptyList(),
        @SerializedName("spawn_event") val spawnEvent: String? = null
) : IEntityComponent {
    data class Rider(
            @SerializedName("entity_type") val entityType: String? = null,
            @SerializedName("spawn_event") val spawnEvent: String? = null
    )
}

