package com.liuxin.backendchain.export

import com.liuxin.backendchain.model.*
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResultExporterTest {
    @Test
    fun `exports skill compatible overview and mermaid`() {
        val method = MethodRef("a#save()", "a.OrderService", "save", null)
        val result = AnalysisResult(
            EntryPoint(EntryType.HTTP, "POST /api/order/save", "POST", "/api/order/save"),
            CallGraph(method.key, mapOf(method.key to method), emptyList()),
            listOf(ResourceRef(ResourceType.MYSQL, "purchase_order", Operation.WRITE, Confidence.CONFIRMED, "test", null)),
            emptyList()
        )
        assertContains(ResultExporter.markdown(result), "写入：`purchase_order`")
        assertContains(ResultExporter.mermaid(result), "a.OrderService.save")
    }

    @Test
    fun `exports resource detail csv with bom and escaping`() {
        val method = MethodRef("a#save()", "a.OrderService", "save", null)
        val result = AnalysisResult(
            EntryPoint(EntryType.HTTP, "POST /api/order,save", "POST", "/api/order,save"),
            CallGraph(method.key, mapOf(method.key to method), emptyList()),
            listOf(
                ResourceRef(
                    ResourceType.ROCKETMQ,
                    "order-topic:tag-a",
                    Operation.PRODUCE,
                    Confidence.INFERRED,
                    "调用路径：OrderService.save\n原始证据：\"send\"",
                    null
                )
            ),
            emptyList()
        )

        val csv = ResultExporter.csv(result)
        assertTrue(csv.startsWith("\uFEFF\"入口类型\""))
        assertContains(csv, "\"POST /api/order,save\"")
        assertContains(csv, "\"调用路径：OrderService.save\n原始证据：\"\"send\"\"\"")
    }

    @Test
    fun `exports csv header when resources are empty`() {
        val result = AnalysisResult(
            EntryPoint(EntryType.METHOD, "test"),
            CallGraph("", emptyMap(), emptyList()),
            emptyList(),
            emptyList()
        )

        assertEquals(1, ResultExporter.csv(result).trim().lines().size)
    }

    @Test
    fun `exports batch csv and markdown with skipped reasons in input order`() {
        val method = MethodRef("a#save()", "a.OrderService", "save", null)
        val result = AnalysisResult(
            EntryPoint(EntryType.HTTP, "POST /api/order/save", "POST", "/api/order/save"),
            CallGraph(method.key, mapOf(method.key to method), emptyList()),
            listOf(
                ResourceRef(ResourceType.MYSQL, "purchase_order", Operation.WRITE, Confidence.CONFIRMED, "调用路径：save", null),
                ResourceRef(ResourceType.MYSQL, "purchase_order_item", Operation.READ, Confidence.CONFIRMED, "调用路径：select", null)
            ),
            listOf(AnalysisWarning("待确认资源"))
        )
        val report = BatchAnalysisReport(
            listOf(
                BatchAnalysisRow(1, "/api/order/save", BatchRowStatus.SUCCESS, result = result, analyzedMethod = method),
                BatchAnalysisRow(2, "/missing", BatchRowStatus.SKIPPED, "未定位到接口"),
                BatchAnalysisRow(3, "/dup", BatchRowStatus.SKIPPED, "定位到多个接口")
            )
        )

        val csv = ResultExporter.batchCsv(report)
        val markdown = ResultExporter.batchMarkdown(report)

        assertTrue(csv.startsWith("\uFEFF\"序号\""))
        assertContains(csv, "\"1\",\"/api/order/save\",\"成功\"")
        assertContains(csv, "\"2\",\"/missing\",\"跳过\",\"未定位到接口\"")
        assertContains(csv, "\"3\",\"/dup\",\"跳过\",\"定位到多个接口\"")
        assertTrue(csv.indexOf("\"/missing\"") < csv.indexOf("\"/dup\""))
        assertContains(markdown, "- 总输入数：3")
        assertContains(markdown, "| 2 | `/missing` | 跳过 | 未定位到接口 |")
        assertContains(markdown, "写入：`purchase_order`")
        assertContains(markdown, "只读：`purchase_order_item`")
    }

    @Test
    fun `exports batch excel with markdown equivalent row summaries`() {
        val method = MethodRef("a#save()", "a.OrderService", "save", null)
        val result = AnalysisResult(
            EntryPoint(EntryType.HTTP, "POST /api/order/save", "POST", "/api/order/save"),
            CallGraph(method.key, mapOf(method.key to method), emptyList()),
            listOf(
                ResourceRef(ResourceType.MYSQL, "purchase_order", Operation.WRITE, Confidence.CONFIRMED, "调用路径：save", null),
                ResourceRef(ResourceType.MYSQL, "purchase_order_item", Operation.READ, Confidence.CONFIRMED, "调用路径：select", null),
                ResourceRef(ResourceType.KAFKA, "order-topic", Operation.PRODUCE, Confidence.INFERRED, "调用路径：send", null),
                ResourceRef(ResourceType.KAFKA, "stock-topic", Operation.PRODUCE, Confidence.INFERRED, "调用路径：sendStock", null),
                ResourceRef(ResourceType.RABBITMQ, "order-queue", Operation.PRODUCE, Confidence.INFERRED, "调用路径：rabbit", null),
                ResourceRef(ResourceType.RABBITMQ, "stock-queue", Operation.PRODUCE, Confidence.INFERRED, "调用路径：rabbitStock", null),
                ResourceRef(ResourceType.ROCKETMQ, "order-topic:tag-a", Operation.PRODUCE, Confidence.INFERRED, "调用路径：rocket", null),
                ResourceRef(ResourceType.ROCKETMQ, "stock-topic:tag-b", Operation.PRODUCE, Confidence.INFERRED, "调用路径：rocketStock", null),
                ResourceRef(ResourceType.EXTERNAL_HTTP, "GET /api/order/search-order", Operation.READ, Confidence.INFERRED, "调用路径：search", null),
                ResourceRef(ResourceType.EXTERNAL_HTTP, "POST /api/order/create-order", Operation.CALL, Confidence.INFERRED, "调用路径：create", null)
            ),
            listOf(
                AnalysisWarning("无法解析方法引用：StockHolding::new"),
                AnalysisWarning("无法解析方法引用：OrderService::handle")
            )
        )
        val report = BatchAnalysisReport(
            listOf(
                BatchAnalysisRow(1, "/api/order/save", BatchRowStatus.SUCCESS, result = result, analyzedMethod = method),
                BatchAnalysisRow(2, "/missing", BatchRowStatus.SKIPPED, "未定位到接口")
            )
        )

        val entries = unzip(ResultExporter.batchExcel(report))
        val workbook = entries.getValue("xl/workbook.xml")
        val interfaceSheet = entries.getValue("xl/worksheets/sheet2.xml")
        val resourceSheet = entries.getValue("xl/worksheets/sheet3.xml")

        assertContains(workbook, "接口结果")
        assertContains(interfaceSheet, "输入URL")
        assertContains(interfaceSheet, "/missing")
        assertContains(resourceSheet, "写入：purchase_order")
        assertContains(resourceSheet, "只读：purchase_order_item")
        assertContains(resourceSheet, "生产：order-topic")
        assertContains(resourceSheet, "生产：order-topic\n生产：stock-topic")
        assertContains(resourceSheet, "生产：order-queue\n生产：stock-queue")
        assertContains(resourceSheet, "生产：order-topic:tag-a\n生产：stock-topic:tag-b")
        assertFalse(resourceSheet.contains("生产：order-topic，stock-topic"))
        assertContains(resourceSheet, "查询：GET /api/order/search-order")
        assertContains(resourceSheet, "非查询：POST /api/order/create-order")
        assertContains(resourceSheet, "查询：GET /api/order/search-order\n非查询：POST /api/order/create-order")
        assertFalse(resourceSheet.contains("StockHolding::new"))
        assertContains(resourceSheet, "无法解析方法引用：OrderService::handle")
    }

    @Test
    fun `exports successful empty resource batch row as summary row`() {
        val method = MethodRef("a#empty()", "a.EmptyController", "empty", null)
        val report = BatchAnalysisReport(
            listOf(
                BatchAnalysisRow(
                    1,
                    "/empty",
                    BatchRowStatus.SUCCESS,
                    result = AnalysisResult(
                        EntryPoint(EntryType.HTTP, "GET /empty", "GET", "/empty"),
                        CallGraph(method.key, mapOf(method.key to method), emptyList()),
                        emptyList(),
                        emptyList()
                    ),
                    analyzedMethod = method
                )
            )
        )

        val lines = ResultExporter.batchCsv(report).trim().lines()

        assertEquals(2, lines.size)
        assertContains(lines[1], "\"/empty\",\"成功\"")
        assertContains(lines[1], "\"0\",\"\",\"\",\"\",\"\"")
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return entries
    }
}
