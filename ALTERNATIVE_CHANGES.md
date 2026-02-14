# FabricRock Alternative Build (hasta ahora)

Esta version alternativa incluye los cambios implementados durante esta sesion para mejorar compatibilidad de addons Bedrock en la rama 1.21.1.

## Cambios principales

1. Fixes de estabilidad
- Prevencion de crash en animaciones con loop no reconocido (`AnimationPlayer` fallback defensivo).
- Prevencion de crash por atributo faltante `generic.attack_damage` en entidades con IA melee fallback.

2. IA fallback base para entidades data-driven
- IA basica: moverse, mirar alrededor, mirar jugador, nadar/flotar segun navegacion.
- Seleccion de hostilidad por `type_family` y fallback por nombre de entidad.
- Soporte de melee fallback sin reventar atributos.

3. Atributos y movilidad dinamicos
- Reaplicacion runtime de `health`, `movement`, `knockback_resistance`, `attack`.
- Soporte de `minecraft:navigation.walk` y `minecraft:navigation.generic` para comportamiento de pathfinding.

4. Soporte de componentes nuevos en entidades
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

5. Events y component_groups
- Aplicacion de `add/remove` de `component_groups`.
- Soporte de `sequence` y `randomize`.
- Cola de eventos runtime.
- Evaluacion basica de `filters` (`all_of`, `any_of`, `none_of` y pruebas comunes como `has_component`, `is_underwater`, `actor_health`, etc.).

6. Runtime sensors/timers/transformation
- `environment_sensor` dispara eventos segun filtros.
- `timer` dispara `time_down_event` con soporte `looping`.
- `transformation` convierte entidad con `into` y `delay`.

7. Combate a distancia basico
- Goal generico para `ranged_attack` + `shooter`.
- Disparo de proyectil base (flecha) con daño ajustado por `minecraft:attack` cuando existe.

## Archivos tocados
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

## Estado
- Build exitoso en `:1.21.1:build`.
- Jar actualizado copiado en MultiMC para pruebas locales.
