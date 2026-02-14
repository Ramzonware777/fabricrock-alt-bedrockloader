package net.easecation.bedrockloader.loader

import net.easecation.bedrockloader.BedrockLoader
import net.easecation.bedrockloader.loader.context.BedrockPackContext
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.ServerCommandSource
import java.lang.reflect.Method
import java.util.Collections

object BedrockFunctionRuntime {
    private val compactTildeCoordsRegex = Regex("^(~(?:-?\\d+(?:\\.\\d+)?)?){2,3}$")
    private val compactTildePartRegex = Regex("~(?:-?\\d+(?:\\.\\d+)?)?")
    private val compactCaretCoordsRegex = Regex("^(\\^(?:-?\\d+(?:\\.\\d+)?)?){2,3}$")
    private val compactCaretPartRegex = Regex("\\^(?:-?\\d+(?:\\.\\d+)?)?")
    private val selectorRegex = Regex("@([pares])\\[([^\\]]+)]")
    private val emptyBlockStateRegex = Regex("([A-Za-z0-9_./:-]+)\\[\\]")

    private val missingFunctionWarnings = Collections.synchronizedSet(mutableSetOf<String>())
    private val commandFailureWarnings = Collections.synchronizedSet(mutableSetOf<String>())
    private val recursionWarnings = Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var functions: Map<String, List<String>> = emptyMap()

    @Volatile
    private var tickFunctions: List<String> = emptyList()

    @Volatile
    private var loadFunctions: List<String> = emptyList()

    @Volatile
    private var registered = false

    @Volatile
    private var executeWithPrefixMethod: Method? = null

    fun initialize(context: BedrockPackContext) {
        val behavior = context.behavior
        functions = behavior.functions.mapValues { it.value.toList() }
        tickFunctions = behavior.tickFunctions.mapNotNull(::normalizeFunctionName).distinct()
        loadFunctions = behavior.loadFunctions.mapNotNull(::normalizeFunctionName).distinct()

        val totalLines = functions.values.sumOf { it.size }
        BedrockLoader.logger.info(
            "[Functions] Loaded ${functions.size} mcfunction file(s), ${loadFunctions.size} load function(s), ${tickFunctions.size} tick function(s), $totalLines line(s)"
        )

        registerCallbacksIfNeeded()
    }

