package net.easecation.bedrockloader.bedrock.entity.components

import com.google.gson.annotations.SerializedName

data class EntityComponents(
        @SerializedName("minecraft:physics") val minecraftPhysics: ComponentPhysics?,
        @SerializedName("minecraft:scale") val minecraftScale: ComponentScale?,
        @SerializedName("minecraft:type_family") val minecraftTypeFamily: ComponentTypeFamily?,
        @SerializedName("minecraft:movement") val minecraftMovement: ComponentMovement?,
        @SerializedName("minecraft:movement.generic") val minecraftMovementGeneric: Any?,
        @SerializedName("minecraft:underwater_movement") val minecraftUnderwaterMovement: ComponentMovement?,
        @SerializedName("minecraft:movement.basic") val minecraftMovementBasic: ComponentMovementBasic?,
        @SerializedName("minecraft:can_fly") val minecraftCanFly: Any?,
        @SerializedName("minecraft:knockback_resistance") val minecraftKnockbackResistance: ComponentKnockbackResistance?,
        @SerializedName("minecraft:navigation.walk") val minecraftNavigationWalk: ComponentNavigationWalk?,
        @SerializedName("minecraft:navigation.generic") val minecraftNavigationGeneric: ComponentNavigationWalk?,
        @SerializedName("minecraft:attack") val minecraftAttack: ComponentAttack?,
        @SerializedName("minecraft:shooter") val minecraftShooter: ComponentShooter?,
        @SerializedName("minecraft:behavior.ranged_attack") val minecraftBehaviorRangedAttack: ComponentBehaviorRangedAttack?,
        @SerializedName("minecraft:burns_in_daylight") val minecraftBurnsInDaylight: Any?,
        @SerializedName("minecraft:equipment") val minecraftEquipment: ComponentEquipment?,
        @SerializedName("minecraft:equip_item") val minecraftEquipItem: ComponentEquipItem?,
        @SerializedName("minecraft:equippable") val minecraftEquippable: ComponentEquippable?,
        @SerializedName("minecraft:loot") val minecraftLoot: ComponentLoot?,
        @SerializedName("minecraft:boss") val minecraftBoss: ComponentBoss?,
        @SerializedName("minecraft:teleport") val minecraftTeleport: ComponentTeleport?,
        @SerializedName("minecraft:collision_box") val minecraftCollisionBox: ComponentCollisionBox?,
        @SerializedName("minecraft:addrider") val minecraftAddrider: ComponentAddrider?,
        @SerializedName("minecraft:can_climb") val minecraftCanClimb: Any?,
        @SerializedName("minecraft:can_join_raid") val minecraftCanJoinRaid: Any?,
        @SerializedName("minecraft:shareables") val minecraftShareables: Any?,
        @SerializedName("minecraft:environment_sensor") val minecraftEnvironmentSensor: Any?,
        @SerializedName("minecraft:timer") val minecraftTimer: Any?,
        @SerializedName("minecraft:transformation") val minecraftTransformation: Any?,
        // Bedrock commonly uses empty objects for marker components (e.g. {}), not only booleans.
        @SerializedName("minecraft:is_baby") val minecraftIsBaby: Any?,
        @SerializedName("minecraft:is_ignited") val minecraftIsIgnited: Any?,
        @SerializedName("minecraft:is_saddled") val minecraftIsSaddled: Any?,
        @SerializedName("minecraft:is_sheared") val minecraftIsSheared: Any?,
        @SerializedName("minecraft:is_tamed") val minecraftIsTamed: Any?,
        @SerializedName("minecraft:is_illager_captain") val minecraftIsIllagerCaptain: Any?,
        // Some packs use object/array forms here instead of an int.
        @SerializedName("minecraft:variant") val minecraftVariant: Any?,
        // Bedrock packs may define this as an int or an object with a value field.
        @SerializedName("minecraft:mark_variant") val minecraftMarkVariant: Any?,
        // Bedrock packs may define this as a number or as an object with a value field.
        @SerializedName("minecraft:skin_id") val minecraftSkinId: Any?,
        @SerializedName("minecraft:health") val minecraftHealth: ComponentHealth?,
        @SerializedName("minecraft:rideable") val minecraftRideable: ComponentRideable?,
        @SerializedName("minecraft:is_immobile") val minecraftIsImmobile: ComponentIsImmobile?,
        @SerializedName("minecraft:pushable") val minecraftPushable: ComponentPushable?,
)
