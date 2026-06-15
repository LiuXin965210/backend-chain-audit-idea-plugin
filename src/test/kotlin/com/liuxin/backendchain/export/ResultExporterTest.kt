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
}
