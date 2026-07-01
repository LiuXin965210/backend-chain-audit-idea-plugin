package com.liuxin.backendchain.export

import com.liuxin.backendchain.model.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
            val summary = result.callGraph.methods.values.take(8).joinToString(" -> ") { methodLabel(it) }
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
                        resource.operationDisplayName,
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
            appendLine("  ${id(method.key)}[\"${escape(methodLabel(method))}\"]")
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
                        "| ${row.index} | `${md(row.input)}` | ${mysqlResourceNames(result)} | ${resourceNames(result, ResourceType.REDIS)} | ${operationResourceLines(result, ResourceType.KAFKA, markdown = true)} | ${operationResourceLines(result, ResourceType.RABBITMQ, markdown = true)} | ${operationResourceLines(result, ResourceType.ROCKETMQ, markdown = true)} | ${externalHttpResourceNames(result, markdown = true)} | ${md(filteredWarningMessages(result).joinToString()).ifBlank { "无" }} |"
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

    fun batchExcel(report: BatchAnalysisReport): ByteArray {
        val overviewRows = buildList {
            add(listOf("指标", "值"))
            add(listOf("总输入数", report.rows.size.toString()))
            add(listOf("成功", report.rows.count { it.status == BatchRowStatus.SUCCESS }.toString()))
            add(listOf("跳过", report.rows.count { it.status == BatchRowStatus.SKIPPED }.toString()))
            add(listOf("失败", report.rows.count { it.status == BatchRowStatus.FAILED }.toString()))
            add(listOf("执行状态", if (report.canceled) "用户取消，已导出完成部分" else "扫描完成"))
        }
        val interfaceRows = buildList {
            add(listOf("序号", "输入URL", "状态", "原因", "HTTP方法", "入口", "分析方法", "方法数", "资源数"))
            report.rows.forEach { row ->
                val result = row.result
                add(
                    listOf(
                        row.index.toString(),
                        row.input,
                        row.status.displayName,
                        row.reason.orEmpty().ifBlank { "无" },
                        result?.entry?.httpMethod.orEmpty().ifBlank { "无" },
                        result?.entry?.displayName.orEmpty().ifBlank { "无" },
                        row.analyzedMethod?.displayName.orEmpty().ifBlank { "无" },
                        (result?.callGraph?.methods?.size ?: 0).toString(),
                        (result?.resources?.size ?: 0).toString()
                    )
                )
            }
        }
        val resourceRows = buildList {
            add(listOf("序号", "输入URL", "MySQL", "Redis", "Kafka", "RabbitMQ", "RocketMQ", "外围接口", "警告"))
            report.rows
                .filter { it.status == BatchRowStatus.SUCCESS && it.result != null }
                .forEach { row ->
                    val result = row.result!!
                    add(
                        listOf(
                            row.index.toString(),
                            row.input,
                            mysqlResourceNames(result, markdown = false),
                            resourceNames(result, ResourceType.REDIS, markdown = false),
                            operationResourceLines(result, ResourceType.KAFKA, markdown = false),
                            operationResourceLines(result, ResourceType.RABBITMQ, markdown = false),
                            operationResourceLines(result, ResourceType.ROCKETMQ, markdown = false),
                            externalHttpResourceNames(result, markdown = false),
                            filteredWarningMessages(result).joinToString().ifBlank { "无" }
                        )
                    )
                }
        }
        return xlsx(
            listOf(
                "总览" to overviewRows,
                "接口结果" to interfaceRows,
                "资源汇总" to resourceRows
            )
        )
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
                resource?.operationDisplayName.orEmpty(),
                resource?.confidence?.displayName.orEmpty(),
                resource?.detail.orEmpty()
            )
        )
    }

    private fun mysqlResourceNames(result: AnalysisResult, markdown: Boolean = true): String {
        val mysql = result.resources.filter { it.type == ResourceType.MYSQL }
        if (mysql.isEmpty()) return "无"
        val separator = if (markdown) "<br>" else "\n"
        val writes = mysql.filter { it.operation == Operation.WRITE }.resourceNames(markdown)
        val reads = mysql.filter { it.operation == Operation.READ }.resourceNames(markdown)
        val others = mysql.filter { it.operation != Operation.WRITE && it.operation != Operation.READ }
        return buildList {
            add("写入：$writes")
            add("只读：$reads")
            if (others.isNotEmpty()) {
                add("其他：" + others.joinToString("，") { resourceName(it.name, markdown) + "（${it.operation.displayName}）" })
            }
        }.joinToString(separator)
    }

    private fun resourceNames(result: AnalysisResult, type: ResourceType, markdown: Boolean = true): String =
        result.resources.filter { it.type == type }.resourceNames(markdown)

    private fun operationResourceNames(result: AnalysisResult, type: ResourceType, markdown: Boolean = false): String {
        val resources = result.resources.filter { it.type == type }
        if (resources.isEmpty()) return "无"
        val separator = if (markdown) "<br>" else "\n"
        return resources.groupBy { it.operation }
            .entries
            .joinToString(separator) { (operation, refs) ->
                refs.first().operationDisplayName + "：" + refs.joinToString("，") { resourceName(it.name, markdown) }
            }
    }

    private fun operationResourceLines(result: AnalysisResult, type: ResourceType, markdown: Boolean): String {
        val resources = result.resources.filter { it.type == type }
        if (resources.isEmpty()) return "无"
        val separator = if (markdown) "<br>" else "\n"
        return resources.joinToString(separator) { ref ->
            ref.operation.displayName + "：" + resourceName(ref.name, markdown)
        }
    }

    private fun externalHttpResourceNames(result: AnalysisResult, markdown: Boolean): String {
        val resources = result.resources.filter { it.type == ResourceType.EXTERNAL_HTTP }
        if (resources.isEmpty()) return "无"
        val separator = if (markdown) "<br>" else "\n"
        return resources.joinToString(separator) { ref ->
            ref.operationDisplayName + "：" + resourceName(ref.name, markdown)
        }
    }

    private fun List<ResourceRef>.resourceNames(markdown: Boolean = true): String {
        val separator = if (markdown) "<br>" else "\n"
        return joinToString(separator) { resourceName(it.name, markdown) }.ifBlank { "无" }
    }

    private fun resourceName(name: String, markdown: Boolean): String =
        if (markdown) "`" + md(name) + "`" else name

    private fun filteredWarningMessages(result: AnalysisResult): List<String> =
        result.warnings.map { it.message }.filterNot(CONSTRUCTOR_REFERENCE_WARNING::matches)

    private fun methodLabel(method: MethodRef): String =
        method.projectName?.let { "$it：${method.displayName}" } ?: method.displayName

    private fun md(value: String) = value.replace("|", "\\|").replace("\n", "<br>")

    private fun id(value: String) = "n" + value.hashCode().toUInt().toString(16)
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun csvRow(values: List<String>) = values.joinToString(",") { value ->
        "\"${value.replace("\"", "\"\"")}\""
    }

    private fun xlsx(sheets: List<Pair<String, List<List<String>>>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.textEntry("[Content_Types].xml", contentTypes(sheets.size))
            zip.textEntry("_rels/.rels", rootRels())
            zip.textEntry("xl/workbook.xml", workbook(sheets.map { it.first }))
            zip.textEntry("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            sheets.forEachIndexed { index, (_, rows) ->
                zip.textEntry("xl/worksheets/sheet${index + 1}.xml", worksheet(rows))
            }
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.textEntry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun contentTypes(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        (1..sheetCount).forEach { index ->
            append("""<Override PartName="/xl/worksheets/sheet$index.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private fun rootRels(): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private fun workbook(sheetNames: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        sheetNames.forEachIndexed { index, name ->
            append("""<sheet name="${xml(name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        (1..sheetCount).forEach { index ->
            append("""<Relationship Id="rId$index" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$index.xml"/>""")
        }
        append("</Relationships>")
    }

    private fun worksheet(rows: List<List<String>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { rowIndex, row ->
            append("""<row r="${rowIndex + 1}">""")
            row.forEachIndexed { columnIndex, value ->
                val ref = "${columnName(columnIndex + 1)}${rowIndex + 1}"
                append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${xml(value)}</t></is></c>""")
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun columnName(index: Int): String {
        var value = index
        val name = StringBuilder()
        while (value > 0) {
            value--
            name.append(('A'.code + value % 26).toChar())
            value /= 26
        }
        return name.reverse().toString()
    }

    private fun xml(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private val CONSTRUCTOR_REFERENCE_WARNING = Regex("""无法解析方法引用：.*::new""")
}
