package com.liuxin.backendchain.export

import com.liuxin.backendchain.model.*
import kotlin.test.Test
import kotlin.test.assertContains

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
}
