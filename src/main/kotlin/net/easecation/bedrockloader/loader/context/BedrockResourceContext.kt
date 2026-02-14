package net.easecation.bedrockloader.loader.context

import net.easecation.bedrockloader.BedrockLoader
import net.easecation.bedrockloader.bedrock.BedrockTexturePath
import net.easecation.bedrockloader.bedrock.JavaTexturePath
import net.easecation.bedrockloader.bedrock.data.TextureImage
import net.easecation.bedrockloader.bedrock.definition.*
import net.minecraft.util.Identifier

class BedrockResourceContext {

    val terrainTexture: MutableMap<String, TextureDataDefinition> = mutableMapOf()
    val itemTexture: MutableMap<String, TextureDataDefinition> = mutableMapOf()
    val blocks: MutableMap<Identifier, BlockResourceDefinition.Block> = mutableMapOf()
    val entities: MutableMap<Identifier, EntityResourceDefinition.ClientEntity> = mutableMapOf()
    val geometries: MutableMap<String, GeometryDefinition.Model> = mutableMapOf()
    val renderControllers: MutableMap<String, EntityRenderControllerDefinition.RenderController> = mutableMapOf()
    val animationControllers: MutableMap<String, EntityAnimationControllerDefinition.AnimationController> = mutableMapOf()
    val textureImages: MutableMap<BedrockTexturePath, TextureImage> = mutableMapOf()
    val animations: MutableMap<String, AnimationDefinition.Animation> = mutableMapOf()

    fun putAll(other: BedrockResourceContext) {
        terrainTexture.putAll(other.terrainTexture)
        blocks.putAll(other.blocks)
        entities.putAll(other.entities)
        geometries.putAll(other.geometries)
        renderControllers.putAll(other.renderControllers)
        animationControllers.putAll(other.animationControllers)
        textureImages.putAll(other.textureImages)
        itemTexture.putAll(other.itemTexture)
        animations.putAll(other.animations)
    }

    fun terrainTextureToJava(namespace: String, textureKey: String): JavaTexturePath? {
        val textures = terrainTexture[textureKey]?.textures
        val texture = textures?.firstOrNull()?.path
        if (texture == null) {
            BedrockLoader.logger.warn("[BedrockResourcePackLoader] Block texture not found: $textureKey")
            return null
        }
        val normalized = normalizeTextureLeaf(texture)
        return Identifier.tryParse("$namespace:block/$normalized")
            ?: Identifier.tryParse("minecraft:block/$normalized")
            ?: run {
                BedrockLoader.logger.warn("[BedrockResourcePackLoader] Invalid terrain texture path: $texture")
                null
            }
    }

    fun itemTextureToJava(namespace: String, textureKey: String): JavaTexturePath? {
        val textures = itemTexture[textureKey]?.textures
        val texture = textures?.firstOrNull()?.path
        if (texture == null) {
            BedrockLoader.logger.warn("[BedrockResourcePackLoader] Item texture not found: $textureKey")
            return null
        }
        val normalized = normalizeTextureLeaf(texture)
        return Identifier.tryParse("$namespace:item/$normalized")
            ?: Identifier.tryParse("minecraft:item/$normalized")
            ?: run {
                BedrockLoader.logger.warn("[BedrockResourcePackLoader] Invalid item texture path: $texture")
                null
            }
    }

    private fun normalizeTextureLeaf(texturePath: String): String {
        val leaf = texturePath
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringBeforeLast('.', missingDelimiterValue = texturePath.substringAfterLast('/').substringAfterLast('\\'))
            .lowercase()
        val sanitized = leaf.replace(Regex("[^a-z0-9._-]"), "_")
        return sanitized.trim('_').ifBlank { "missing" }
    }
}
