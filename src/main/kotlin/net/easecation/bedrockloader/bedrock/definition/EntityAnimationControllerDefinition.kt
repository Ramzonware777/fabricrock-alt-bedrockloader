package net.easecation.bedrockloader.bedrock.definition

import com.google.gson.annotations.SerializedName

data class EntityAnimationControllerDefinition(
    @SerializedName("format_version") val formatVersion: String,
    @SerializedName("animation_controllers") val animationControllers: Map<String, AnimationController>
) {

    data class AnimationController(
        val initial_state: String? = null,
        val states: Map<String, State> = emptyMap()
    )

    data class State(
        val animations: List<Any>? = null,
        val transitions: List<Map<String, String>>? = null,
        val particle_effects: List<Map<String, Any>>? = null,
        val blend_transition: Double? = null
    )
}
