package net.easecation.bedrockloader.bedrock.definition

import com.google.gson.annotations.SerializedName

data class StructureTemplateFeatureDefinition(
    @SerializedName("format_version") val formatVersion: String? = null,
    @SerializedName("minecraft:structure_template_feature")
    val structureTemplateFeature: StructureTemplateFeature? = null
) {
    data class StructureTemplateFeature(
        val description: Description? = null,
        @SerializedName("structure_name") val structureName: String? = null,
        @SerializedName("adjustment_radius") val adjustmentRadius: Int? = null,
        @SerializedName("facing_direction") val facingDirection: String? = null,
        val constraints: Map<String, Any?>? = null
    )

    data class Description(
        val identifier: String? = null
    )
}

