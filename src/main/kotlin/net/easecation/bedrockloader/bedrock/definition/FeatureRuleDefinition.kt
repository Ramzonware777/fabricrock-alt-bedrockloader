package net.easecation.bedrockloader.bedrock.definition

import com.google.gson.annotations.SerializedName

data class FeatureRuleDefinition(
    @SerializedName("format_version") val formatVersion: String? = null,
    @SerializedName("minecraft:feature_rules") val minecraftFeatureRules: FeatureRule? = null
) {
    data class FeatureRule(
        val description: Description? = null,
        val conditions: Conditions? = null,
        val distribution: Any? = null
    ) {
        fun resolvedDistribution(): Any? = distribution ?: conditions?.distribution
    }

    data class Description(
        val identifier: String? = null,
        @SerializedName("places_feature") val placesFeature: String? = null
    )

    data class Conditions(
        @SerializedName("placement_pass") val placementPass: String? = null,
        @SerializedName("minecraft:biome_filter") val biomeFilter: Any? = null,
        val distribution: Any? = null
    )
}

