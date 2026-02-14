package net.easecation.bedrockloader.util

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lightweight Molang-like expression evaluator used for data-driven numeric fields.
 *
 * This is intentionally partial and focuses on common expressions seen in Bedrock
 * animation files and block/entity conditions.
 */
object MolangExpressionEvaluator {

    fun evaluate(
        expression: String,
        numberVariables: Map<String, Double> = emptyMap(),
        booleanVariables: Map<String, Boolean> = emptyMap()
    ): Double? {
        return try {
            Parser(expression, numberVariables, booleanVariables).parse()
        } catch (_: Exception) {
            null
        }
    }

    private data class Value(
        val number: Double = 0.0,
        val boolean: Boolean = false,
        val isBoolean: Boolean = false
    ) {
        fun asNumber(): Double = if (isBoolean) if (boolean) 1.0 else 0.0 else number
        fun asBoolean(): Boolean = if (isBoolean) boolean else number != 0.0

        companion object {
            fun number(v: Double) = Value(number = v, isBoolean = false)
            fun boolean(v: Boolean) = Value(boolean = v, isBoolean = true)
        }
    }

    private class Parser(
        rawInput: String,
        numberVariables: Map<String, Double>,
        booleanVariables: Map<String, Boolean>
    ) {
        private val input = rawInput.trim()
        private val numberVars = numberVariables.mapKeys { it.key.lowercase() }
        private val booleanVars = booleanVariables.mapKeys { it.key.lowercase() }
        private var index = 0

        fun parse(): Double {
            if (input.isEmpty()) return 0.0
            val value = parseTernary()
            skipWhitespace()
            if (!isAtEnd()) throw IllegalArgumentException("Unexpected token at $index")
            return value.asNumber()
        }

        private fun parseTernary(): Value {
            var condition = parseOr()
            skipWhitespace()
            if (match('?')) {
                val ifTrue = parseTernary()
                expect(':')
                val ifFalse = parseTernary()
                condition = if (condition.asBoolean()) ifTrue else ifFalse
            }
            return condition
        }

        private fun parseOr(): Value {
            var value = parseAnd()
            while (true) {
                skipWhitespace()
                if (match("||")) {
                    val rhs = parseAnd()
                    value = Value.boolean(value.asBoolean() || rhs.asBoolean())
                } else {
                    return value
                }
            }
        }

        private fun parseAnd(): Value {
            var value = parseEquality()
            while (true) {
                skipWhitespace()
                if (match("&&")) {
                    val rhs = parseEquality()
                    value = Value.boolean(value.asBoolean() && rhs.asBoolean())
                } else {
                    return value
                }
            }
        }

        private fun parseEquality(): Value {
            var value = parseComparison()
            while (true) {
                skipWhitespace()
                value = when {
                    match("==") -> {
                        val rhs = parseComparison()
                        Value.boolean(value.asNumber() == rhs.asNumber())
                    }
                    match("!=") -> {
                        val rhs = parseComparison()
                        Value.boolean(value.asNumber() != rhs.asNumber())
                    }
                    else -> return value
                }
            }
        }

        private fun parseComparison(): Value {
            var value = parseAddSub()
            while (true) {
                skipWhitespace()
                value = when {
                    match(">=") -> {
                        val rhs = parseAddSub()
                        Value.boolean(value.asNumber() >= rhs.asNumber())
                    }
                    match("<=") -> {
                        val rhs = parseAddSub()
                        Value.boolean(value.asNumber() <= rhs.asNumber())
                    }
                    match(">") -> {
                        val rhs = parseAddSub()
                        Value.boolean(value.asNumber() > rhs.asNumber())
                    }
                    match("<") -> {
                        val rhs = parseAddSub()
                        Value.boolean(value.asNumber() < rhs.asNumber())
                    }
                    else -> return value
                }
            }
        }

        private fun parseAddSub(): Value {
            var value = parseMulDiv()
            while (true) {
                skipWhitespace()
                value = when {
                    match('+') -> Value.number(value.asNumber() + parseMulDiv().asNumber())
                    match('-') -> Value.number(value.asNumber() - parseMulDiv().asNumber())
                    else -> return value
                }
            }
        }

        private fun parseMulDiv(): Value {
            var value = parseUnary()
            while (true) {
                skipWhitespace()
                value = when {
                    match('*') -> Value.number(value.asNumber() * parseUnary().asNumber())
                    match('/') -> {
                        val rhs = parseUnary().asNumber()
                        Value.number(if (rhs == 0.0) 0.0 else value.asNumber() / rhs)
                    }
                    match('%') -> {
                        val rhs = parseUnary().asNumber()
                        Value.number(if (rhs == 0.0) 0.0 else value.asNumber() % rhs)
                    }
                    else -> return value
                }
            }
        }

        private fun parseUnary(): Value {
            skipWhitespace()
            return when {
                match('+') -> parseUnary()
                match('-') -> Value.number(-parseUnary().asNumber())
                match('!') -> Value.boolean(!parseUnary().asBoolean())
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Value {
            skipWhitespace()

            if (match('(')) {
                val value = parseTernary()
                expect(')')
                return value
            }

            if (peek() == '\'' || peek() == '"') {
                // String literals are treated as false/0 in numeric contexts.
                parseStringLiteral()
                return Value.number(0.0)
            }

            if (peek()?.isDigit() == true || (peek() == '.' && peek(1)?.isDigit() == true)) {
                return Value.number(parseNumberLiteral())
            }

            val identifier = parseIdentifier()
            if (identifier.isEmpty()) {
                throw IllegalArgumentException("Unexpected token at $index")
            }

            val normalized = identifier.lowercase()

            skipWhitespace()
            if (match('(')) {
                val args = mutableListOf<Value>()
                skipWhitespace()
                if (!match(')')) {
                    do {
                        args += parseTernary()
                        skipWhitespace()
                    } while (match(','))
                    expect(')')
                }
                return Value.number(callFunction(normalized, args))
            }

            if (normalized == "true") return Value.boolean(true)
            if (normalized == "false") return Value.boolean(false)
            if (normalized == "pi" || normalized == "math.pi") return Value.number(Math.PI)

            booleanVars[normalized]?.let { return Value.boolean(it) }
            numberVars[normalized]?.let { return Value.number(it) }
            return Value.number(0.0)
        }

        private fun callFunction(name: String, args: List<Value>): Double {
            fun n(i: Int): Double = args.getOrNull(i)?.asNumber() ?: 0.0
            return when (name) {
                "math.sin", "sin" -> sin(n(0))
                "math.cos", "cos" -> cos(n(0))
                "math.sqrt", "sqrt" -> sqrt(max(0.0, n(0)))
                "math.abs", "abs" -> abs(n(0))
                "math.pow", "pow" -> n(0).pow(n(1))
                "math.min", "min" -> min(n(0), n(1))
                "math.max", "max" -> max(n(0), n(1))
                "math.mod", "mod" -> {
                    val divisor = n(1)
                    if (divisor == 0.0) 0.0 else n(0) % divisor
                }
                "math.lerp", "lerp" -> {
                    val t = n(2)
                    n(0) + (n(1) - n(0)) * t
                }
                else -> 0.0
            }
        }

        private fun parseNumberLiteral(): Double {
            val start = index
            while (peek()?.isDigit() == true) advance()
            if (peek() == '.') {
                advance()
                while (peek()?.isDigit() == true) advance()
            }
            if (peek() == 'e' || peek() == 'E') {
                advance()
                if (peek() == '+' || peek() == '-') advance()
                while (peek()?.isDigit() == true) advance()
            }
            return input.substring(start, index).toDoubleOrNull() ?: 0.0
        }

        private fun parseIdentifier(): String {
            val start = index
            while (true) {
                val ch = peek() ?: break
                if (ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == ':') {
                    advance()
                } else {
                    break
                }
            }
            return if (index > start) input.substring(start, index) else ""
        }

        private fun parseStringLiteral() {
            val quote = peek() ?: return
            if (quote != '\'' && quote != '"') return
            advance()
            while (!isAtEnd() && peek() != quote) {
                if (peek() == '\\') advance()
                advance()
            }
            if (!isAtEnd()) advance()
        }

        private fun expect(ch: Char) {
            skipWhitespace()
            if (!match(ch)) throw IllegalArgumentException("Expected '$ch' at $index")
        }

        private fun match(expected: Char): Boolean {
            if (peek() != expected) return false
            advance()
            return true
        }

        private fun match(expected: String): Boolean {
            if (!input.regionMatches(index, expected, 0, expected.length)) return false
            index += expected.length
            return true
        }

        private fun skipWhitespace() {
            while (peek()?.isWhitespace() == true) advance()
        }

        private fun peek(offset: Int = 0): Char? {
            val i = index + offset
            return if (i in input.indices) input[i] else null
        }

        private fun advance() {
            if (index < input.length) index++
        }

        private fun isAtEnd(): Boolean = index >= input.length
    }
}
