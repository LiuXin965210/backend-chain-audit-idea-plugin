package com.liuxin.backendchain.silent

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilentTaskRunnerTest : BasePlatformTestCase() {
    fun testExecutesSilentHttpMqAndBatchTasks() {
        myFixture.configureByText(
            "SilentTargets.java",
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
        val projectDir = Files.createTempDirectory("backend-chain-audit-silent").toFile()
        val taskDir = File(projectDir, ".backend-chain-audit").apply { mkdirs() }
        val taskFile = File(taskDir, "silent-task.json").apply {
            writeText(
                """
                    {
                      "tasks": [
                        {
                          "type": "http",
                          "input": "/api/order/save",
                          "outputs": {
                            "markdown": ".backend-chain-audit/results/order-save.md",
                            "csv": ".backend-chain-audit/results/order-save.csv",
                            "mermaid": ".backend-chain-audit/results/order-save.mmd"
                          }
                        },
                        {
                          "type": "mq",
                          "input": "order.topic",
                          "outputs": {
                            "markdown": ".backend-chain-audit/results/order-topic.md"
                          }
                        },
                        {
                          "type": "batchHttp",
                          "inputs": ["/api/order/save", "/api/order/detail", "/missing"],
                          "outputs": {
                            "markdown": ".backend-chain-audit/results/batch.md",
                            "csv": ".backend-chain-audit/results/batch.csv"
                          }
                        }
                      ]
                    }
                """.trimIndent()
            )
        }

        val summary = SilentTaskRunner(project).execute(taskFile, projectDir, EmptyProgressIndicator())

        assertFalse(summary.failed)
        assertEquals(3, summary.taskCount)
        assertFalse(taskFile.exists())
        assertEquals(1, taskDir.listFiles { file -> file.name.endsWith(".done.json") }.orEmpty().size)

        val httpMarkdown = File(projectDir, ".backend-chain-audit/results/order-save.md").readText()
        val mqMarkdown = File(projectDir, ".backend-chain-audit/results/order-topic.md").readText()
        val batchMarkdown = File(projectDir, ".backend-chain-audit/results/batch.md").readText()
        val batchCsv = File(projectDir, ".backend-chain-audit/results/batch.csv").readText()

        assertContains(httpMarkdown, "GET /api/order/save")
        assertContains(mqMarkdown, "order.topic")
        assertContains(batchMarkdown, "`/missing` | 跳过 | 未定位到接口")
        assertContains(batchCsv, "\"/api/order/save\",\"成功\"")
        assertTrue(File(projectDir, ".backend-chain-audit/results/order-save.mmd").isFile)
    }
}

