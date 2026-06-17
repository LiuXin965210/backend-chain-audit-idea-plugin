package com.liuxin.backendchain.headless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HeadlessCliParserTest {
    @Test
    fun `parses required and optional arguments`() {
        val options = HeadlessCliParser.parse(
            listOf("--project", "/project", "--task", "/tmp/task.json", "--summary", "summary.json")
        )

        assertEquals("/project", options.projectDir.path)
        assertEquals("/tmp/task.json", options.taskFile.path)
        assertEquals("summary.json", options.summaryFile?.path)
    }

    @Test
    fun `rejects unknown and missing arguments`() {
        assertFailsWith<IllegalStateException> {
            HeadlessCliParser.parse(listOf("--project", "/project"))
        }
        assertFailsWith<IllegalStateException> {
            HeadlessCliParser.parse(listOf("--unknown", "value", "--project", "/project", "--task", "/task.json"))
        }
    }
}

