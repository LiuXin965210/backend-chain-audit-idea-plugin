package com.liuxin.backendchain.headless

import java.io.File

data class HeadlessExecutionSummary(
    val totalTasks: Int,
    val successfulTasks: Int,
    val failedTasks: Int,
    val outputs: List<String>,
    val errors: List<String>
) {
    val failed: Boolean get() = failedTasks > 0 || errors.isNotEmpty()
}

object HeadlessSummaryWriter {
    fun write(summary: HeadlessExecutionSummary, file: File) {
        file.parentFile?.mkdirs()
        file.writeText(toJson(summary))
    }

    fun toJson(summary: HeadlessExecutionSummary): String = buildString {
        appendLine("{")
        appendLine("  \"totalTasks\": ${summary.totalTasks},")
        appendLine("  \"successfulTasks\": ${summary.successfulTasks},")
        appendLine("  \"failedTasks\": ${summary.failedTasks},")
        appendLine("  \"outputs\": [")
        appendLine(summary.outputs.joinToString(",\n") { "    \"${escape(it)}\"" })
        appendLine("  ],")
        appendLine("  \"errors\": [")
        appendLine(summary.errors.joinToString(",\n") { "    \"${escape(it)}\"" })
        appendLine("  ]")
        appendLine("}")
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

