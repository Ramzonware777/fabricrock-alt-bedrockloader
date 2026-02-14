package net.easecation.bedrockloader.loader

import com.mojang.datafixers.util.Either
import net.easecation.bedrockloader.BedrockLoader
import net.easecation.bedrockloader.bedrock.block.component.BlockComponents
import net.easecation.bedrockloader.bedrock.block.component.ComponentGeometry
import net.easecation.bedrockloader.bedrock.block.component.ComponentMaterialInstances
import net.easecation.bedrockloader.bedrock.definition.BlockResourceDefinition
import net.easecation.bedrockloader.bedrock.definition.EntityRenderControllerDefinition
import net.easecation.bedrockloader.bedrock.definition.EntityResourceDefinition
import net.easecation.bedrockloader.bedrock.definition.GeometryDefinition
import net.easecation.bedrockloader.java.definition.JavaBlockTag
import net.easecation.bedrockloader.java.definition.JavaMCMeta
import net.easecation.bedrockloader.java.definition.VanillaBlockTagsData
import net.easecation.bedrockloader.loader.context.BedrockPackContext
import net.easecation.bedrockloader.render.BedrockEntityMaterial
import net.easecation.bedrockloader.render.BedrockGeometryModel
import net.easecation.bedrockloader.render.BedrockMaterialInstance
import net.easecation.bedrockloader.render.VersionCompat
import net.easecation.bedrockloader.render.renderer.BlockEntityDataDrivenRenderer
import net.easecation.bedrockloader.render.renderer.EntityDataDrivenRenderer
import net.easecation.bedrockloader.util.MolangExpressionEvaluator
import net.easecation.bedrockloader.util.GsonUtil
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories
import net.minecraft.client.render.entity.EmptyEntityRenderer
import net.minecraft.client.render.model.json.JsonUnbakedModel
import net.minecraft.client.render.model.json.ModelTransformation
//? if >=1.21.4 {
/*import net.minecraft.client.render.model.ModelTextures
*///?}
import net.minecraft.client.texture.MissingSprite
import net.minecraft.client.util.SpriteIdentifier
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.imageio.ImageIO

