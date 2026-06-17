package com.liuxin.backendchain.headless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class HeadlessTaskParserTest {
    @Test
    fun `parses http mq and batch tasks`() {
        val file = HeadlessTaskParser.parse(
            """
                {
                  "tasks": [
                    {
                      "type": "http",
                      "input": "/api/order/save",
                      "outputs": {
                        "markdown": "build/order.md",
                        "csv": "build/order.csv",
                        "mermaid": "build/order.mmd"
                      }
                    },
                    {
                      "type": "mq",
                      "input": "order.topic",
                      "outputs": {
                        "markdown": "build/mq.md"
                      }
                    },
                    {
                      "type": "batchHttp",
                      "inputs": ["/api/order/save", "/api/order/detail"],
                      "outputs": {
                        "csv": "build/batch.csv"
                      }
                    }
                  ]
                }
            """.trimIndent()
        )

        assertEquals(3, file.tasks.size)
        assertEquals("/api/order/save", assertIs<HeadlessTask.Http>(file.tasks[0]).input)
        assertEquals("order.topic", assertIs<HeadlessTask.Mq>(file.tasks[1]).input)
        assertEquals(listOf("/api/order/save", "/api/order/detail"), assertIs<HeadlessTask.BatchHttp>(file.tasks[2]).inputs)
    }

    @Test
    fun `rejects invalid task contract`() {
        assertFailsWith<IllegalStateException> {
            HeadlessTaskParser.parse("""{"tasks":[]}""")
        }
        assertFailsWith<IllegalStateException> {
            HeadlessTaskParser.parse("""{"tasks":[{"type":"http","outputs":{"markdown":"a.md"}}]}""")
        }
        assertFailsWith<IllegalStateException> {
            HeadlessTaskParser.parse("""{"tasks":[{"type":"http","input":"/a","outputs":{}}]}""")
        }
        assertFailsWith<IllegalStateException> {
            HeadlessTaskParser.parse("""{"tasks":[{"type":"method","input":"a","outputs":{"markdown":"a.md"}}]}""")
        }
    }
}

