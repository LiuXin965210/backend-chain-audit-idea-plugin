package com.liuxin.backendchain.export

import com.liuxin.backendchain.model.*

object ResultExporter {
    fun markdown(result: AnalysisResult): String {
        val grouped = result.resources.groupBy { it.type }
        val mysql = grouped[ResourceType.MYSQL].orEmpty()
        val writes = mysql.filter { it.operation == Operation.WRITE }.joinToString("<br>") { "`${it.name}`" }.ifBlank { "无" }
        val reads = mysql.filter { it.operation == Operation.READ }.joinToString("<br>") { "`${it.name}`" }.ifBlank { "无" }
        fun cell(type: ResourceType) = grouped[type].orEmpty().joinToString("<br>") { "`${it.name}`" }.ifBlank { "无" }
        return buildString {
            appendLine("| 入口类型 | 入口 | 状态 | 调用链摘要 | MySQL表 | Redis key | ES索引 | MongoDB表 | Kafka topic | RabbitMQ queue | RocketMQ topic | 外围接口 |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
            val status = if (result.resources.any { it.confidence == Confidence.UNRESOLVED }) "待确认" else "已完成"
            val summary = result.callGraph.methods.values.take(8).joinToString(" -> ") { it.displayName }
            appendLine("| ${result.entry.type} | `${result.entry.displayName}` | $status | $summary | 写入：$writes<br>只读：$reads | ${cell(ResourceType.REDIS)} | 无 | 无 | ${cell(ResourceType.KAFKA)} | ${cell(ResourceType.RABBITMQ)} | ${cell(ResourceType.ROCKETMQ)} | ${cell(ResourceType.EXTERNAL_HTTP)} |")
        }
    }

    fun csv(result: AnalysisResult): String = buildString {
        append('\uFEFF')
        appendLine(csvRow(listOf("入口类型", "入口", "资源类型", "资源名称", "操作", "置信度", "调用路径与证据")))
        result.resources.forEach { resource ->
            appendLine(
                csvRow(
                    listOf(
                        result.entry.type.name,
                        result.entry.displayName,
                        resource.type.displayName,
                        resource.name,
                        resource.operation.displayName,
                        resource.confidence.displayName,
                        resource.detail
                    )
                )
            )
        }
    }

    fun mermaid(result: AnalysisResult): String = buildString {
        appendLine("flowchart TD")
        result.callGraph.methods.values.forEach { method ->
            appendLine("  ${id(method.key)}[\"${escape(method.displayName)}\"]")
        }
        result.callGraph.edges.forEach { edge ->
            appendLine("  ${id(edge.caller)} -->|${edge.confidence.displayName}| ${id(edge.callee)}")
        }
        result.resources.forEachIndexed { index, resource ->
            val resourceId = "resource_$index"
            appendLine("  $resourceId[(\"${escape(resource.type.displayName + ": " + resource.name)}\")]")
            appendLine("  ${id(result.callGraph.root)} -.-> $resourceId")
        }
    }

    private fun id(value: String) = "n" + value.hashCode().toUInt().toString(16)
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun csvRow(values: List<String>) = values.joinToString(",") { value ->
        "\"${value.replace("\"", "\"\"")}\""
    }
}
