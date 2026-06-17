package com.liuxin.backendchain.silent

import java.io.File

object SilentTaskPaths {
    fun outputFile(projectDir: File, path: String): File {
        val raw = File(path)
        if (raw.isAbsolute) error("输出路径必须是项目内相对路径：$path")
        val base = projectDir.canonicalFile
        val file = File(base, path).canonicalFile
        if (!file.path.startsWith(base.path + File.separator)) {
            error("输出路径不能逃逸项目目录：$path")
        }
        file.parentFile?.mkdirs()
        return file
    }
}

