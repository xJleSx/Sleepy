package com.sleepy.app.sleepy.dataset

internal sealed interface JsonValue {
    data class Text(val value: String) : JsonValue
    data class Number(val value: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class ObjectValue(val fields: List<Pair<String, JsonValue>>) : JsonValue
}

internal class JsonObject {
    private val fields = mutableListOf<Pair<String, JsonValue>>()

    fun put(key: String, value: String): JsonObject = apply {
        fields += key to JsonValue.Text(value)
    }

    fun put(key: String, value: Int): JsonObject = apply {
        fields += key to JsonValue.Number(value.toString())
    }

    fun put(key: String, value: Long): JsonObject = apply {
        fields += key to JsonValue.Number(value.toString())
    }

    fun put(key: String, value: Double): JsonObject = apply {
        require(value.isFinite()) { "$key должно быть конечным числом" }
        fields += key to JsonValue.Number(value.toString())
    }

    fun put(key: String, value: Boolean): JsonObject = apply {
        fields += key to JsonValue.BooleanValue(value)
    }

    fun put(key: String, value: JsonValue): JsonObject = apply {
        fields += key to value
    }

    fun putStrings(key: String, values: List<String>): JsonObject = apply {
        fields += key to JsonValue.ArrayValue(values.map { JsonValue.Text(it) })
    }

    fun build(): JsonValue.ObjectValue = JsonValue.ObjectValue(fields.toList())
}

internal class JsonArray {
    private val values = mutableListOf<JsonValue>()

    fun add(value: JsonValue): JsonArray = apply {
        values += value
    }

    fun add(value: String): JsonArray = apply {
        values += JsonValue.Text(value)
    }

    fun add(value: JsonObject): JsonArray = apply {
        values += value.build()
    }

    fun build(): JsonValue.ArrayValue = JsonValue.ArrayValue(values.toList())
}

internal operator fun JsonValue.ObjectValue.get(key: String): JsonValue? =
    fields.lastOrNull { it.first == key }?.second

internal fun JsonValue.asText(): String? = (this as? JsonValue.Text)?.value

internal fun JsonValue.asArray(): List<JsonValue>? = (this as? JsonValue.ArrayValue)?.values

internal fun JsonValue.asLong(): Long? = (this as? JsonValue.Number)?.value?.toLongOrNull()

internal object DatasetJson {
    fun write(value: JsonValue): String = buildString {
        appendValue(value, 0)
    }

    fun parse(text: String): JsonValue {
        val parser = JsonParser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) {
            parser.fail("Лишние данные после JSON")
        }
        return value
    }

    private fun StringBuilder.appendValue(value: JsonValue, depth: Int) {
        when (value) {
            is JsonValue.Text -> appendString(value.value)
            is JsonValue.Number -> append(value.value)
            is JsonValue.BooleanValue -> append(if (value.value) "true" else "false")
            JsonValue.NullValue -> append("null")
            is JsonValue.ArrayValue -> appendArray(value, depth)
            is JsonValue.ObjectValue -> appendObject(value, depth)
        }
    }

    private fun StringBuilder.appendArray(value: JsonValue.ArrayValue, depth: Int) {
        if (value.values.isEmpty()) {
            append("[]")
            return
        }

        append("[\n")
        value.values.forEachIndexed { index, item ->
            appendIndent(depth + 1)
            appendValue(item, depth + 1)
            if (index != value.values.lastIndex) append(',')
            append('\n')
        }
        appendIndent(depth)
        append(']')
    }

    private fun StringBuilder.appendObject(value: JsonValue.ObjectValue, depth: Int) {
        if (value.fields.isEmpty()) {
            append("{}")
            return
        }

        append("{\n")
        value.fields.forEachIndexed { index, (key, fieldValue) ->
            appendIndent(depth + 1)
            appendString(key)
            append(": ")
            appendValue(fieldValue, depth + 1)
            if (index != value.fields.lastIndex) append(',')
            append('\n')
        }
        appendIndent(depth)
        append('}')
    }

