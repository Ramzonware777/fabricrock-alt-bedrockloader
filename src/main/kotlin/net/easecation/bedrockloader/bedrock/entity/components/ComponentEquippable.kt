package net.easecation.bedrockloader.bedrock.entity.components

import com.google.gson.annotations.SerializedName

data class ComponentEquippable(
        val slots: List<Slot> = emptyList()
) : IEntityComponent {
    data class Slot(
            val slot: Int? = null,
            val item: String? = null,
            @SerializedName("accepted_items") val acceptedItems: List<String> = emptyList(),
            @SerializedName("interact_text") val interactText: String? = null,
            @SerializedName("on_equip") val onEquip: Any? = null,
            @SerializedName("on_unequip") val onUnequip: Any? = null
    )
}