class BedrockResourcePackLoader(
        private val javaResDir: File,
        private val context: BedrockPackContext
) {

    private val initedNamespaces = mutableSetOf<String>()

    fun load() {
        this.init()
        // æ³¨å†Œèµ„æºåŒ…ä¸Šä¸‹æ–‡ï¼Œä¾›åŠ¨æ€æè´¨åˆ›å»ºä½¿ç”¨
        // ä½¿ç”¨é€šé…ç¬¦ä½œä¸ºkeyï¼Œå› ä¸ºæ‰€æœ‰èµ„æºåŒ…éƒ½æ··åˆåœ¨ä¸€ä¸ªå…¨å±€contextä¸­
        BedrockAddonsRegistryClient.packContexts["*"] = context
        // Geometry
        context.resource.geometries.forEach { (key, value) ->
            BedrockAddonsRegistryClient.geometries[key] = BedrockGeometryModel.Factory(value)
        }
        registerBuiltinProjectileGeometryFallbacks()
        // Blocks
        for ((identifier, blockBehaviour) in context.behavior.blocks) {
            val block = context.resource.blocks[identifier]
            val blockComponents = blockBehaviour.components

            // tags
            createBlockTags(identifier, blockComponents.tags)
            // textures
            createBlockTextures(identifier, block, blockComponents)
            // models
            createBlockModel(identifier, block, blockComponents)
            createBlockItemModel(identifier, block, blockComponents)
            // renderer
            registerBlockRenderLayer(identifier, blockComponents)

            // Block Entity
            if (block?.client_entity != null) {
                // textures
                createBlockEntityTextures(identifier, block)
                // models
                createBlockEntityModel(identifier, block)
                // renderer
                registerBlockEntityRenderer(identifier)
            }
        }
        // Entity
        for ((identifier, entityBehaviour) in context.behavior.entities) {
            val clientEntity = context.resource.entities[identifier]?.description

            // textures
            createEntityTextures(identifier, clientEntity)
            createSpawnEggTextures(identifier, clientEntity)
            // models
            createEntityModel(identifier, clientEntity)
            createSpawnEggModel(identifier, clientEntity)
            // renderer
            registerEntityRenderController(identifier)
        }
    }

    private fun createBlockEntityTextures(identifier: Identifier, block: BlockResourceDefinition.Block) {
        val clientEntity = block.client_entity?.let { context.resource.entities[it.identifier]?.description } ?: return
        createEntityTextures(identifier, clientEntity)
    }

    private fun createBlockEntityModel(identifier: Identifier, block: BlockResourceDefinition.Block) {
        val clientEntity = block.client_entity?.let { context.resource.entities[it.identifier]?.description } ?: return
        val (model, material) = createClientEntityModel(identifier, clientEntity) ?: return
        BedrockAddonsRegistryClient.blockEntityModels[identifier] = model
        BedrockAddonsRegistryClient.blockEntityMaterial[identifier] = material

        // æ³¨å†Œæ–¹å—å®žä½“åŠ¨ç”»é…ç½®ï¼ˆç”¨äºŽæ‡’åŠ è½½åˆ›å»º EntityAnimationManagerï¼‰
        registerBlockEntityAnimationConfig(identifier, clientEntity)

        // æ³¨å†Œæ–¹å—å®žä½“ç¼©æ”¾é…ç½®
        registerBlockEntityScaleConfig(identifier, clientEntity)
    }

    private fun registerBlockEntityRenderer(identifier: Identifier) {
        val blockEntityType = BedrockAddonsRegistry.blockEntities[identifier] ?: return
        val model = BedrockAddonsRegistryClient.blockEntityModels[identifier] ?: return
        val material = BedrockAddonsRegistryClient.blockEntityMaterial[identifier] ?: BedrockEntityMaterial.ENTITY
        val spriteId = model.materials["*"]?.spriteId ?: return
        // å®žä½“æ¸²æŸ“å™¨éœ€è¦å®Œæ•´çš„çº¹ç†è·¯å¾„ï¼ˆå¸¦ textures/ å‰ç¼€å’Œ .png æ‰©å±•åï¼‰
        // SpriteIdentifier çš„ textureId æ ¼å¼æ˜¯ block/entity_xxxï¼ˆä¸å¸¦ textures/ å’Œ .pngï¼‰
        val entityTextureId = Identifier.of(spriteId.textureId.namespace, "textures/" + spriteId.textureId.path + ".png")
        BlockEntityRendererFactories.register(blockEntityType) { context ->
            BlockEntityDataDrivenRenderer.create(context, model, entityTextureId, identifier, material)
        }
    }

    /**
     * åˆå§‹åŒ–ä¸´æ—¶èµ„æºåŒ…æ–‡ä»¶å¤¹
     */
    private fun init() {
        javaResDir.deleteRecursively()
        javaResDir.mkdirs()
        // pack.mcmeta
        val fileMcMeta = javaResDir.resolve("pack.mcmeta")
        val mcMeta = JavaMCMeta(
                pack = JavaMCMeta.PackInfo(
                        //? if >=1.21.4 {
                        /*pack_format = 46,
                        *///?} else {
                        pack_format = 34,
                        //?}
                        description = "Bedrock addons loader"
                )
        )
        Files.newBufferedWriter(fileMcMeta.toPath(), StandardCharsets.UTF_8).use { writer ->
            GsonUtil.GSON.toJson(mcMeta, writer)
        }
        // pack.png
        val filePackIcon = javaResDir.resolve("pack.png")
        val packIcon = BedrockLoader::class.java.getResourceAsStream("/res-pack.png")
        packIcon.use { input ->
            filePackIcon.outputStream().use { output ->
                input?.copyTo(output) ?: throw NullPointerException("Cannot find resource: res-pack.png")
            }
        }
        // åˆ›å»ºassetsæ–‡ä»¶å¤¹
        val assetsDir = javaResDir.resolve("assets")
        assetsDir.mkdirs()
        // åˆ›å»ºdataæ–‡ä»¶å¤¹ï¼ˆç”¨äºŽTagç­‰æ•°æ®åŒ…å†…å®¹ï¼‰
        val dataDir = javaResDir.resolve("data")
        dataDir.mkdirs()
        // åŠ è½½åŸºå²©ç‰ˆåŽŸç”Ÿæ–¹å—Tagå®šä¹‰
        loadVanillaBlockTags()
    }

    /**
     * åˆ›å»ºJsonUnbakedModelçš„è¾…åŠ©æ–¹æ³•
     * å¤„ç†1.21.4çš„APIå˜åŒ–
     */
    private fun createJsonUnbakedModel(
        parent: Identifier?,
        textureMap: Map<String, Either<SpriteIdentifier, String>>
    ): JsonUnbakedModel {
        //? if >=1.21.4 {
        /*val builder = ModelTextures.Textures.Builder()
        textureMap.forEach { (key, either) ->
            either.ifLeft { spriteId -> builder.addSprite(key, spriteId) }
                .ifRight { reference -> builder.addTextureReference(key, reference) }
        }
        return JsonUnbakedModel(parent, emptyList(), builder.build(), null, null, ModelTransformation.NONE)
        *///?} else {
        // 1.21.1-1.21.3: ä½¿ç”¨Map
        return JsonUnbakedModel(parent, emptyList(), textureMap, null, null, ModelTransformation.NONE, emptyList())
        //?}
    }

    /**
     * èŽ·å–å‘½åç©ºé—´å¯¹åº”çš„èµ„æºåŒ…æ–‡ä»¶å¤¹ï¼Œå¦‚æžœä¸å­˜åœ¨åˆ™åˆ›å»º
     */
    private fun namespaceDir(namespace: String) : File {
        val namespaceDir = javaResDir.resolve("assets").resolve(namespace)
        if (!namespaceDir.exists()) {
            namespaceDir.mkdirs()
        }
        // init dirs
        if (!initedNamespaces.contains(namespace)) {
            val textures = namespaceDir.resolve("textures")
            if (!textures.exists()) {
                textures.mkdirs()
            }
            val texturesItem = textures.resolve("item")
            if (!texturesItem.exists()) {
                texturesItem.mkdirs()
            }
            val texturesBlock = textures.resolve("block")
            if (!texturesBlock.exists()) {
                texturesBlock.mkdirs()
            }
            val texturesEntity = textures.resolve("entity")
            if (!texturesEntity.exists()) {
                texturesEntity.mkdirs()
            }
            //? if >=1.21.4 {
            /*val itemsDir = namespaceDir.resolve("items")
            if (!itemsDir.exists()) {
                itemsDir.mkdirs()
            }
            *///?}
            initedNamespaces.add(namespace)
        }
        return namespaceDir
    }

    //? if >=1.21.4 {
    /*// ä¸º1.21.4ç”Ÿæˆç‰©å“æ¨¡åž‹å®šä¹‰æ–‡ä»¶ (assets/<namespace>/items/<item_id>.json)
    private fun createItemDefinition(identifier: Identifier, modelPath: String) {
        val namespaceDir = namespaceDir(identifier.namespace)
        val file = namespaceDir.resolve("items/${identifier.path}.json")
        val json = """{"model":{"type":"minecraft:model","model":"$modelPath"}}"""
        file.writeText(json)
    }
    *///?}

    /**
     * æ ¹æ®æ–¹å—æè´¨åŒ…ä¸­çš„å®šä¹‰ï¼Œåˆ›å»ºä¸€ä¸ªæ–¹å—è´´å›¾æ–‡ä»¶ï¼ˆé™„å¸¦å‘½åç©ºé—´ï¼‰
     */
    private fun createBlockTextures(
        identifier: Identifier,
        block: BlockResourceDefinition.Block?,
        blockComponents: BlockComponents?
    ) {
        block?.client_entity?.block_icon?.let { createBlockTexture(identifier, it) }
        block?.textures?.let { createTextures(identifier, it) }
        block?.carried_textures?.let { createTextures(identifier, it) }
        blockComponents?.minecraftMaterialInstances?.values?.forEach { instance ->
            instance.texture?.let { createBlockTexture(identifier, it) }
        }
    }

    private fun createTextures(
        identifier: Identifier,
        textures: BlockResourceDefinition.Textures
    ) {
        when (textures) {
            is BlockResourceDefinition.Textures.TexturesAllFace -> {
                createBlockTexture(identifier, textures.all)
            }
            is BlockResourceDefinition.Textures.TexturesMultiFace -> {
                val directions = mapOf(
                    "up" to textures.up,
                    "down" to textures.down,
                    "north" to textures.north,
                    "south" to textures.south,
                    "east" to textures.east,
                    "west" to textures.west
                )
                for ((_, textureKey) in directions) {
                    textureKey?.let { createBlockTexture(identifier, it) }
                }
            }
        }
    }

    /**
     * æ ¹æ®æ–¹å—æè´¨åŒ…ä¸­çš„å®šä¹‰ï¼Œåˆ›å»ºä¸€ä¸ªæ–¹å—è´´å›¾æ–‡ä»¶ï¼ˆé™„å¸¦å‘½åç©ºé—´ï¼‰
     */
    private fun createBlockTexture(
        identifier: Identifier,
        textureKey: String
    ) {
        val namespaceDir = this.namespaceDir(identifier.namespace)
        val textures = context.resource.terrainTexture[textureKey]?.textures
        val texture = textures?.firstOrNull()?.path
        if (texture == null) {
            BedrockLoader.logger.warn("[BedrockResourcePackLoader] Block texture not found: $textureKey")
            return
        }
        val path = "textures/block/${texture.substringAfterLast("/")}"
        val bedrockTexture = context.resource.textureImages[texture]
        if (bedrockTexture == null) {
            BedrockLoader.logger.warn("[BedrockResourcePackLoader] Block texture not found: $textureKey")
            return
        }
        // ç»Ÿä¸€è½¬æ¢ä¸ºPNGæ ¼å¼ï¼ˆMinecraftåŽŸç”Ÿçº¹ç†ç³»ç»Ÿåªæ”¯æŒPNGï¼‰
        val file = namespaceDir.resolve(path + ".png")
        bedrockTexture.image.let { image ->
            ImageIO.write(image, "png", file)
        }
    }

    /**
     * æ ¹æ®æ–¹å—æè´¨åŒ…å’Œè¡Œä¸ºåŒ…ä¸­çš„å®šä¹‰ï¼Œåˆ›å»ºä¸€ä¸ªæ–¹å—æ¨¡åž‹æ–‡ä»¶ï¼ˆå¦‚æžœè®¾å®šäº†æ¨¡åž‹ï¼Œåˆ™åº”ç”¨æ¨¡åž‹ï¼›å¦åˆ™åº”ç”¨æ­£å¸¸æ–¹å—æ¨¡åž‹ï¼‰
     */
    private fun createBlockModel(
        identifier: Identifier,
        block: BlockResourceDefinition.Block?,
        blockComponents: BlockComponents?
    ) {
        val geometry = blockComponents?.minecraftGeometry
        val materialInstances = blockComponents?.minecraftMaterialInstances

        // æ£€æŸ¥æ˜¯å¦ä½¿ç”¨æ ‡å‡†ç«‹æ–¹ä½“geometry
        val isStandardCube = geometry == null || isStandardCubeGeometry(geometry)

        if (!isStandardCube) {
            // è‡ªå®šä¹‰å‡ ä½•ä½“ï¼šé€šè¿‡è¡Œä¸ºåŒ…å®šä¹‰æ¨¡åž‹å’Œè´´å›¾
            val model = createGeometryModel(identifier, geometry!!, materialInstances) ?: return
            BedrockAddonsRegistryClient.blockModels[identifier] = model
        } else {
            // æ ‡å‡†ç«‹æ–¹ä½“ï¼šä½¿ç”¨cubeæ¨¡åž‹
            val textures = block?.textures
            val model = createCubeModel(identifier, textures, materialInstances)
            BedrockAddonsRegistryClient.blockModels[identifier] = model
        }
    }

    /**
     * ç›´æŽ¥åˆ›å»ºä¸€ä¸ªç»§æ‰¿äºŽå¯¹åº”æ–¹å—æ¨¡åž‹çš„ç‰©å“æ¨¡åž‹
     */
    private fun createBlockItemModel(
        identifier: Identifier,
        block: BlockResourceDefinition.Block?,
        blockComponents: BlockComponents?
    ) {
        val bedrockClientEntity = block?.client_entity
        if (bedrockClientEntity != null) {
            if (bedrockClientEntity.hand_model_use_client_entity == true) {
                // ä½¿ç”¨å®¢æˆ·ç«¯å®žä½“çš„å‡ ä½•ä½“æ¸²æŸ“ç‰©å“
                val clientEntity = context.resource.entities[bedrockClientEntity.identifier]?.description
                if (clientEntity != null) {
                    val result = createClientEntityModel(identifier, clientEntity)
                    if (result != null) {
                        val (model, _) = result
                        BedrockAddonsRegistryClient.itemModels[identifier] = model
                        //? if >=1.21.4 {
                        /*createItemDefinition(identifier, "${identifier.namespace}:item/${identifier.path}")
                        *///?}
                        return
                    }
                }
                // å¦‚æžœå®¢æˆ·ç«¯å®žä½“æ¨¡åž‹åˆ›å»ºå¤±è´¥ï¼Œå°è¯•é™çº§åˆ° block_icon
                BedrockLoader.logger.warn("[BedrockResourcePackLoader] Failed to create client entity model for item $identifier, falling back to block_icon")
            }
            // ä½¿ç”¨ block_icon åˆ›å»ºå¹³é¢å›¾æ ‡
            val blockIcon = bedrockClientEntity.block_icon ?: return
            val textureMap = mutableMapOf<String, Either<SpriteIdentifier, String>>()
            context.resource.terrainTextureToJava(identifier.namespace, blockIcon)?.let {
                textureMap["layer0"] = Either.left(SpriteIdentifier(VersionCompat.BLOCK_ATLAS_TEXTURE, it))
            }
            BedrockAddonsRegistryClient.itemModels[identifier] = createJsonUnbakedModel(Identifier.of("item/generated"), textureMap)
            //? if >=1.21.4 {
            /*createItemDefinition(identifier, "${identifier.namespace}:item/${identifier.path}")
            *///?}
        } else {
            val geometry = blockComponents?.minecraftGeometry
            val materialInstances = blockComponents?.minecraftMaterialInstances

            // æ£€æŸ¥æ˜¯å¦ä½¿ç”¨æ ‡å‡†ç«‹æ–¹ä½“geometry
            val isStandardCube = geometry == null || isStandardCubeGeometry(geometry)

            if (!isStandardCube) {
                // è‡ªå®šä¹‰å‡ ä½•ä½“ï¼šé€šè¿‡è¡Œä¸ºåŒ…å®šä¹‰æ¨¡åž‹å’Œè´´å›¾
                val model = createGeometryModel(identifier, geometry!!, materialInstances) ?: return
                BedrockAddonsRegistryClient.itemModels[identifier] = model
            } else {
                // æ ‡å‡†ç«‹æ–¹ä½“ï¼šä½¿ç”¨cubeæ¨¡åž‹
                val textures = block?.carried_textures ?: block?.textures
                val model = createCubeModel(identifier, textures, materialInstances)
                BedrockAddonsRegistryClient.itemModels[identifier] = model
            }
            //? if >=1.21.4 {
            /*createItemDefinition(identifier, "${identifier.namespace}:item/${identifier.path}")
            *///?}
        }
    }

    /**
     * åˆ¤æ–­geometryæ˜¯å¦ä¸ºæ ‡å‡†ç«‹æ–¹ä½“ï¼ˆåº”è¯¥ä½¿ç”¨createCubeModelå¤„ç†ï¼‰
     * åŸºå²©ç‰ˆå†…ç½®çš„æ ‡å‡†ç«‹æ–¹ä½“æ ‡è¯†ç¬¦åŒ…æ‹¬ï¼š
     * - minecraft:geometry.full_block
     * æœªæ¥å¯èƒ½éœ€è¦æ·»åŠ å…¶ä»–æ ‡è¯†ç¬¦
     */
    private fun isStandardCubeGeometry(geometry: ComponentGeometry): Boolean {
        val geometryIdentifier = when (geometry) {
            is ComponentGeometry.ComponentGeometrySimple -> geometry.identifier
            is ComponentGeometry.ComponentGeometryFull -> geometry.identifier
        }

        return geometryIdentifier == "minecraft:geometry.full_block"
    }

    private fun createCubeModel(
        identifier: Identifier,
        textures: BlockResourceDefinition.Textures?,
        materialInstances: ComponentMaterialInstances?
    ): JsonUnbakedModel {
        val textureMap = mutableMapOf<String, Either<SpriteIdentifier, String>>()
        // é€šè¿‡è¡Œä¸ºåŒ…å®šä¹‰äº†è´´å›¾
        materialInstances?.forEach { (key, value) ->
            value.texture?.let { texture ->
                context.resource.terrainTextureToJava(identifier.namespace, texture)?.let { texturePath ->
                    val spriteId = SpriteIdentifier(VersionCompat.BLOCK_ATLAS_TEXTURE, texturePath)
                    // æ”¯æŒå¤šé¢æè´¨æ˜ å°„
                    when (key) {
                        "*" -> textureMap["all"] = Either.left(spriteId)
                        "side" -> {
                            // "side" æ˜¯åŸºå²©ç‰ˆçš„ç®€å†™é”®,è¡¨ç¤ºå››ä¸ªæ°´å¹³ä¾§é¢
                            textureMap["north"] = Either.left(spriteId)
                            textureMap["south"] = Either.left(spriteId)
                            textureMap["east"] = Either.left(spriteId)
                            textureMap["west"] = Either.left(spriteId)
                        }
                        "north" -> textureMap["north"] = Either.left(spriteId)
                        "south" -> textureMap["south"] = Either.left(spriteId)
                        "east" -> textureMap["east"] = Either.left(spriteId)
                        "west" -> textureMap["west"] = Either.left(spriteId)
                        "up" -> textureMap["up"] = Either.left(spriteId)
                        "down" -> textureMap["down"] = Either.left(spriteId)
                        else -> BedrockLoader.logger.warn("[BedrockResourcePackLoader] Unknown material instance key '$key' for block $identifier")
                    }
                }
            }
        }
        // æ™®é€šæ–¹å—æƒ…å†µï¼šé€šè¿‡æè´¨åŒ…çš„blocks.jsonå®šä¹‰è´´å›¾
        when (textures) {
            is BlockResourceDefinition.Textures.TexturesAllFace -> {
                val texture = context.resource.terrainTexture[textures.all]?.textures
                if (texture == null) {
                    BedrockLoader.logger.warn("[BedrockResourcePackLoader] Block texture not found: ${textures.all}")
                } else {
                    context.resource.terrainTextureToJava(identifier.namespace, textures.all)?.let {
                        textureMap["all"] = Either.left(SpriteIdentifier(VersionCompat.BLOCK_ATLAS_TEXTURE, it))
                    }
                }
            }
            is BlockResourceDefinition.Textures.TexturesMultiFace -> {
                val directions = mapOf(
                    "up" to textures.up,
                    "down" to textures.down,
                    "north" to textures.north,
                    "south" to textures.south,
                    "east" to textures.east,
                    "west" to textures.west
                )
                for ((direction, textureKey) in directions) {
                    textureKey?.let {
                        context.resource.terrainTextureToJava(identifier.namespace, it)?.let { texture ->
                            textureMap[direction] = Either.left(SpriteIdentifier(VersionCompat.BLOCK_ATLAS_TEXTURE, texture))
                        }
                    }
                }
            }
            else -> BedrockLoader.logger.warn("[BedrockResourcePackLoader] Block $identifier has no textures defined.")
        }
        return createJsonUnbakedModel(Identifier.of("block/cube_all"), textureMap)
    }

    private fun createGeometryModel(
        identifier: Identifier,
        geometry: ComponentGeometry,
        materialInstances: ComponentMaterialInstances?
    ): BedrockGeometryModel? {
        val geometryIdentifier = when (geometry) {
            is ComponentGeometry.ComponentGeometrySimple -> geometry.identifier
            is ComponentGeometry.ComponentGeometryFull -> geometry.identifier
        }
        val geometryFactory = BedrockAddonsRegistryClient.geometries[geometryIdentifier]
        val materials = materialInstances?.mapValues { (_, material) ->
            val textureKey = material.texture ?: return@mapValues null
            val textures = context.resource.terrainTexture[textureKey]?.textures ?: return@mapValues null
            val texture = textures.firstOrNull()?.path ?: return@mapValues null
            val spriteId = SpriteIdentifier(
                VersionCompat.BLOCK_ATLAS_TEXTURE,
                Identifier.of(identifier.namespace, "block/${texture.substringAfterLast("/")}")
            )
            return@mapValues BedrockMaterialInstance(spriteId)
        }?.mapValues {
            it.value ?: BedrockMaterialInstance(
                SpriteIdentifier(
                    VersionCompat.BLOCK_ATLAS_TEXTURE,
                    MissingSprite.getMissingSpriteId()
                )
            )
        } ?: emptyMap()
        // ä½¿ç”¨å¸¦identifierçš„createæ–¹æ³•ï¼Œæ”¯æŒpermutationsåŠ¨æ€æè´¨åˆ‡æ¢
        return geometryFactory?.create(materials, identifier)
    }

    private fun registerBlockRenderLayer(
        identifier: Identifier,
        blockComponents: BlockComponents?
    ) {
        val materialInstances = blockComponents?.minecraftMaterialInstances
        val block = BedrockAddonsRegistry.blocks[identifier] ?: return
        val renderMethod = materialInstances?.get("*")?.render_method ?: return
        if (renderMethod == ComponentMaterialInstances.RenderMethod.alpha_test) {
            BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getCutout())
        } else if (renderMethod == ComponentMaterialInstances.RenderMethod.blend) {
            BlockRenderLayerMap.INSTANCE.putBlock(block, RenderLayer.getTranslucent())
        }
    }

    private fun createSpawnEggTextures(
        identifier: Identifier,
        clientEntity: EntityResourceDefinition.ClientEntityDescription?
    ) {
        val namespaceDir = this.namespaceDir(identifier.namespace)
        val spawnEggTexture = clientEntity?.spawn_egg?.texture
        if (spawnEggTexture != null) {
            val textures = context.resource.itemTexture[spawnEggTexture]?.textures
            val texture = textures?.firstOrNull()?.path
            if (texture == null) {
                BedrockLoader.logger.warn("[BedrockResourcePackLoader] Entity spawn egg texture not found: $spawnEggTexture")
                return
            }
            val path = "textures/item/${texture.substringAfterLast("/")}"
            val bedrockTexture = context.resource.textureImages[texture]
            if (bedrockTexture == null) {
                BedrockLoader.logger.warn("[BedrockResourcePackLoader] Entity spawn egg texture not found: $spawnEggTexture")
                return
            }
            // ç»Ÿä¸€è½¬æ¢ä¸ºPNGæ ¼å¼ï¼ˆMinecraftåŽŸç”Ÿçº¹ç†ç³»ç»Ÿåªæ”¯æŒPNGï¼‰
            val file = namespaceDir.resolve(path + ".png")
            bedrockTexture.image.let { image ->
                ImageIO.write(image, "png", file)
            }
        }
    }

    /**
     * ç›´æŽ¥åˆ›å»ºä¸€ä¸ªç»§æ‰¿äºŽå¯¹åº”å®žä½“çš„ç”Ÿç‰©è›‹ç‰©å“
     */
    private fun createSpawnEggModel(
        identifier: Identifier,
        clientEntity: EntityResourceDefinition.ClientEntityDescription?
    ) {
        context.behavior.entities[identifier]?.description?.is_spawnable?.let {
            val entityName = identifier.path
            val itemIdentifier = Identifier.of(identifier.namespace, "${entityName}_spawn_egg")
            val spawnEggTexture = clientEntity?.spawn_egg?.texture
            if (spawnEggTexture != null) {
                val textureMap = mutableMapOf<String, Either<SpriteIdentifier, String>>()
                context.resource.itemTextureToJava(itemIdentifier.namespace, spawnEggTexture)?.let {
                    textureMap["layer0"] = Either.left(SpriteIdentifier(VersionCompat.BLOCK_ATLAS_TEXTURE, it))
                }
                BedrockAddonsRegistryClient.itemModels[itemIdentifier] = createJsonUnbakedModel(Identifier.of("item/generated"), textureMap)
                //? if >=1.21.4 {
                /*createItemDefinition(itemIdentifier, "${itemIdentifier.namespace}:item/${itemIdentifier.path}")
                *///?}
            } else {
                //? if >=1.21.4 {
                /*// 1.21.4: DelegatingUnbakedModelå·²ç§»é™¤ï¼Œä¸æ³¨å†Œæ¨¡åž‹ä»¥ä½¿ç”¨é»˜è®¤æ¨¡åž‹
                BedrockLoader.logger.debug("[BedrockResourcePackLoader] 1.21.4: Skipping spawn egg model for: $itemIdentifier")
                *///?} else {
                BedrockAddonsRegistryClient.itemModels[itemIdentifier] = net.fabricmc.fabric.api.client.model.loading.v1.DelegatingUnbakedModel(Identifier.of("item/template_spawn_egg"))
                //?}
            }
        }
    }

    /**
     * ä»ŽClientEntityè¯»å–éœ€è¦çš„æ¨¡åž‹ï¼Œç„¶åŽå°†å¯¹åº”çš„æ¨¡åž‹ä¿å­˜åˆ°Javaæè´¨åŒ…ä¸­ï¼ˆå¯¹åº”å‘½åç©ºé—´ï¼‰
     * åŒæ—¶æ³¨å†ŒåŠ¨ç”»é…ç½®å’Œæè´¨ç±»åž‹
     */
    private fun createEntityModel(
        identifier: Identifier,
        clientEntity: EntityResourceDefinition.ClientEntityDescription?
    ) {
        val (model, material) = createClientEntityModel(identifier, clientEntity) ?: return
        BedrockAddonsRegistryClient.entityModel[identifier] = model
        BedrockAddonsRegistryClient.entityMaterial[identifier] = material

        // æ³¨å†ŒåŠ¨ç”»é…ç½®ï¼ˆç”¨äºŽæ‡’åŠ è½½åˆ›å»º EntityAnimationManagerï¼‰
        registerEntityAnimationConfig(identifier, clientEntity)

        // æ³¨å†Œç¼©æ”¾é…ç½®
        registerEntityScaleConfig(identifier, clientEntity)
    }

    /**
     * æ³¨å†Œå®žä½“åŠ¨ç”»é…ç½®
     *
     * è§£æž ClientEntity ä¸­çš„ animations å’Œ scripts.animate é…ç½®ï¼Œ
     * å­˜å‚¨åˆ° BedrockAddonsRegistryClient ä¾›å®žä½“è¿è¡Œæ—¶ä½¿ç”¨ã€‚
     */
    private fun registerEntityAnimationConfig(
        identifier: Identifier,
        clientEntity: EntityResourceDefinition.ClientEntityDescription?
    ) {
        val config = buildAnimationConfig(identifier, clientEntity, "entity") ?: return
        BedrockAddonsRegistryClient.entityAnimationConfigs[identifier] = config
        BedrockLoader.logger.debug("[BedrockResourcePackLoader] Registered animation config for entity $identifier: ${config.autoPlayList.size} auto-play animations")
    }

    /**
     * æ³¨å†Œæ–¹å—å®žä½“åŠ¨ç”»é…ç½®
     *
     * è§£æž ClientEntity ä¸­çš„ animations å’Œ scripts.animate é…ç½®ï¼Œ
     * å­˜å‚¨åˆ° BedrockAddonsRegistryClient.blockEntityAnimationConfigs ä¾›æ–¹å—å®žä½“è¿è¡Œæ—¶ä½¿ç”¨ã€‚
     */
    private fun registerBlockEntityAnimationConfig(
        identifier: Identifier,
        clientEntity: EntityResourceDefinition.ClientEntityDescription?
    ) {
        val config = buildAnimationConfig(identifier, clientEntity, "block entity") ?: return
        BedrockAddonsRegistryClient.blockEntityAnimationConfigs[identifier] = config
        BedrockLoader.logger.info("[BedrockResourcePackLoader] Registered animation config for block entity $identifier: ${config.autoPlayList.size} auto-play animations")
    }

    private fun buildAnimationConfig(
        identifier: Identifier,
        clientEntity: EntityResourceDefinition.ClientEntityDescription?,
        ownerName: String
    ): EntityAnimationConfig? {
        if (clientEntity == null) return null

        val animationMap = (clientEntity.animations ?: emptyMap()).toMutableMap()
        val rootAliases = LinkedHashSet<String>()
        rootAliases.addAll(parseAnimateAliases(clientEntity.scripts?.animate))

        clientEntity.animation_controllers.orEmpty().forEach { reference ->
            if (!evaluateCondition(reference.condition)) {
                return@forEach
            }
            val alias = reference.alias?.takeIf { it.isNotBlank() } ?: reference.id
            animationMap.putIfAbsent(alias, reference.id)
            rootAliases += alias
        }

        if (animationMap.isEmpty() || rootAliases.isEmpty()) return null

        val autoPlayList = resolveAutoPlayAliases(identifier, rootAliases.toList(), animationMap)
        if (autoPlayList.isEmpty()) return null

        val animations = mutableMapOf<String, net.easecation.bedrockloader.bedrock.definition.AnimationDefinition.Animation>()
        autoPlayList.forEach { alias ->
            val animId = animationMap[alias] ?: return@forEach
            val anim = context.resource.animations[animId]
            if (anim != null) {
                animations[animId] = anim
            } else {
                BedrockLoader.logger.warn("[BedrockResourcePackLoader] Animation not found: $animId for $ownerName $identifier")
            }
        }
        if (animations.isEmpty()) return null

        return EntityAnimationConfig(
            animationMap = animationMap,
            animations = animations,
            autoPlayList = autoPlayList
        )
    }

    private fun parseAnimateAliases(animate: List<Any>?): List<String> {
        return animate?.mapNotNull { item ->
            when (item) {
                is String -> item
                is Map<*, *> -> item.keys.firstOrNull()?.toString()
                else -> null
            }
        } ?: emptyList()
    }

    private fun resolveAutoPlayAliases(
        identifier: Identifier,
        rootAliases: List<String>,
        animationMap: MutableMap<String, String>
    ): List<String> {
        val resolvedAliases = linkedSetOf<String>()
        val pending = ArrayDeque<String>()
        val visitedAliases = mutableSetOf<String>()
        val visitedControllers = mutableSetOf<String>()

        rootAliases.filter { it.isNotBlank() }.forEach { pending.add(it) }

        while (pending.isNotEmpty()) {
            val alias = pending.removeFirst()
            if (!visitedAliases.add(alias)) continue

            val mappedId = animationMap[alias] ?: alias
            when {
                context.resource.animations.containsKey(mappedId) -> {
                    animationMap.putIfAbsent(alias, mappedId)
                    resolvedAliases += alias
                }
                isControllerId(mappedId) -> {
                    if (!visitedControllers.add(mappedId)) continue
                    val controller = context.resource.animationControllers[mappedId]
                    if (controller == null) {
                        BedrockLoader.logger.warn("[BedrockResourcePackLoader] Animation controller not found: $mappedId for entity $identifier")
                        continue
                    }
                    extractControllerAnimationAliases(controller).forEach { pending.add(it) }
                }
                animationMap.containsKey(mappedId) && mappedId != alias -> pending.add(mappedId)
                else -> {
                    val fallback = builtinAnimation(mappedId)
                    if (fallback != null) {
                        context.resource.animations.putIfAbsent(mappedId, fallback)
                        animationMap.putIfAbsent(alias, mappedId)
                        resolvedAliases += alias
                    }
                }
            }
        }

        return resolvedAliases.toList()
    }

    private fun isControllerId(id: String): Boolean {
        return id.startsWith("controller.animation.", ignoreCase = true) ||
            context.resource.animationControllers.containsKey(id)
    }

    private fun extractControllerAnimationAliases(
        controller: net.easecation.bedrockloader.bedrock.definition.EntityAnimationControllerDefinition.AnimationController
    ): List<String> {
        val aliases = linkedSetOf<String>()
        controller.states.values.forEach { state ->
            state.animations.orEmpty().forEach { animationEntry ->
                when (animationEntry) {
                    is String -> aliases += animationEntry
                    is Map<*, *> -> {
                        animationEntry.keys.firstOrNull()?.toString()?.let { aliases += it }
                    }
                }
            }
        }
        return aliases.toList()
    }

    private fun builtinAnimation(animationId: String): net.easecation.bedrockloader.bedrock.definition.AnimationDefinition.Animation? {
        return when (animationId) {
            "animation.arrow.move",
            "animation.wither_skull.move" -> net.easecation.bedrockloader.bedrock.definition.AnimationDefinition.Animation(
                loop = net.easecation.bedrockloader.bedrock.definition.AnimationDefinition.LoopMode.Loop,
                animation_length = null,
                bones = null
            )
            else -> null
        }
    }

    private fun evaluateCondition(condition: String?): Boolean {
        if (condition.isNullOrBlank()) return true
        val result = MolangExpressionEvaluator.evaluate(condition)
        return result != null && result != 0.0
    }

    /**
     * è§£æž scripts.scale å€¼ä¸º Float
     * æ”¯æŒæ ¼å¼: æ•°å­—ã€å­—ç¬¦ä¸²æ•°å­—
     * Molang è¡¨è¾¾å¼è¿”å›žé»˜è®¤å€¼ 1.0f
     */
    private fun parseScaleValue(scale: Any?): Float {
        if (scale == null) return 1.0f
        return when (scale) {
            is Number -> scale.toFloat()
            is String -> scale.toFloatOrNull()
                ?: MolangExpressionEvaluator.evaluate(scale)?.toFloat()
                ?: run {
                    BedrockLoader.logger.warn("[BedrockResourcePackLoader] Molang scale expression not supported, using default 1.0: $scale")
                    1.0f
                }
            else -> 1.0f
        }
    }

    /**
     * æ³¨å†Œå®žä½“ç¼©æ”¾é…ç½®
     */
    private fun registerEntityScaleConfig(
        identifier: Identifier,
        clientEntity: EntityResourceDefinition.ClientEntityDescription?
    ) {
        val scripts = clientEntity?.scripts ?: return
        val scale = parseScaleValue(scripts.scale)
        if (scale != 1.0f) {
            BedrockAddonsRegistryClient.entityScaleConfigs[identifier] = scale
            BedrockLoader.logger.info("[BedrockResourcePackLoader] Registered scale $scale for entity $identifier")
        }
    }

    /**
     * æ³¨å†Œæ–¹å—å®žä½“ç¼©æ”¾é…ç½®
     */
    private fun registerBlockEntityScaleConfig(
        identifier: Identifier,
        clientEntity: EntityResourceDefinition.ClientEntityDescription?
    ) {
        val scripts = clientEntity?.scripts ?: return
        val scale = parseScaleValue(scripts.scale)
        if (scale != 1.0f) {
            BedrockAddonsRegistryClient.blockEntityScaleConfigs[identifier] = scale
            BedrockLoader.logger.info("[BedrockResourcePackLoader] Registered scale $scale for block entity $identifier")
        }
    }

    /**
     * ä»ŽClientEntityè¯»å–éœ€è¦çš„æ¨¡åž‹å’Œæè´¨ç±»åž‹
     *
     * @return Pair(æ¨¡åž‹, æè´¨ç±»åž‹)ï¼Œå¦‚æžœå¤±è´¥åˆ™è¿”å›ž null
     */
    private fun createClientEntityModel(
        identifier: Identifier,
        clientEntity: EntityResourceDefinition.ClientEntityDescription?
    ): Pair<BedrockGeometryModel, BedrockEntityMaterial>? {
        if (clientEntity == null) {
            return null
        }

        val selectedController = selectRenderControllerReference(clientEntity)
        if (selectedController != null) {
            val renderController = context.resource.renderControllers[selectedController.id]
            if (renderController != null) {
                return createRenderControllerModel(identifier, clientEntity, renderController)
            }
        }

        if (clientEntity.render_controllers.orEmpty().isNotEmpty()) {
            BedrockLoader.logger.debug("[BedrockResourcePackLoader] Render controller not found for entity {}, using client_entity defaults", clientEntity.identifier)
        }
        return createDefaultClientEntityModel(identifier, clientEntity)
    }

    private fun selectRenderControllerReference(
        clientEntity: EntityResourceDefinition.ClientEntityDescription
    ): EntityResourceDefinition.RenderControllerReference? {
        val controllers = clientEntity.render_controllers.orEmpty()
        if (controllers.isEmpty()) return null

        val existing = controllers.filter { context.resource.renderControllers.containsKey(it.id) }
        if (existing.isEmpty()) return null

        existing.firstOrNull { evaluateCondition(it.condition) }?.let { return it }
        existing.firstOrNull { it.condition.isNullOrBlank() }?.let { return it }
        return existing.first()
    }

    private fun extractAlias(molang: String, lowercasePrefix: String, uppercasePrefix: String): String {
        return molang.substringAfter(lowercasePrefix).substringAfter(uppercasePrefix)
    }

    private fun resolveMappedValue(values: Map<String, String>?, alias: String?): String? {
        if (values.isNullOrEmpty()) {
            return null
        }
        if (alias != null) {
            values[alias]?.let { return it }
        }
        values["default"]?.let { return it }
        return values.values.firstOrNull()
    }

    private fun createModelFromGeometryAndTexture(
        identifier: Identifier,
        geometryIdentifier: String,
        texture: String,
        material: BedrockEntityMaterial
    ): Pair<BedrockGeometryModel, BedrockEntityMaterial>? {
        val resolvedGeometryIdentifier = resolveEntityGeometryIdentifier(identifier, geometryIdentifier)
        val geometryFactory = resolvedGeometryIdentifier?.let { BedrockAddonsRegistryClient.geometries[it] }
        if (geometryFactory == null) {
            BedrockLoader.logger.warn("[BedrockResourcePackLoader] Geometry not found: $geometryIdentifier for entity $identifier")
            return null
        }

        if (!context.resource.textureImages.containsKey(texture)) {
            BedrockLoader.logger.warn("[BedrockResourcePackLoader] Entity texture not found: $texture")
        }

        // Use block atlas path (block/entity_xxx) to match how textures are packed in createEntityTextures.
        val normalizedTextureName = normalizeTextureLeaf(texture)
        val textureId = Identifier.tryParse("${identifier.namespace}:block/entity_$normalizedTextureName")
            ?: MissingSprite.getMissingSpriteId()
        val spriteId = SpriteIdentifier(
            VersionCompat.BLOCK_ATLAS_TEXTURE,
            textureId
        )
        val materials = mapOf("*" to BedrockMaterialInstance(spriteId))
        val model = geometryFactory.create(materials)
        return Pair(model, material)
    }

    private fun resolveEntityGeometryIdentifier(identifier: Identifier, requestedGeometry: String): String? {
        if (BedrockAddonsRegistryClient.geometries.containsKey(requestedGeometry)) {
            return requestedGeometry
        }

        val caseInsensitive = BedrockAddonsRegistryClient.geometries.keys.firstOrNull {
            it.equals(requestedGeometry, ignoreCase = true)
        }
        if (caseInsensitive != null) {
            return caseInsensitive
        }

        val entityPath = identifier.path
        val candidates = listOf(
            "geometry.$entityPath",
            "geometry.$entityPath.geo"
        )
        for (candidate in candidates) {
            if (BedrockAddonsRegistryClient.geometries.containsKey(candidate)) {
                BedrockLoader.logger.warn(
                    "[BedrockResourcePackLoader] Using fallback geometry {} for entity {} (requested {})",
                    candidate,
                    identifier,
                    requestedGeometry
                )
                return candidate
            }
        }

        val suffixMatch = BedrockAddonsRegistryClient.geometries.keys.firstOrNull {
            it.endsWith(".$entityPath") || it.endsWith(".$entityPath.geo")
        }
        if (suffixMatch != null) {
            BedrockLoader.logger.warn(
                "[BedrockResourcePackLoader] Using suffix fallback geometry {} for entity {} (requested {})",
                suffixMatch,
                identifier,
                requestedGeometry
            )
            return suffixMatch
        }

        return null
    }

    private fun registerBuiltinProjectileGeometryFallbacks() {
        registerBuiltinArrowGeometryFallback("geometry.arrow")
    }

    private fun registerBuiltinArrowGeometryFallback(geometryId: String) {
        if (BedrockAddonsRegistryClient.geometries.containsKey(geometryId)) return

        val arrowBody = GeometryDefinition.Cube(
            inflate = null,
            mirror = null,
            origin = listOf(-0.5, -0.5, -8.0),
            pivot = null,
            reset = null,
            rotation = null,
            size = listOf(1.0, 1.0, 16.0),
            uv = GeometryDefinition.Uv.UvBox(listOf(0, 0))
        )
        val arrowFin1 = GeometryDefinition.Cube(
            inflate = null,
            mirror = null,
            origin = listOf(-2.0, -1.5, -6.0),
            pivot = null,
            reset = null,
            rotation = null,
            size = listOf(4.0, 0.0, 4.0),
            uv = GeometryDefinition.Uv.UvBox(listOf(0, 4))
        )
        val arrowFin2 = GeometryDefinition.Cube(
            inflate = null,
            mirror = null,
            origin = listOf(-2.0, -1.5, -6.0),
            pivot = null,
            reset = null,
            rotation = listOf(90.0, 0.0, 0.0),
            size = listOf(4.0, 0.0, 4.0),
            uv = GeometryDefinition.Uv.UvBox(listOf(0, 4))
        )
        val rootBone = GeometryDefinition.Bone(
            binding = null,
            cubes = listOf(arrowBody, arrowFin1, arrowFin2),
            debug = null,
            inflate = null,
            locators = null,
            mirror = null,
            name = "root",
            parent = null,
            pivot = listOf(0.0, 0.0, 0.0),
            poly_mesh = null,
            render_group_id = null,
            rotation = null,
            texture_meshes = null
        )
        val model = GeometryDefinition.Model(
            description = GeometryDefinition.Description(
                identifier = geometryId,
                texture_width = 32,
                texture_height = 32,
                visible_bounds_offset = listOf(0.0, 0.0, 0.0),
                visible_bounds_width = 1.0,
                visible_bounds_height = 1.0
            ),
            bones = listOf(rootBone),
            cape = null
        )
        BedrockAddonsRegistryClient.geometries[geometryId] = BedrockGeometryModel.Factory(model)
        BedrockLoader.logger.info("[BedrockResourcePackLoader] Registered builtin projectile geometry fallback: $geometryId")
    }

    private fun createDefaultClientEntityModel(
        identifier: Identifier,
        clientEntity: EntityResourceDefinition.ClientEntityDescription
    ): Pair<BedrockGeometryModel, BedrockEntityMaterial>? {
        val geometryIdentifier = resolveMappedValue(clientEntity.geometry, "default") ?: return null
        val texture = resolveMappedValue(clientEntity.textures, "default") ?: return null
        val materialName = resolveMappedValue(clientEntity.materials, "default")
        val material = materialName?.let(BedrockEntityMaterial::fromBedrockName) ?: BedrockEntityMaterial.ENTITY
        return createModelFromGeometryAndTexture(identifier, geometryIdentifier, texture, material)
    }

    /**
     * ä»ŽRenderControllerè¯»å–éœ€è¦çš„æ¨¡åž‹å’Œæè´¨ç±»åž‹
     *
     * @return Pair(æ¨¡åž‹, æè´¨ç±»åž‹)ï¼Œå¦‚æžœå¤±è´¥åˆ™è¿”å›ž null
     */
    private fun createRenderControllerModel(
        identifier: Identifier,
        clientEntity: EntityResourceDefinition.ClientEntityDescription,
        renderController: EntityRenderControllerDefinition.RenderController
    ): Pair<BedrockGeometryModel, BedrockEntityMaterial>? {
        val geometryAlias = extractAlias(renderController.geometry, "geometry.", "Geometry.")
        val geometryIdentifier = resolveMappedValue(clientEntity.geometry, geometryAlias) ?: return null

        val textureAlias = renderController.textures?.firstOrNull()?.let {
            extractAlias(it, "texture.", "Texture.")
        }
        val texture = resolveMappedValue(clientEntity.textures, textureAlias) ?: return null

        // è§£æžæè´¨ç±»åž‹
        // RenderController.materials æ ¼å¼: [{"*": "Material.default"}, {"body": "Material.emissive"}]
        // æˆ‘ä»¬å–ç¬¬ä¸€ä¸ªé€šé…ç¬¦æè´¨ï¼ˆ"*"ï¼‰çš„å€¼
        val entityMaterial = parseMaterialFromRenderController(renderController, clientEntity)

        return createModelFromGeometryAndTexture(identifier, geometryIdentifier, texture, entityMaterial)
    }

    /**
     * ä»Ž RenderController è§£æžæè´¨ç±»åž‹
     *
     * æ•°æ®æµï¼šRenderController.materials â†’ ClientEntity.materials â†’ BedrockEntityMaterial
     */
    private fun parseMaterialFromRenderController(
        renderController: EntityRenderControllerDefinition.RenderController,
        clientEntity: EntityResourceDefinition.ClientEntityDescription
    ): BedrockEntityMaterial {
        // ä»Ž RenderController.materials èŽ·å–æè´¨åˆ«å
        // æ ¼å¼: [{"*": "Material.default"}, {"bone_name": "Material.xxx"}]
        val materialsList = renderController.materials
        if (materialsList.isEmpty()) {
            return BedrockEntityMaterial.ENTITY
        }

        // ä¼˜å…ˆæŸ¥æ‰¾é€šé…ç¬¦æè´¨ "*"ï¼Œå¦åˆ™å–ç¬¬ä¸€ä¸ª
        val materialEntry = materialsList.find { it.containsKey("*") } ?: materialsList.firstOrNull()
        val materialMolang = materialEntry?.values?.firstOrNull() ?: return BedrockEntityMaterial.ENTITY

        // è§£æž Molang è¡¨è¾¾å¼ï¼Œæå–æè´¨åˆ«å
        // æ ¼å¼: "Material.default" æˆ– "material.default"
        val materialAlias = materialMolang
            .substringAfter("Material.")
            .substringAfter("material.")

        // ä»Ž ClientEntity.materials æŸ¥æ‰¾å®žé™…æè´¨åç§°
        // ClientEntity.materials æ ¼å¼: {"default": "entity", "emissive": "entity_emissive"}
        val actualMaterialName = clientEntity.materials?.get(materialAlias) ?: materialAlias

        return BedrockEntityMaterial.fromBedrockName(actualMaterialName)
    }

    /**
     * ä»ŽClientEntityè¯»å–éœ€è¦çš„è´´å›¾ï¼Œç„¶åŽå°†å¯¹åº”çš„è´´å›¾æ–‡ä»¶ä¿å­˜åˆ°javaæè´¨åŒ…ä¸­ï¼ˆå¯¹åº”å‘½åç©ºé—´ï¼‰
     * çº¹ç†ä¿å­˜åˆ° textures/entity/ ç›®å½•ï¼ˆMinecraft æ ‡å‡†å®žä½“è´´å›¾ä½ç½®ï¼‰
     * ä¾‹å¦‚ï¼štextures/entity/bench.png â†’ textures/entity/bench.png
     */
    private fun createEntityTextures(
        identifier: Identifier,
        clientEntity: EntityResourceDefinition.ClientEntityDescription?
    ) {
        val namespaceDir = this.namespaceDir(identifier.namespace)
        clientEntity?.textures?.forEach { (_, texture) ->
            val bedrockTexture = context.resource.textureImages[texture]
            if (bedrockTexture == null) {
                BedrockLoader.logger.warn("[BedrockResourcePackLoader] Entity texture not found: $texture")
                return
            }
            // ä¿å­˜åˆ° textures/block/ ç›®å½•ï¼Œä½¿ç”¨ entity_ å‰ç¼€é¿å…å†²çª
            // è¿™æ ·çº¹ç†æ‰èƒ½è¢« BLOCK_ATLAS æ­£ç¡®åŠ è½½ï¼ˆç”¨äºŽ inventory æ¸²æŸ“ï¼‰
            // ç»Ÿä¸€è½¬æ¢ä¸ºPNGæ ¼å¼ï¼ˆMinecraftåŽŸç”Ÿçº¹ç†ç³»ç»Ÿåªæ”¯æŒPNGï¼‰
            val fileName = "entity_" + normalizeTextureLeaf(texture)
            val file = namespaceDir.resolve("textures/block/$fileName.png")
            file.parentFile.mkdirs()
            bedrockTexture.image.let { image ->
                ImageIO.write(image, "png", file)
            }
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

    /**
     * æ³¨å†Œå®žä½“æ¸²æŸ“å™¨
     */
    private fun registerEntityRenderController(identifier: Identifier) {
        val entityType = BedrockAddonsRegistry.entities[identifier] ?: return
        val model = BedrockAddonsRegistryClient.entityModel[identifier]
        if (model == null) {
            BedrockLoader.logger.warn("[BedrockResourcePackLoader] Missing client model for entity $identifier, using EmptyEntityRenderer to avoid client crash")
            EntityRendererRegistry.register(entityType) { context ->
                EmptyEntityRenderer(context)
            }
            return
        }
        val material = BedrockAddonsRegistryClient.entityMaterial[identifier] ?: BedrockEntityMaterial.ENTITY
        val spriteId = model.materials["*"]?.spriteId ?: return
        // å®žä½“æ¸²æŸ“å™¨éœ€è¦å®Œæ•´çš„çº¹ç†è·¯å¾„ï¼ˆå¸¦ textures/ å‰ç¼€å’Œ .png æ‰©å±•åï¼‰
        // SpriteIdentifier çš„ textureId æ ¼å¼æ˜¯ block/entity_xxxï¼ˆä¸å¸¦ textures/ å’Œ .pngï¼‰
        val entityTextureId = Identifier.of(spriteId.textureId.namespace, "textures/" + spriteId.textureId.path + ".png")
        EntityRendererRegistry.register(entityType) { context ->
            EntityDataDrivenRenderer.create(context, model, 0.5f, entityTextureId, identifier, material)
        }
    }

    /**
     * ä¸ºæ–¹å—ç”ŸæˆTag JSONæ–‡ä»¶
     *
     * å°†åŸºå²©ç‰ˆæ–¹å—çš„ tag:* ç»„ä»¶è½¬æ¢ä¸ºJavaç‰ˆæ•°æ®åŒ…Tagæ–‡ä»¶ã€‚
     * ä¾‹å¦‚ï¼š`tag:wood` â†’ `data/minecraft/tags/blocks/wood.json`
     *
     * @param identifier æ–¹å—æ ‡è¯†ç¬¦
     * @param tags æ–¹å—çš„Tagé›†åˆï¼ˆä»ŽBlockComponents.tagsæå–ï¼‰
     */
    private fun createBlockTags(
        identifier: Identifier,
        tags: Set<String>
    ) {
        if (tags.isEmpty()) return

        // æŒ‰å‘½åç©ºé—´å’Œè·¯å¾„åˆ†ç»„tag
        // ä¾‹å¦‚ï¼š"wood" â†’ minecraft:wood â†’ data/minecraft/tags/blocks/wood.json
        //      "c:ores" â†’ c:ores â†’ data/c/tags/blocks/ores.json
        val tagsByNamespace = mutableMapOf<String, MutableMap<String, MutableList<String>>>()

        for (tagName in tags) {
            // è·³è¿‡ç©ºç™½tagåç§°
            if (tagName.isBlank()) {
                BedrockLoader.logger.warn("[BedrockResourcePackLoader] Skipping empty tag for block $identifier")
                continue
            }

            // è§£æžtagåç§°ï¼Œæå–å‘½åç©ºé—´å’Œè·¯å¾„
            val (namespace, path) = if (tagName.contains(':')) {
                val parts = tagName.split(':', limit = 2)
                parts[0].lowercase() to parts[1]  // å‘½åç©ºé—´å¼ºåˆ¶å°å†™
            } else {
                "minecraft" to tagName
            }

            // å°†æ–¹å—IDæ·»åŠ åˆ°å¯¹åº”çš„tag
            tagsByNamespace
                .getOrPut(namespace) { mutableMapOf() }
                .getOrPut(path) { mutableListOf() }
                .add(identifier.toString())
        }

        // ä¸ºæ¯ä¸ªtagç”ŸæˆJSONæ–‡ä»¶
        for ((namespace, tagMap) in tagsByNamespace) {
            for ((path, blockIds) in tagMap) {
                createTagFile(namespace, path, blockIds)
            }
        }
    }

    /**
     * åˆ›å»ºå•ä¸ªTag JSONæ–‡ä»¶
     *
     * æ”¯æŒTagåˆå¹¶ï¼šå¦‚æžœæ–‡ä»¶å·²å­˜åœ¨ï¼Œä¼šè¯»å–å¹¶åˆå¹¶æ–¹å—IDåˆ—è¡¨ã€‚
     * æ”¯æŒå­ç›®å½•ï¼šè·¯å¾„å¦‚ `mineable/pickaxe` ä¼šåˆ›å»º `mineable/pickaxe.json`
     *
     * @param namespace Tagå‘½åç©ºé—´ï¼ˆå¦‚ minecraft, cï¼‰
     * @param path Tagè·¯å¾„ï¼ˆå¦‚ wood, mineable/pickaxeï¼‰
     * @param blockIds è¦æ·»åŠ åˆ°Tagçš„æ–¹å—IDåˆ—è¡¨
     */
    private fun createTagFile(
        namespace: String,
        path: String,
        blockIds: List<String>
    ) {
        val validBlockIds = filterExistingBlockIds(blockIds)
        if (validBlockIds.isEmpty()) {
            return
        }

        // æž„å»ºæ–‡ä»¶è·¯å¾„ï¼ˆæ”¯æŒå­ç›®å½•ï¼Œå¦‚ mineable/pickaxeï¼‰
        val tagDir = if (path.contains('/')) {
            // æœ‰å­ç›®å½•çš„æƒ…å†µ
            val parentPath = path.substringBeforeLast('/')
            javaResDir.resolve("data/$namespace/tags/block/$parentPath")
        } else {
            // æ— å­ç›®å½•çš„æƒ…å†µ
            javaResDir.resolve("data/$namespace/tags/block")
        }

        // ç¡®ä¿ç›®å½•å­˜åœ¨
        tagDir.mkdirs()

        // èŽ·å–æ–‡ä»¶åï¼ˆè·¯å¾„çš„æœ€åŽä¸€éƒ¨åˆ†ï¼‰
        val fileName = path.substringAfterLast('/') + ".json"
        val tagFile = File(tagDir, fileName)

        // å¦‚æžœæ–‡ä»¶å·²å­˜åœ¨ï¼Œè¯»å–å¹¶åˆå¹¶
        val existingBlocks = if (tagFile.exists()) {
            try {
                val existingJson = GsonUtil.GSON.fromJson(
                    tagFile.readText(StandardCharsets.UTF_8),
                    JavaBlockTag::class.java
                )
                existingJson.values.toMutableList()
            } catch (e: Exception) {
                BedrockLoader.logger.warn("[BedrockResourcePackLoader] Failed to read existing tag file: $tagFile", e)
                mutableListOf()
            }
        } else {
            mutableListOf()
        }

        // åˆå¹¶æ–°æ–¹å—IDï¼ˆåŽ»é‡å¹¶æŽ’åºï¼‰
        val allBlocks = (existingBlocks + validBlockIds).distinct().sorted()

        // åˆ›å»ºTagå¯¹è±¡
        val tag = JavaBlockTag(
            replace = false,  // ä¸æ›¿æ¢çŽ°æœ‰Tagï¼Œä¸Žå…¶ä»–æ•°æ®åŒ…åˆå¹¶
            values = allBlocks
        )

        // å†™å…¥æ–‡ä»¶
        try {
            Files.newBufferedWriter(tagFile.toPath(), StandardCharsets.UTF_8).use { writer ->
                GsonUtil.GSON.toJson(tag, writer)
            }
            BedrockLoader.logger.debug("[BedrockResourcePackLoader] Generated tag: $namespace:$path with ${allBlocks.size} blocks")
        } catch (e: Exception) {
            BedrockLoader.logger.error("[BedrockResourcePackLoader] Failed to write tag file: $tagFile", e)
        }
    }

    /**
     * åŠ è½½åŸºå²©ç‰ˆåŽŸç”Ÿæ–¹å—Tagå®šä¹‰
     *
     * ä»Ž `src/main/resources/vanilla_block_tags.json` è¯»å–åŸºå²©ç‰ˆå®˜æ–¹å®šä¹‰çš„æ–¹å—Tagï¼Œ
     * å¹¶ä¸ºæ¯ä¸ªTagç”Ÿæˆå¯¹åº”çš„Javaç‰ˆæ•°æ®åŒ…Tagæ–‡ä»¶ã€‚
     *
     * è¿™äº›Tagå°†è¢«ç”¨äºŽMolangæ¡ä»¶æŸ¥è¯¢ï¼Œä¾‹å¦‚ï¼š
     * - `query.block_neighbor_has_all_tags(0, -1, 0, 'dirt')`
     * - `query.block_neighbor_has_any_tag(0, 0, -1, 'wood', 'log')`
     *
     * æ–‡ä»¶æ ¼å¼è¯´æ˜Žï¼š
     * - Tagåç§°æ ¼å¼ï¼š`` `dirt` `` æˆ– `` `minecraft:crop` `` ï¼ˆè¢«åå¼•å·åŒ…è£¹ï¼‰
     * - æ–¹å—IDæ ¼å¼ï¼š`` `minecraft:stone` `` ï¼ˆè¢«åå¼•å·åŒ…è£¹ï¼ŒåŒ…å«å®Œæ•´å‘½åç©ºé—´ï¼‰
     */
    private fun filterExistingBlockIds(blockIds: List<String>): List<String> {
        return blockIds.filter { rawId ->
            val id = Identifier.tryParse(rawId)
            if (id == null) {
                BedrockLoader.logger.debug("[BedrockResourcePackLoader] Skip invalid block id in tag: $rawId")
                return@filter false
            }

            val exists = Registries.BLOCK.containsId(id)
            if (!exists) {
                BedrockLoader.logger.debug("[BedrockResourcePackLoader] Skip unknown block id in tag for this MC version: $rawId")
            }
            exists
        }
    }

    private fun loadVanillaBlockTags() {
        try {
            // ä»Žresourcesè¯»å–vanilla_block_tags.json
            val inputStream = BedrockLoader::class.java.getResourceAsStream("/vanilla_block_tags.json")
            if (inputStream == null) {
                BedrockLoader.logger.warn("[VanillaBlockTags] vanilla_block_tags.json not found in resources, skipping")
                return
            }

            // è§£æžJSON
            val data = inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                GsonUtil.GSON.fromJson(reader, VanillaBlockTagsData::class.java)
            }

            if (data.rows.isEmpty()) {
                BedrockLoader.logger.warn("[VanillaBlockTags] No tag data found in vanilla_block_tags.json")
                return
            }

            // åŠ è½½åŸºå²©ç‰ˆåˆ°Javaç‰ˆçš„æ–¹å—IDæ˜ å°„
            val blockIdMapping = loadBedrockToJavaBlockIdMapping()
            BedrockLoader.logger.info("[VanillaBlockTags] Loaded ${blockIdMapping.size} block ID mappings")

            // å¤„ç†æ¯ä¸ªTagè¡Œ
            var successCount = 0
            var totalFilteredBlocks = 0
            var totalMappedBlocks = 0

            for (row in data.rows) {
                try {
                    // åŽ»æŽ‰åå¼•å·ï¼Œæå–Tagåç§°
                    val tagName = row.tag.trim('`', ' ')
                    if (tagName.isBlank()) {
                        BedrockLoader.logger.warn("[VanillaBlockTags] Skipping empty tag name")
                        continue
                    }

                    // åŽ»æŽ‰åå¼•å·ï¼Œæå–æ–¹å—IDåˆ—è¡¨ï¼ˆåŸºå²©ç‰ˆIDï¼‰
                    val bedrockBlockIds = row.vanilla_usage.map { it.trim('`', ' ') }.filter { it.isNotBlank() }
                    if (bedrockBlockIds.isEmpty()) {
                        BedrockLoader.logger.warn("[VanillaBlockTags] Tag '$tagName' has no block IDs, skipping")
                        continue
                    }

                    // åº”ç”¨æ˜ å°„ï¼šåŸºå²©ç‰ˆID -> Javaç‰ˆID
                    val javaBlockIds = mutableListOf<String>()
                    for (bedrockId in bedrockBlockIds) {
                        // ç§»é™¤minecraft:å‰ç¼€ï¼ˆå¦‚æžœæœ‰ï¼‰
                        val cleanBedrockId = bedrockId.removePrefix("minecraft:")

                        // æŸ¥æ‰¾æ˜ å°„
                        val javaId = blockIdMapping[cleanBedrockId]

                        when {
                            javaId == null -> {
                                // æ˜ å°„ä¸ºnullï¼Œè¡¨ç¤ºJavaç‰ˆä¸­ä¸å­˜åœ¨ï¼Œéœ€è¦è¿‡æ»¤
                                totalFilteredBlocks++
                                BedrockLoader.logger.debug("[VanillaBlockTags] Filtered block (not in Java): $bedrockId")
                            }
                            javaId != cleanBedrockId -> {
                                // IDä¸åŒï¼Œéœ€è¦æ˜ å°„
                                javaBlockIds.add("minecraft:$javaId")
                                totalMappedBlocks++
                                BedrockLoader.logger.debug("[VanillaBlockTags] Mapped block: $bedrockId -> minecraft:$javaId")
                            }
                            else -> {
                                // IDç›¸åŒï¼Œç›´æŽ¥ä½¿ç”¨
                                javaBlockIds.add(bedrockId)
                            }
                        }
                    }

                    if (javaBlockIds.isEmpty()) {
                        BedrockLoader.logger.debug("[VanillaBlockTags] Tag '$tagName' has no valid Java blocks after mapping, skipping")
                        continue
                    }

                    // è§£æžå‘½åç©ºé—´å’Œè·¯å¾„
                    val (namespace, path) = if (tagName.contains(':')) {
                        val parts = tagName.split(':', limit = 2)
                        parts[0].lowercase() to parts[1]
                    } else {
                        "minecraft" to tagName
                    }

                    // ç”ŸæˆTagæ–‡ä»¶ï¼ˆå¤ç”¨çŽ°æœ‰çš„createTagFileæ–¹æ³•ï¼‰
                    createTagFile(namespace, path, javaBlockIds)
                    successCount++

                } catch (e: Exception) {
                    BedrockLoader.logger.error("[VanillaBlockTags] Failed to process tag: ${row.tag}", e)
                }
            }

            BedrockLoader.logger.info("[VanillaBlockTags] Successfully loaded $successCount / ${data.rows.size} vanilla block tags")
            BedrockLoader.logger.info("[VanillaBlockTags] Mapped $totalMappedBlocks blocks, filtered $totalFilteredBlocks blocks (not in Java)")

        } catch (e: Exception) {
            BedrockLoader.logger.error("[VanillaBlockTags] Failed to load vanilla_block_tags.json", e)
        }
    }

    /**
     * åŠ è½½åŸºå²©ç‰ˆåˆ°Javaç‰ˆçš„æ–¹å—IDæ˜ å°„è¡¨
     *
     * @return æ˜ å°„è¡¨ï¼Œkeyä¸ºåŸºå²©ç‰ˆIDï¼ˆä¸å¸¦å‘½åç©ºé—´ï¼‰ï¼Œvalueä¸ºJavaç‰ˆIDï¼ˆä¸å¸¦å‘½åç©ºé—´ï¼‰æˆ–nullï¼ˆè¡¨ç¤ºä¸å­˜åœ¨ï¼‰
     */
    private fun loadBedrockToJavaBlockIdMapping(): Map<String, String?> {
        try {
            val inputStream = BedrockLoader::class.java.getResourceAsStream("/bedrock_to_java_block_ids.json")
            if (inputStream == null) {
                BedrockLoader.logger.warn("[VanillaBlockTags] bedrock_to_java_block_ids.json not found, using identity mapping")
                return emptyMap()
            }

            return inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                GsonUtil.GSON.fromJson(reader, object : com.google.gson.reflect.TypeToken<Map<String, String?>>() {}.type)
            }
        } catch (e: Exception) {
            BedrockLoader.logger.error("[VanillaBlockTags] Failed to load bedrock_to_java_block_ids.json", e)
            return emptyMap()
        }
    }

}

