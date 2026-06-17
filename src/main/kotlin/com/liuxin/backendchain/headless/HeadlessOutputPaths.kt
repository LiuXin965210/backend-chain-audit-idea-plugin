package com.liuxin.backendchain.headless

import java.io.File

object HeadlessOutputPaths {
    fun outputFile(projectDir: File, path: String): File {
        val raw = File(path)
        val file = if (raw.isAbsolute) raw else File(projectDir, path)
        file.parentFile?.mkdirs()
        return file.canonicalFile
    }
}

