package net.easecation.bedrockloader.bedrock.entity.components

import com.google.gson.annotations.SerializedName

data class ComponentEquipment(
        val table: String?,
        @SerializedName("slot_drop_chance") val slotDropChance: Any?
) : IEntityComponent
