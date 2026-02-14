package net.easecation.bedrockloader.bedrock.pack

import com.google.gson.*
import java.lang.reflect.Type

data class SemVersion(
        val major: Int,
        val minor: Int,
        val patch: Int
) {
    override fun toString(): String {
        return "$major.$minor.$patch"
    }

    class Serializer : JsonSerializer<SemVersion>, JsonDeserializer<SemVersion> {
        private fun parseSegment(raw: String?): Int {
            if (raw.isNullOrBlank()) return 0
            val numericPrefix = Regex("[-+]?\\d+").find(raw.trim())?.value
            return numericPrefix?.toIntOrNull() ?: 0
        }

        private fun parseElement(element: JsonElement?): Int {
            if (element == null || element.isJsonNull) return 0
            if (!element.isJsonPrimitive) return 0

            val primitive = element.asJsonPrimitive
            return when {
                primitive.isNumber -> primitive.asInt
                primitive.isString -> parseSegment(primitive.asString)
                else -> 0
            }
        }

        override fun serialize(src: SemVersion, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
            val jsonArr = JsonArray()
            jsonArr.add(src.major)
            jsonArr.add(src.minor)
            jsonArr.add(src.patch)
            return jsonArr
        }

        override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): SemVersion {
            if (json.isJsonPrimitive && json.asJsonPrimitive.isString) {
                val version = json.asString.split(".")
                return SemVersion(
                    parseSegment(version.getOrNull(0)),
                    parseSegment(version.getOrNull(1)),
                    parseSegment(version.getOrNull(2))
                )
            }

            if (json.isJsonArray) {
                val jsonArr = json.asJsonArray
                return SemVersion(
                    parseElement(if (jsonArr.size() > 0) jsonArr[0] else null),
                    parseElement(if (jsonArr.size() > 1) jsonArr[1] else null),
                    parseElement(if (jsonArr.size() > 2) jsonArr[2] else null)
                )
            }

            if (json.isJsonObject) {
                val jsonObj = json.asJsonObject
                return SemVersion(
                    parseElement(jsonObj.get("major")),
                    parseElement(jsonObj.get("minor")),
                    parseElement(jsonObj.get("patch"))
                )
            }

            return SemVersion(0, 0, 0)
        }
    }
}
