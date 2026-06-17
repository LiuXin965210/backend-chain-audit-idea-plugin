package com.liuxin.backendchain.headless

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeadlessOutputPathsTest {
    @Test
    fun `resolves relative output from project directory and creates parent`() {
        val projectDir = Files.createTempDirectory("backend-chain-audit").toFile()

        val output = HeadlessOutputPaths.outputFile(projectDir, "build/backend-chain-audit/a.md")

        assertTrue(output.path.startsWith(projectDir.canonicalPath + File.separator))
        assertTrue(output.parentFile.isDirectory)
    }

    @Test
    fun `keeps absolute output path`() {
        val projectDir = Files.createTempDirectory("backend-chain-audit").toFile()
        val absolute = Files.createTempDirectory("backend-chain-audit-output").resolve("a.md").toFile()

        val output = HeadlessOutputPaths.outputFile(projectDir, absolute.absolutePath)

        assertEquals(absolute.canonicalPath, output.path)
        assertTrue(output.parentFile.isDirectory)
    }
}

