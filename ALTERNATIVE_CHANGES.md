# FabricRock Alternative Build (Current Snapshot)

This alternative version includes the changes implemented during this session to improve Bedrock addon compatibility on the 1.21.1 branch.

## Main Changes

1. Stability fixes
- Prevented crashes in animations with unknown loop values (`AnimationPlayer` defensive fallback).
- Prevented crashes caused by missing `generic.attack_damage` on entities using fallback melee AI.

2. Base fallback AI for data-driven entities
- Basic AI: movement, look around, look at player, swim/float based on navigation components.
- Hostility selection using `type_family` plus fallback by entity name.
- Melee fallback support without breaking attribute handling.

3. Dynamic attributes and mobility
- Runtime re-application of `health`, `movement`, `knockback_resistance`, and `attack`.
- Support for `minecraft:navigation.walk` and `minecraft:navigation.generic` for pathfinding behavior.

4. New entity component support
- `minecraft:attack`
- `minecraft:shooter`
- `minecraft:behavior.ranged_attack`
- `minecraft:burns_in_daylight`
- `minecraft:can_fly`
- `minecraft:equipment`
- `minecraft:shareables`
- `minecraft:environment_sensor`
- `minecraft:timer`
- `minecraft:transformation`
- `minecraft:movement.generic`
- `minecraft:underwater_movement`

5. Events and component_groups
- `add/remove` application for `component_groups`.
- `sequence` and `randomize` support.
- Runtime event queue.
- Basic `filters` evaluation (`all_of`, `any_of`, `none_of`, and common tests like `has_component`, `is_underwater`, `actor_health`, etc.).

6. Runtime sensors/timers/transformation
- `environment_sensor` triggers events based on filters.
- `timer` triggers `time_down_event` with `looping` support.
- `transformation` converts entities via `into` and `delay`.

7. Basic ranged combat
- Generic goal for `ranged_attack` + `shooter`.
- Base projectile shooting (arrow) with damage adjusted by `minecraft:attack` when present.

## Files Updated
- `src/main/kotlin/net/easecation/bedrockloader/entity/EntityDataDriven.kt`
- `src/client/kotlin/net/easecation/bedrockloader/animation/AnimationPlayer.kt`
- `src/main/kotlin/net/easecation/bedrockloader/bedrock/entity/components/EntityComponents.kt`
- `src/main/kotlin/net/easecation/bedrockloader/bedrock/entity/components/ComponentPhysics.kt`
- `src/main/kotlin/net/easecation/bedrockloader/bedrock/entity/components/ComponentAttack.kt`
- `src/main/kotlin/net/easecation/bedrockloader/bedrock/entity/components/ComponentBehaviorRangedAttack.kt`
- `src/main/kotlin/net/easecation/bedrockloader/bedrock/entity/components/ComponentShooter.kt`
- `src/main/kotlin/net/easecation/bedrockloader/bedrock/entity/components/ComponentEquipment.kt`
- `src/main/kotlin/net/easecation/bedrockloader/loader/BedrockAddonsRegistry.kt`
- `src/main/kotlin/net/easecation/bedrockloader/loader/BedrockBehaviorPackLoader.kt`

## Status
- Build completed successfully in `:1.21.1:build`.
- Updated JAR copied to MultiMC for local testing.
