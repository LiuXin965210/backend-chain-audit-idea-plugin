package com.liuxin.backendchain.silent

object SilentTaskParser {
    fun parse(text: String): SilentTaskFile {
        val root = JsonParser(text).parse()
        val rootObject = root as? Map<*, *> ?: error("任务文件必须是 JSON object")
        val tasks = rootObject["tasks"] as? List<*> ?: error("缺少 tasks 数组")
        return SilentTaskFile(tasks.mapIndexed { index, value -> parseTask(index + 1, value) })
    }

    private fun parseTask(index: Int, value: Any?): SilentTask {
        val task = value as? Map<*, *> ?: error("第 $index 个任务必须是 object")
        val type = task.string("type", index)
        val outputs = parseOutputs(index, task["outputs"])
        if (outputs.isEmpty()) error("第 $index 个任务必须至少配置一个 outputs")
        return when (type) {
            "http" -> SilentTask.Http(task.nonBlankString("input", index), outputs)
            "mq" -> SilentTask.Mq(task.nonBlankString("input", index), outputs)
            "batchHttp" -> {
                val inputs = task["inputs"] as? List<*> ?: error("第 $index 个 batchHttp 任务缺少 inputs 数组")
                val normalized = inputs.mapIndexed { inputIndex, input ->
                    (input as? String)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: error("第 $index 个任务的 inputs[${inputIndex + 1}] 必须是非空字符串")
                }
                if (normalized.isEmpty()) error("第 $index 个 batchHttp 任务 inputs 不能为空")
                SilentTask.BatchHttp(normalized, outputs)
            }
            else -> error("第 $index 个任务 type 不支持：$type")
        }
    }

    private fun parseOutputs(index: Int, value: Any?): SilentTaskOutputs {
        val outputs = value as? Map<*, *> ?: error("第 $index 个任务缺少 outputs object")
        return SilentTaskOutputs(
            markdown = outputs.optionalString("markdown", index),
            csv = outputs.optionalString("csv", index),
            mermaid = outputs.optionalString("mermaid", index)
        )
    }

    private fun Map<*, *>.string(name: String, index: Int): String =
        this[name] as? String ?: error("第 $index 个任务缺少 $name 字符串")

    private fun Map<*, *>.nonBlankString(name: String, index: Int): String =
        string(name, index).trim().takeIf { it.isNotEmpty() } ?: error("第 $index 个任务 $name 不能为空")

    private fun Map<*, *>.optionalString(name: String, index: Int): String? {
        val value = this[name] ?: return null
        return (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("第 $index 个任务 outputs.$name 必须是非空字符串")
    }
}

private class JsonParser(private val text: String) {
    private var offset = 0

    fun parse(): Any? {
        val value = parseValue()
        skipWhitespace()
        if (offset != text.length) error("JSON 末尾存在多余内容")
        return value
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        if (offset >= text.length) error("JSON 内容不完整")
        return when (text[offset]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            else -> error("无法解析 JSON 值：${text[offset]}")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val values = linkedMapOf<String, Any?>()
        skipWhitespace()
        if (consume('}')) return values
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            values[key] = parseValue()
            skipWhitespace()
            if (consume('}')) return values
            expect(',')
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val values = mutableListOf<Any?>()
        skipWhitespace()
        if (consume(']')) return values
        while (true) {
            values += parseValue()
            skipWhitespace()
            if (consume(']')) return values
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (offset < text.length) {
            val char = text[offset++]
            when (char) {
                '"' -> return result.toString()
                '\\' -> result.append(parseEscape())
                else -> result.append(char)
            }
        }
        error("字符串未闭合")
    }

    private fun parseEscape(): Char {
        if (offset >= text.length) error("转义字符不完整")
        return when (val char = text[offset++]) {
            '"', '\\', '/' -> char
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicode()
            else -> error("不支持的转义字符：\\$char")
        }
    }

    private fun parseUnicode(): Char {
        if (offset + 4 > text.length) error("Unicode 转义不完整")
        val hex = text.substring(offset, offset + 4)
        offset += 4
        return hex.toIntOrNull(16)?.toChar() ?: error("非法 Unicode 转义：$hex")
    }

    private fun parseLiteral(literal: String, value: Any?): Any? {
        if (!text.startsWith(literal, offset)) error("非法 JSON 字面量")
        offset += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (offset < text.length && text[offset].isWhitespace()) offset++
    }

    private fun consume(expected: Char): Boolean {
        if (offset < text.length && text[offset] == expected) {
            offset++
            return true
        }
        return false
    }

    private fun expect(expected: Char) {
        if (!consume(expected)) error("期望 '$expected'")
    }
}

