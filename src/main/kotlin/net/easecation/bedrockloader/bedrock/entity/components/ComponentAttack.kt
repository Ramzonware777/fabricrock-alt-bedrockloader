package net.easecation.bedrockloader.bedrock.entity.components

data class ComponentAttack(
        val damage: Any?
) : IEntityComponent {
    fun resolveDamageValueOrNull(): Double? {
        val raw = damage ?: return null
        return when (raw) {
            is Number -> raw.toDouble()
            is List<*> -> {
                val values = raw.mapNotNull { (it as? Number)?.toDouble() }
                if (values.isEmpty()) null else values.average()
            }
            is Map<*, *> -> {
                val min = (raw["range_min"] as? Number)?.toDouble()
                val max = (raw["range_max"] as? Number)?.toDouble()
                when {
                    min != null && max != null -> (min + max) / 2.0
                    min != null -> min
                    max != null -> max
                    else -> null
                }
            }
            else -> null
        }
    }
}

