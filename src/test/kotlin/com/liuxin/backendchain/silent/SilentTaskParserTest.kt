package com.liuxin.backendchain.silent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SilentTaskParserTest {
    @Test
    fun `parses single and batch tasks`() {
        val file = SilentTaskParser.parse(
            """
                {
                  "tasks": [
                    {
                      "type": "http",
                      "input": "/api/order/save",
                      "outputs": {
                        "markdown": ".backend-chain-audit/results/order.md",
                        "csv": ".backend-chain-audit/results/order.csv",
                        "mermaid": ".backend-chain-audit/results/order.mmd"
                      }
                    },
                    {
                      "type": "batchHttp",
                      "inputs": ["/api/order/save", "/api/order/detail"],
                      "outputs": {
                        "markdown": ".backend-chain-audit/results/batch.md"
                      }
                    }
                  ]
                }
            """.trimIndent()
        )

        assertEquals(2, file.tasks.size)
        val http = assertIs<SilentTask.Http>(file.tasks[0])
        assertEquals("/api/order/save", http.input)
        assertEquals(".backend-chain-audit/results/order.csv", http.outputs.csv)
        val batch = assertIs<SilentTask.BatchHttp>(file.tasks[1])
        assertEquals(listOf("/api/order/save", "/api/order/detail"), batch.inputs)
    }

    @Test
    fun `rejects missing input output and unknown type`() {
        assertFailsWith<IllegalStateException> {
            SilentTaskParser.parse("""{"tasks":[{"type":"http","outputs":{"markdown":"a.md"}}]}""")
        }
        assertFailsWith<IllegalStateException> {
            SilentTaskParser.parse("""{"tasks":[{"type":"http","input":"/a","outputs":{}}]}""")
        }
        assertFailsWith<IllegalStateException> {
            SilentTaskParser.parse("""{"tasks":[{"type":"method","input":"a","outputs":{"markdown":"a.md"}}]}""")
        }
    }
}