    private fun StringBuilder.appendIndent(depth: Int) {
        repeat(depth) { append("  ") }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private class JsonParser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (!atEnd() && text[index] in WHITESPACE) index++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) fail("Неожиданный конец JSON")
            return when (text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Text(parseString())
                't' -> parseLiteral("true", JsonValue.BooleanValue(true))
                'f' -> parseLiteral("false", JsonValue.BooleanValue(false))
                'n' -> parseLiteral("null", JsonValue.NullValue)
                '-', in '0'..'9' -> parseNumber()
                else -> fail("Неожиданный символ JSON")
            }
        }

        private fun parseObject(): JsonValue.ObjectValue {
            expect('{')
            val fields = mutableListOf<Pair<String, JsonValue>>()
            val keys = mutableSetOf<String>()
            skipWhitespace()
            if (peek('}')) {
                index++
                return JsonValue.ObjectValue(fields)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                if (!keys.add(key)) fail("Повторяющийся ключ JSON: $key")
                skipWhitespace()
                expect(':')
                fields += key to parseValue()
                skipWhitespace()
                if (peek('}')) {
                    index++
                    return JsonValue.ObjectValue(fields)
                }
                expect(',')
            }
        }

        private fun parseArray(): JsonValue.ArrayValue {
            expect('[')
            val values = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek(']')) {
                index++
                return JsonValue.ArrayValue(values)
            }
            while (true) {
                values += parseValue()
                skipWhitespace()
                if (peek(']')) {
                    index++
                    return JsonValue.ArrayValue(values)
                }
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (!atEnd()) {
                val character = text[index++]
                when (character) {
                    '"' -> return result.toString()
                    '\\' -> {
                        if (atEnd()) fail("Незавершённая escape-последовательность")
                        when (val escaped = text[index++]) {
                            '"' -> result.append('"')
                            '\\' -> result.append('\\')
                            '/' -> result.append('/')
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> result.append(parseUnicode())
                            else -> fail("Неизвестная escape-последовательность: $escaped")
                        }
                    }
                    else -> {
                        if (character.code < 0x20) fail("Управляющий символ в строке JSON")
                        result.append(character)
                    }
                }
            }
            fail("Незавершённая строка JSON")
        }

        private fun parseUnicode(): Char {
            if (index + 4 > text.length) fail("Незавершённый unicode escape")
            val hex = text.substring(index, index + 4)
            index += 4
            return hex.toIntOrNull(16)?.toChar() ?: fail("Некорректный unicode escape")
        }

        private fun parseNumber(): JsonValue.Number {
            val start = index
            if (peek('-')) index++
            if (atEnd()) fail("Незавершённое число JSON")
            if (peek('0')) {
                index++
            } else {
                if (text[index] !in '1'..'9') fail("Некорректное число JSON")
                while (!atEnd() && text[index] in '0'..'9') index++
            }
            if (!atEnd() && text[index] == '.') {
                index++
                if (atEnd() || text[index] !in '0'..'9') fail("Некорректная дробная часть")
                while (!atEnd() && text[index] in '0'..'9') index++
            }
            if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
                if (atEnd() || text[index] !in '0'..'9') fail("Некорректная экспонента")
                while (!atEnd() && text[index] in '0'..'9') index++
            }
            return JsonValue.Number(text.substring(start, index))
        }

        private fun <T : JsonValue> parseLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, index)) fail("Неожиданный литерал JSON")
            index += literal.length
            return value
        }

        private fun expect(expected: Char) {
            skipWhitespace()
            if (atEnd() || text[index] != expected) fail("Ожидался символ $expected")
            index++
        }

        private fun peek(expected: Char): Boolean = !atEnd() && text[index] == expected

        fun fail(message: String): Nothing = throw IllegalArgumentException(message)

        private companion object {
            val WHITESPACE = setOf(' ', '\t', '\n', '\r')
        }
    }
}
