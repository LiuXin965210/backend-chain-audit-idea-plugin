package com.liuxin.backendchain.headless

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadlessTaskRunnerTest : BasePlatformTestCase() {
    fun testExecutesHttpMqAndBatchTasks() {
        myFixture.configureByText(
            "HeadlessTargets.java",
            """
                @interface GetMapping {
                    String[] value() default {};
                    String[] path() default {};
                }
                @interface KafkaListener {
                    String[] topics() default {};
                    String topic() default "";
                    String value() default "";
                }
                class OrderController {
                    @GetMapping("/api/order/save")
                    void save() { helper(); }
                    @GetMapping("/api/order/detail")
                    void detail() {}
                    void helper() {}
                }
                class OrderConsumer {
                    @KafkaListener(topics = "order.topic")
                    void consume() { process(); }
                    void process() {}
                }
            """.trimIndent()
        )
        val projectDir = Files.createTempDirectory("backend-chain-audit-headless").toFile()
        val taskFile = File(projectDir, "task.json").apply {
            writeText(
                """
                    {
                      "tasks": [
                        {
                          "type": "http",
                          "input": "/api/order/save",
                          "outputs": {
                            "markdown": "build/backend-chain-audit/order-save.md",
                            "csv": "build/backend-chain-audit/order-save.csv",
                            "mermaid": "build/backend-chain-audit/order-save.mmd"
                          }
                        },
                        {
                          "type": "mq",
                          "input": "order.topic",
                          "outputs": {
                            "markdown": "build/backend-chain-audit/order-topic.md"
                          }
                        },
                        {
                          "type": "batchHttp",
                          "inputs": ["/api/order/save", "/api/order/detail", "/missing"],
                          "outputs": {
                            "markdown": "build/backend-chain-audit/batch.md",
                            "csv": "build/backend-chain-audit/batch.csv"
                          }
                        }
                      ]
                    }
                """.trimIndent()
            )
        }

        val summary = HeadlessTaskRunner(project, projectDir).run(taskFile)

        assertFalse(summary.failed)
        assertEquals(3, summary.totalTasks)
        assertEquals(6, summary.outputs.size)
        val httpMarkdown = File(projectDir, "build/backend-chain-audit/order-save.md").readText()
        val mqMarkdown = File(projectDir, "build/backend-chain-audit/order-topic.md").readText()
        val batchMarkdown = File(projectDir, "build/backend-chain-audit/batch.md").readText()
        val batchCsv = File(projectDir, "build/backend-chain-audit/batch.csv").readText()

        assertContains(httpMarkdown, "GET /api/order/save")
        assertContains(mqMarkdown, "order.topic")
        assertContains(batchMarkdown, "`/missing` | 跳过 | 未定位到接口")
        assertContains(batchCsv, "\"/api/order/save\",\"成功\"")
        assertTrue(File(projectDir, "build/backend-chain-audit/order-save.mmd").isFile)

        val summaryJson = HeadlessSummaryWriter.toJson(summary)
        assertContains(summaryJson, "\"totalTasks\": 3")
        assertContains(summaryJson, "order-save.md")
    }
}

