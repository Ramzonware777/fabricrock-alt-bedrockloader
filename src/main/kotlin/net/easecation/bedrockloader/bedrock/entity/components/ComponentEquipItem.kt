package net.easecation.bedrockloader.bedrock.entity.components

import com.google.gson.annotations.SerializedName

data class ComponentEquipItem(
        @SerializedName("can_wear_armor") val canWearArmor: Boolean? = null,
        @SerializedName("excluded_items") val excludedItems: List<ExcludedItem> = emptyList()
) : IEntityComponent {
    data class ExcludedItem(
            val item: String? = null
    )
}