    private fun registerCallbacksIfNeeded() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            ServerLifecycleEvents.SERVER_STARTED.register { server ->
                runFunctionList(server, loadFunctions, "load")
            }
            ServerTickEvents.START_SERVER_TICK.register { server ->
                runFunctionList(server, tickFunctions, "tick")
            }
            registered = true
        }
    }

    private fun runFunctionList(server: MinecraftServer, names: List<String>, phase: String) {
        if (names.isEmpty()) return
        val source = server.commandSource
        names.forEach { name ->
            executeFunctionByName(
                server = server,
                source = source,
                rawName = name,
                depth = 0,
                stack = mutableSetOf(),
                origin = phase
            )
        }
    }

    private fun executeFunctionByName(
        server: MinecraftServer,
        source: ServerCommandSource,
        rawName: String,
        depth: Int,
        stack: MutableSet<String>,
        origin: String
    ) {
        if (depth > 32) {
            val key = "$origin:$rawName"
            if (recursionWarnings.add(key)) {
                BedrockLoader.logger.warn("[Functions] Max recursion depth reached while executing '$rawName' from $origin")
            }
            return
        }

        val functionName = normalizeFunctionName(rawName) ?: return
        val lines = functions[functionName]
        if (lines == null) {
            if (missingFunctionWarnings.add(functionName)) {
                BedrockLoader.logger.warn("[Functions] Referenced function not found: $functionName")
            }
            return
        }

        if (!stack.add(functionName)) {
            val key = "$origin:$functionName"
            if (recursionWarnings.add(key)) {
                BedrockLoader.logger.warn("[Functions] Recursive function call detected for '$functionName' (origin=$origin)")
            }
            return
        }

        try {
            lines.forEach { line ->
                executeFunctionLine(server, source, line, depth, stack, functionName)
            }
        } finally {
            stack.remove(functionName)
        }
    }

    private fun executeFunctionLine(
        server: MinecraftServer,
        source: ServerCommandSource,
        line: String,
        depth: Int,
        stack: MutableSet<String>,
        ownerFunction: String
    ) {
        val translated = translateBedrockCommand(line) ?: return

        if (translated.startsWith("function ")) {
            val referencedName = normalizeFunctionName(translated.substringAfter("function ").substringBefore(" "))
            if (referencedName != null && functions.containsKey(referencedName)) {
                executeFunctionByName(
                    server = server,
                    source = source,
                    rawName = referencedName,
                    depth = depth + 1,
                    stack = stack,
                    origin = ownerFunction
                )
                return
            }
        }

        runCatching {
            executeCommand(server, source, translated)
        }.onFailure { throwable ->
            val warningKey = "$ownerFunction::$translated"
            if (commandFailureWarnings.add(warningKey)) {
                BedrockLoader.logger.warn(
                    "[Functions] Command failed in $ownerFunction: '$translated' (${throwable.message})"
                )
            }
        }
    }

    private fun executeCommand(server: MinecraftServer, source: ServerCommandSource, command: String) {
        val manager = server.commandManager
        var method = executeWithPrefixMethod
        if (method == null) {
            method = manager.javaClass.methods.firstOrNull {
                it.name == "executeWithPrefix" && it.parameterCount == 2
            }
            executeWithPrefixMethod = method
        }

        if (method != null) {
            method.invoke(manager, source, command)
            return
        }

        manager.dispatcher.execute(command, source)
    }

    private fun translateBedrockCommand(rawLine: String): String? {
        var command = rawLine.replace("\uFEFF", "").trim()
        if (command.isBlank() || command.startsWith("#")) return null

        command = command.replace(Regex("\\s+"), " ")
        command = expandCompactCoordinateTokens(command)
        command = translateLegacyExecute(command)
        command = translateReplaceItem(command)
        command = normalizeLegacyBlockData(command)
        command = stripEmptyBlockStates(command)
        command = normalizeSelectorArguments(command)
        command = command.replace(Regex("\\s+"), " ").trim()

        return command.takeIf { it.isNotBlank() }
    }

    private fun normalizeFunctionName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var value = raw.trim().replace('\\', '/')
        if (value.contains(':')) value = value.substringAfter(':')
        value = value.removePrefix("/")
        value = value.removePrefix("functions/")
        value = value.removeSuffix(".mcfunction")
        value = value.trim('/')
        if (value.isBlank()) return null
        return value.lowercase()
    }

    private fun expandCompactCoordinateTokens(command: String): String {
        val expanded = mutableListOf<String>()
        command.split(" ").filter { it.isNotBlank() }.forEach { token ->
            expanded.addAll(expandCompactCoordinateToken(token))
        }
        return expanded.joinToString(" ")
    }

    private fun expandCompactCoordinateToken(token: String): List<String> {
        if (compactTildeCoordsRegex.matches(token)) {
            val parts = compactTildePartRegex.findAll(token).map { it.value }.toList()
            if (parts.isNotEmpty()) return parts
        }
        if (compactCaretCoordsRegex.matches(token)) {
            val parts = compactCaretPartRegex.findAll(token).map { it.value }.toList()
            if (parts.isNotEmpty()) return parts
        }
        return listOf(token)
    }

    private fun translateLegacyExecute(command: String, depth: Int = 0): String {
        if (depth > 8) return command
        if (!command.startsWith("execute ")) return command

        val tokens = command.split(" ").filter { it.isNotBlank() }
        if (tokens.size < 5) return command
        if (tokens[0] != "execute") return command

        val modernSubcommands = setOf(
            "as", "at", "in", "positioned", "rotated", "facing",
            "align", "anchored", "if", "unless", "store", "run"
        )
        if (tokens[1] in modernSubcommands) return command

        val selector = tokens[1]
        val x = tokens[2]
        val y = tokens[3]
        val z = tokens[4]
        val rest = tokens.drop(5)
        if (rest.isEmpty()) return command

        val prefix = "execute as $selector at @s positioned $x $y $z"
        if (rest[0] == "detect" && rest.size >= 6) {
            val detectX = rest[1]
            val detectY = rest[2]
            val detectZ = rest[3]
            val block = rest[4]
            val hasDataToken = rest.getOrNull(5)?.toIntOrNull() != null
            val commandStartIndex = if (hasDataToken) 6 else 5
            if (rest.size <= commandStartIndex) return command
            val nextCommand = rest.drop(commandStartIndex).joinToString(" ")
            val translatedNext = translateLegacyExecute(nextCommand, depth + 1)
            return "$prefix if block $detectX $detectY $detectZ $block run $translatedNext"
        }

        val translatedNext = translateLegacyExecute(rest.joinToString(" "), depth + 1)
        return "$prefix run $translatedNext"
    }

    private fun translateReplaceItem(command: String): String {
        val tokens = command.split(" ").filter { it.isNotBlank() }
        if (tokens.size < 6) return command
        if (tokens[0] != "replaceitem" || tokens[1] != "entity") return command

        val target = tokens[2]
        val slot = tokens[3].removePrefix("slot.")
        val itemId = tokens[5]
        val count = tokens.getOrNull(6)?.toIntOrNull()

        return buildString {
            append("item replace entity ")
            append(target)
            append(' ')
            append(slot)
            append(" with ")
            append(itemId)
            if (count != null && count > 0) {
                append(' ')
                append(count)
            }
        }
    }

    private fun normalizeLegacyBlockData(command: String): String {
        val tokens = command.split(" ").filter { it.isNotBlank() }.toMutableList()
        if (tokens.isEmpty()) return command

        if (tokens[0] == "fill" && tokens.size >= 9) {
            if (tokens[8].toIntOrNull() != null) {
                tokens.removeAt(8)
            }
            return tokens.joinToString(" ")
        }

        if (tokens[0] == "setblock" && tokens.size >= 6) {
            if (tokens[5].toIntOrNull() != null) {
                tokens.removeAt(5)
            }
            return tokens.joinToString(" ")
        }

        return command
    }

    private fun stripEmptyBlockStates(command: String): String {
        return command.replace(emptyBlockStateRegex, "$1")
    }

    private fun normalizeSelectorArguments(command: String): String {
        return selectorRegex.replace(command) { match ->
            val selectorType = match.groupValues[1]
            val body = match.groupValues[2]
            if (body.contains('{') || body.contains('}')) {
                return@replace match.value
            }

            val converted = mutableListOf<String>()
            var distanceMin: String? = null
            var distanceMax: String? = null
            var limit: String? = null
            var sort: String? = null

            body.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { part ->
                    val split = part.split("=", limit = 2)
                    if (split.size != 2) {
                        converted += part
                        return@forEach
                    }
                    val key = split[0].trim().lowercase()
                    val value = split[1].trim()
                    when (key) {
                        "r" -> distanceMax = value
                        "rm" -> distanceMin = value
                        "c" -> {
                            val count = value.toIntOrNull()
                            if (count == null) {
                                converted += "limit=$value"
                            } else {
                                if (count < 0) {
                                    sort = "furthest"
                                    limit = (-count).toString()
                                } else {
                                    limit = count.toString()
                                }
                            }
                        }
                        else -> converted += "$key=$value"
                    }
                }

            if (distanceMin != null || distanceMax != null) {
                converted += "distance=${distanceMin ?: ""}..${distanceMax ?: ""}"
            }
            if (sort != null && converted.none { it.startsWith("sort=") }) {
                converted += "sort=$sort"
            }
            if (limit != null && converted.none { it.startsWith("limit=") }) {
                converted += "limit=$limit"
            }

            if (converted.isEmpty()) {
                "@$selectorType"
            } else {
                "@$selectorType[${converted.joinToString(",")}]"
            }
        }
    }
}
