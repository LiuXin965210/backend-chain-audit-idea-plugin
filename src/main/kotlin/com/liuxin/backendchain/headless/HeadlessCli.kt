package com.liuxin.backendchain.headless

import java.io.File

data class HeadlessCliOptions(
    val projectDir: File,
    val taskFile: File,
    val summaryFile: File?
)

object HeadlessCliParser {
    fun parse(args: List<String>): HeadlessCliOptions {
        val values = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val name = args[index]
            if (name !in setOf("--project", "--task", "--summary")) {
                error("未知参数：$name")
            }
            val value = args.getOrNull(index + 1)?.takeIf { !it.startsWith("--") }
                ?: error("参数 $name 缺少值")
            values[name] = value
            index += 2
        }
        val project = values["--project"]?.let(::File) ?: error("缺少 --project")
        val task = values["--task"]?.let(::File) ?: error("缺少 --task")
        return HeadlessCliOptions(
            projectDir = project,
            taskFile = task,
            summaryFile = values["--summary"]?.let(::File)
        )
    }

    fun usage(): String =
        "Usage: idea backend-chain-audit --project <projectDir> --task <task.json> [--summary <summary.json>]"
}

