package net.easecation.bedrockloader.loader.deserializer

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import net.easecation.bedrockloader.BedrockLoader
import net.easecation.bedrockloader.bedrock.data.TextureImage
import net.easecation.bedrockloader.bedrock.definition.*
import net.easecation.bedrockloader.loader.context.BedrockResourceContext
import net.easecation.bedrockloader.util.GsonUtil
import net.easecation.bedrockloader.util.TargaReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipFile
import javax.imageio.ImageIO

object BedrockResourceDeserializer : PackDeserializer<BedrockResourceContext> {

    private fun copyFirst(source: JsonObject, target: JsonObject, targetKey: String, vararg sourceKeys: String) {
        for (key in sourceKeys) {
            if (source.has(key)) {
                target.add(targetKey, source.get(key))
                return
            }
        }
    }

    private fun parseLegacyGeometryModel(identifier: String, legacyModel: JsonObject): GeometryDefinition.Model? {
        val description = JsonObject().apply {
            addProperty("identifier", identifier)
            copyFirst(legacyModel, this, "texture_width", "texture_width", "texturewidth")
            copyFirst(legacyModel, this, "texture_height", "texture_height", "textureheight")
            copyFirst(legacyModel, this, "visible_bounds_width", "visible_bounds_width")
            copyFirst(legacyModel, this, "visible_bounds_height", "visible_bounds_height")
            copyFirst(legacyModel, this, "visible_bounds_offset", "visible_bounds_offset")
        }

        val normalized = JsonObject().apply {
            add("description", description)
            if (legacyModel.has("bones")) {
                add("bones", legacyModel.get("bones"))
            }
            if (legacyModel.has("cape")) {
                add("cape", legacyModel.get("cape"))
            }
        }

        return runCatching {
            GsonUtil.GSON.fromJson(normalized, GeometryDefinition.Model::class.java)
        }.getOrNull()
    }

    private fun parseGeometryModels(root: JsonElement, name: String): List<GeometryDefinition.Model> {
        if (!root.isJsonObject) {
            return emptyList()
        }

        val rootObject = root.asJsonObject
        val result = mutableListOf<GeometryDefinition.Model>()

        val modernGeometry = rootObject.get("minecraft:geometry")
        if (modernGeometry != null && modernGeometry.isJsonArray) {
            for (modelElement in modernGeometry.asJsonArray) {
                runCatching {
                    GsonUtil.GSON.fromJson(modelElement, GeometryDefinition.Model::class.java)
                }.onSuccess { model ->
                    result.add(model)
                }.onFailure { error ->
                    BedrockLoader.logger.error("Error parsing modern geometry model in: $name", error)
                }
            }
        }

        for ((key, value) in rootObject.entrySet()) {
            if (!key.startsWith("geometry.") || !value.isJsonObject) {
                continue
            }

            val legacyModel = parseLegacyGeometryModel(key, value.asJsonObject)
            if (legacyModel != null) {
                result.add(legacyModel)
            } else {
                BedrockLoader.logger.warn("Failed to convert legacy geometry model '$key' in: $name")
            }
        }

        return result
    }

    private fun parseJsonElement(stream: InputStream, name: String): JsonElement? {
        return try {
            InputStreamReader(stream).use { reader ->
                val jsonReader = JsonReader(reader)
                jsonReader.isLenient = true
                val element: JsonElement? = GsonUtil.GSON.fromJson(jsonReader, JsonElement::class.java)
                if (element == null) {
                    BedrockLoader.logger.warn("Skipping empty JSON file: $name")
                }
                element
            }
        } catch (e: Exception) {
            BedrockLoader.logger.warn("Skipping malformed JSON file: $name (${e.message})")
            null
        }
    }

    private fun parseJsonObject(stream: InputStream, name: String): JsonObject? {
        val root = parseJsonElement(stream, name) ?: return null
        if (!root.isJsonObject) {
            BedrockLoader.logger.warn("Skipping JSON with non-object root: $name")
            return null
        }
        return root.asJsonObject
    }

    override fun deserialize(file: ZipFile): BedrockResourceContext {
        return deserialize(file, "")
    }

