package com.liuxin.backendchain.analysis

import com.liuxin.backendchain.model.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathAndSqlTest {
    @Test
    fun `normalizes mapping paths`() {
        assertEquals("/api/order/save", normalizePath("/api/", "/order", "save"))
        assertEquals("/", normalizePath(null, ""))
    }

    @Test
    fun `extracts read tables from joins`() {
        val resources = SqlResourceParser.parse(
            "select o.id from trade_order o join trade_order_items i on i.order_id=o.id",
            null,
            "test"
        )
        assertEquals(setOf("trade_order", "trade_order_items"), resources.map { it.name }.toSet())
        assertTrue(resources.all { it.operation == Operation.READ })
    }

    @Test
    fun `extracts write table`() {
        val resources = SqlResourceParser.parse("update purchase_order set status=1 where id=2", null, "test")
        assertEquals(setOf("purchase_order"), resources.map { it.name }.toSet())
        assertTrue(resources.all { it.operation == Operation.WRITE })
    }
}
