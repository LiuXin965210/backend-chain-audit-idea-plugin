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

    fun batchCsv(report: BatchAnalysisReport): String = buildString {
        append('\uFEFF')
        appendLine(
            csvRow(
                listOf(
                    "序号", "输入URL", "状态", "跳过/失败原因", "HTTP方法", "入口", "分析方法",
                    "方法数", "资源数", "资源类型", "资源名称", "操作", "置信度", "证据"
                )
            )
        )
        report.rows.forEach { row ->
            val result = row.result
            val resources = result?.resources.orEmpty()
            if (row.status != BatchRowStatus.SUCCESS || result == null) {
                appendLine(batchCsvRow(row, null))
            } else if (resources.isEmpty()) {
                appendLine(batchCsvRow(row, null))
            } else {
                resources.forEach { resource -> appendLine(batchCsvRow(row, resource)) }
            }
        }
    }

    fun batchMarkdown(report: BatchAnalysisReport): String {
        val success = report.rows.count { it.status == BatchRowStatus.SUCCESS }
        val skipped = report.rows.count { it.status == BatchRowStatus.SKIPPED }
        val failed = report.rows.count { it.status == BatchRowStatus.FAILED }
        return buildString {
            appendLine("# Backend Chain Audit 批量统计")
            appendLine()
            appendLine("## 总览")
            appendLine()
            appendLine("- 总输入数：${report.rows.size}")
            appendLine("- 成功：$success")
            appendLine("- 跳过：$skipped")
            appendLine("- 失败：$failed")
            if (report.canceled) appendLine("- 执行状态：用户取消，已导出完成部分")
            appendLine()
            appendLine("## 接口结果")
            appendLine()
            appendLine("| 序号 | 输入URL | 状态 | 原因 | HTTP方法 | 入口 | 分析方法 | 方法数 | 资源数 |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            report.rows.forEach { row ->
                val result = row.result
                appendLine(
                    "| ${row.index} | `${md(row.input)}` | ${row.status.displayName} | ${md(row.reason.orEmpty()).ifBlank { "无" }} | ${md(result?.entry?.httpMethod.orEmpty()).ifBlank { "无" }} | ${md(result?.entry?.displayName.orEmpty()).ifBlank { "无" }} | ${md(row.analyzedMethod?.displayName.orEmpty()).ifBlank { "无" }} | ${result?.callGraph?.methods?.size ?: 0} | ${result?.resources?.size ?: 0} |"
                )
            }
            appendLine()
            appendLine("## 资源汇总")
            appendLine()
            val successfulRows = report.rows.filter { it.status == BatchRowStatus.SUCCESS && it.result != null }
            if (successfulRows.isEmpty()) {
                appendLine("无成功接口。")
            } else {
                appendLine("| 序号 | 输入URL | MySQL | Redis | Kafka | RabbitMQ | RocketMQ | 外围接口 | 警告 |")
                appendLine("|---|---|---|---|---|---|---|---|---|")
                successfulRows.forEach { row ->
                    val result = row.result!!
                    appendLine(
                        "| ${row.index} | `${md(row.input)}` | ${resourceNames(result, ResourceType.MYSQL)} | ${resourceNames(result, ResourceType.REDIS)} | ${resourceNames(result, ResourceType.KAFKA)} | ${resourceNames(result, ResourceType.RABBITMQ)} | ${resourceNames(result, ResourceType.ROCKETMQ)} | ${resourceNames(result, ResourceType.EXTERNAL_HTTP)} | ${md(result.warnings.joinToString { it.message }).ifBlank { "无" }} |"
                    )
                }
            }
            val abnormalRows = report.rows.filter { it.status != BatchRowStatus.SUCCESS }
            if (abnormalRows.isNotEmpty()) {
                appendLine()
                appendLine("## 跳过与失败明细")
                appendLine()
                abnormalRows.forEach { row ->
                    appendLine("- ${row.index}. `${md(row.input)}`：${row.status.displayName}，${md(row.reason.orEmpty())}")
                }
            }
        }
    }

    private fun batchCsvRow(row: BatchAnalysisRow, resource: ResourceRef?): String {
        val result = row.result
        return csvRow(
            listOf(
                row.index.toString(),
                row.input,
                row.status.displayName,
                row.reason.orEmpty(),
                result?.entry?.httpMethod.orEmpty(),
                result?.entry?.displayName.orEmpty(),
                row.analyzedMethod?.displayName.orEmpty(),
                (result?.callGraph?.methods?.size ?: 0).toString(),
                (result?.resources?.size ?: 0).toString(),
                resource?.type?.displayName.orEmpty(),
                resource?.name.orEmpty(),
                resource?.operation?.displayName.orEmpty(),
                resource?.confidence?.displayName.orEmpty(),
                resource?.detail.orEmpty()
            )
        )
    }

    private fun resourceNames(result: AnalysisResult, type: ResourceType): String =
        result.resources.filter { it.type == type }.joinToString("<br>") { "`" + md(it.name) + "`" }.ifBlank { "无" }

    private fun md(value: String) = value.replace("|", "\\|").replace("\n", "<br>")

    private fun id(value: String) = "n" + value.hashCode().toUInt().toString(16)
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun csvRow(values: List<String>) = values.joinToString(",") { value ->
        "\"${value.replace("\"", "\"\"")}\""
    }
}