    override fun deserialize(file: ZipFile, pathPrefix: String): BedrockResourceContext {
        val context = BedrockResourceContext()

        fun getEntryWithPrefix(path: String) = file.getEntry("$pathPrefix$path")

        fun getRelativeName(name: String): String? {
            if (pathPrefix.isNotEmpty() && !name.startsWith(pathPrefix)) {
                return null
            }
            return if (pathPrefix.isNotEmpty()) name.removePrefix(pathPrefix) else name
        }

        getEntryWithPrefix("textures/terrain_texture.json")?.let { entry ->
            file.getInputStream(entry).use { stream ->
                try {
                    val root = parseJsonObject(stream, entry.name) ?: return@use
                    val terrainTextureDefinition = GsonUtil.GSON.fromJson(root, TerrainTextureDefinition::class.java)
                    context.terrainTexture.putAll(terrainTextureDefinition.texture_data)
                } catch (e: Exception) {
                    BedrockLoader.logger.error("Error parsing terrain texture definition: ${entry.name}", e)
                }
            }
        }

        getEntryWithPrefix("textures/item_texture.json")?.let { entry ->
            file.getInputStream(entry).use { stream ->
                try {
                    val root = parseJsonObject(stream, entry.name) ?: return@use
                    val itemTextureDefinition = GsonUtil.GSON.fromJson(root, ItemTextureDefinition::class.java)
                    context.itemTexture.putAll(itemTextureDefinition.texture_data)
                } catch (e: Exception) {
                    BedrockLoader.logger.error("Error parsing item texture definition: ${entry.name}", e)
                }
            }
        }

        getEntryWithPrefix("blocks.json")?.let { entry ->
            file.getInputStream(entry).use { stream ->
                try {
                    val root = parseJsonObject(stream, entry.name) ?: return@use
                    val blockResourceDefinition = GsonUtil.GSON.fromJson(root, BlockResourceDefinition::class.java)
                    context.blocks.putAll(blockResourceDefinition.blocks)
                } catch (e: Exception) {
                    BedrockLoader.logger.error("Error parsing block resource definition: ${entry.name}", e)
                }
            }
        }

        val entries = file.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            val relativeName = getRelativeName(name) ?: continue

            if (relativeName.startsWith("textures/") && (relativeName.endsWith(".png") || relativeName.endsWith(".jpg") || relativeName.endsWith(".tga"))) {
                try {
                    val ext = relativeName.substring(relativeName.lastIndexOf('.') + 1)
                    val withoutExt = relativeName.substring(0, relativeName.lastIndexOf('.'))
                    val image = when (ext.lowercase()) {
                        "tga" -> TargaReader.read(file.getInputStream(entry))
                        else -> ImageIO.read(file.getInputStream(entry))
                    }
                    if (image == null) {
                        BedrockLoader.logger.warn("Skipping texture with unsupported/invalid image data: $name")
                    } else {
                        context.textureImages[withoutExt] = TextureImage(image, ext)
                    }
                } catch (e: Exception) {
                    BedrockLoader.logger.error("Error parsing texture: $name", e)
                }
            }

            if (relativeName.endsWith(".geo.json") || (relativeName.startsWith("models/") && relativeName.endsWith(".json"))) {
                file.getInputStream(entry).use { stream ->
                    try {
                        val root = parseJsonElement(stream, name) ?: return@use
                        val models = parseGeometryModels(root, name)
                        if (models.isEmpty()) {
                            BedrockLoader.logger.warn("Skipping unsupported geometry format: $name")
                            return@use
                        }
                        for (model in models) {
                            context.geometries[model.description.identifier] = model
                        }
                    } catch (e: Exception) {
                        BedrockLoader.logger.error("Error parsing geometry: $name", e)
                    }
                }
            }

            if (relativeName.startsWith("entity/") && relativeName.endsWith(".json")) {
                file.getInputStream(entry).use { stream ->
                    try {
                        val root = parseJsonObject(stream, name) ?: return@use
                        if (!root.has("minecraft:client_entity")) {
                            return@use
                        }
                        val entityResourceDefinition = GsonUtil.GSON.fromJson(root, EntityResourceDefinition::class.java)
                        val clientEntity = entityResourceDefinition?.clientEntity ?: return@use
                        context.entities[clientEntity.description.identifier] = clientEntity
                    } catch (e: Exception) {
                        BedrockLoader.logger.error("Error parsing client entity: $name", e)
                    }
                }
            }

            if (relativeName.startsWith("render_controllers/") && relativeName.endsWith(".json")) {
                file.getInputStream(entry).use { stream ->
                    try {
                        val root = parseJsonObject(stream, name) ?: return@use
                        if (!root.has("render_controllers")) {
                            return@use
                        }
                        val renderControllerDefinition = GsonUtil.GSON.fromJson(root, EntityRenderControllerDefinition::class.java)
                        if (renderControllerDefinition == null) {
                            BedrockLoader.logger.warn("Skipping null render controller definition: $name")
                            return@use
                        }
                        context.renderControllers.putAll(renderControllerDefinition.renderControllers)
                    } catch (e: Exception) {
                        BedrockLoader.logger.error("Error parsing render controller: $name", e)
                    }
                }
            }

            if (relativeName.startsWith("animation_controllers/") && relativeName.endsWith(".json")) {
                file.getInputStream(entry).use { stream ->
                    try {
                        val root = parseJsonObject(stream, name) ?: return@use
                        if (!root.has("animation_controllers")) {
                            return@use
                        }
                        val definition = GsonUtil.GSON.fromJson(root, EntityAnimationControllerDefinition::class.java)
                        if (definition == null) {
                            BedrockLoader.logger.warn("Skipping null animation controller definition: $name")
                            return@use
                        }
                        context.animationControllers.putAll(definition.animationControllers)
                    } catch (e: Exception) {
                        BedrockLoader.logger.error("Error parsing animation controller: $name", e)
                    }
                }
            }

            if (relativeName.endsWith(".animation.json") || (relativeName.startsWith("animations/") && relativeName.endsWith(".json"))) {
                file.getInputStream(entry).use { stream ->
                    try {
                        val root = parseJsonObject(stream, name) ?: return@use
                        val animationDefinition = GsonUtil.GSON.fromJson(root, AnimationDefinition::class.java)
                        val animations = animationDefinition?.animations
                        if (animations == null) {
                            BedrockLoader.logger.warn("Skipping null animation definition: $name")
                            return@use
                        }
                        context.animations.putAll(animations)
                    } catch (e: Exception) {
                        BedrockLoader.logger.error("Error parsing animation: $name", e)
                    }
                }
            }
        }

        return context
    }
}
