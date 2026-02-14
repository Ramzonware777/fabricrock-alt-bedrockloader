package net.easecation.bedrockloader.bedrock.definition

import com.google.gson.annotations.SerializedName
import net.minecraft.util.Identifier

data class SpawnRulesDefinition(
    @SerializedName("format_version") val formatVersion: String,
    @SerializedName("minecraft:spawn_rules") val minecraftSpawnRules: SpawnRules
) {

    data class SpawnRules(
        val description: Description,
        val conditions: List<Condition> = emptyList()
    )

    data class Description(
        val identifier: Identifier,
        val population_control: String?
    )

    data class Condition(
        @SerializedName("minecraft:spawns_on_surface") val spawnsOnSurface: Any? = null,
        @SerializedName("minecraft:spawns_underground") val spawnsUnderground: Any? = null,
        @SerializedName("minecraft:spawns_underwater") val spawnsUnderwater: Any? = null,
        // Bedrock accepts either a single string or an array for these filters.
        @SerializedName("minecraft:spawns_on_block_prevented_filter") val spawnsOnBlockPreventedFilter: Any? = null,
        @SerializedName("minecraft:spawns_on_block_filter") val spawnsOnBlockFilter: Any? = null,
        @SerializedName("minecraft:permute_type") val permuteType: List<PermuteTypeEntry>? = null,
        @SerializedName("minecraft:brightness_filter") val brightnessFilter: BrightnessFilter? = null,
        @SerializedName("minecraft:difficulty_filter") val difficultyFilter: DifficultyFilter? = null,
        @SerializedName("minecraft:weight") val weight: Weight? = null,
        @SerializedName("minecraft:herd") val herd: Herd? = null,
        @SerializedName("minecraft:density_limit") val densityLimit: DensityLimit? = null,
        @SerializedName("minecraft:biome_filter") val biomeFilter: Any? = null
    )

    data class PermuteTypeEntry(
        val weight: Int? = null,
        val entity_type: String? = null
    )

    data class BrightnessFilter(
        val min: Float? = null,
        val max: Float? = null,
        val adjust_for_weather: Boolean? = null
    )

    data class DifficultyFilter(
        val min: String? = null,
        val max: String? = null
    )

    data class Weight(
        val default: Int? = null
    )

    data class Herd(
        val min_size: Int? = null,
        val max_size: Int? = null
    )

    data class DensityLimit(
        val surface: Int? = null,
        val underground: Int? = null
    )
}
