package com.liuxin.backendchain.action

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyzeBatchHttpActionTest {
    @Test
    fun testValidatesEmptyBatchInput() {
        val validation = validateBatchHttpInputs(" \n\t")

        assertTrue(validation.inputs.isEmpty())
        assertEquals(listOf("请输入至少一个 HTTP 接口路径。"), validation.errors)
    }

    @Test
    fun testReportsInvalidLinesWithLineNumbers() {
        val validation = validateBatchHttpInputs(
            """
                /api/order/save
                api/order/detail
                /api/order/search?keyword=x
                /api/order one
            """.trimIndent()
        )

        assertEquals(listOf("/api/order/save"), validation.inputs)
        assertEquals(3, validation.errors.size)
        assertContains(validation.errorMessage(), "第 2 行 `api/order/detail`")
        assertContains(validation.errorMessage(), "第 3 行 `/api/order/search?keyword=x`")
        assertContains(validation.errorMessage(), "第 4 行 `/api/order one`")
    }

    @Test
    fun testDeduplicatesBatchInputBeforeAnalysis() {
        val validation = validateBatchHttpInputs(
            """
                /api/order/save
                /api/order/detail
                /api/order/save
            """.trimIndent()
        )

        assertEquals(listOf("/api/order/save", "/api/order/detail"), validation.inputs)
        assertEquals(listOf(3 to "/api/order/save"), validation.duplicateLines)
        assertTrue(validation.errors.isEmpty())
        assertContains(validation.deduplicateMessage(), "已自动去重 1 行重复接口")
    }

    @Test
    fun testLimitsUniqueBatchInputs() {
        val raw = (1..101).joinToString("\n") { "/api/order/$it" }

        val validation = validateBatchHttpInputs(raw)

        assertEquals(101, validation.inputs.size)
        assertEquals(1, validation.errors.size)
        assertContains(validation.errors.single(), "最多支持 100 个去重后的接口")
    }
}
