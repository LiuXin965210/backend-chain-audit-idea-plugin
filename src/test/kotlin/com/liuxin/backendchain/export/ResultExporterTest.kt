package com.liuxin.backendchain.export

import com.liuxin.backendchain.model.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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
            listOf(ResourceRef(ResourceType.MYSQL, "purchase_order", Operation.WRITE, Confidence.CONFIRMED, "调用路径：save", null)),
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
        assertContains(markdown, "`purchase_order`")
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
}
