package com.liuxin.backendchain.silent

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SilentTaskPathsTest {
    @Test
    fun `resolves relative output under project directory and creates parent`() {
        val projectDir = Files.createTempDirectory("backend-chain-audit").toFile()

        val output = SilentTaskPaths.outputFile(projectDir, ".backend-chain-audit/results/a.md")

        assertTrue(output.path.startsWith(projectDir.canonicalPath + File.separator))
        assertTrue(output.parentFile.isDirectory)
    }

    @Test
    fun `rejects absolute and escaping output paths`() {
        val projectDir = Files.createTempDirectory("backend-chain-audit").toFile()

        assertFailsWith<IllegalStateException> {
            SilentTaskPaths.outputFile(projectDir, "/tmp/a.md")
        }
        assertFailsWith<IllegalStateException> {
            SilentTaskPaths.outputFile(projectDir, "../a.md")
        }
    }
}

