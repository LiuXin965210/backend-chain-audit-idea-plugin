package com.liuxin.backendchain.headless

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.liuxin.backendchain.analysis.ChainAnalysisService
import com.liuxin.backendchain.export.ResultExporter
import com.liuxin.backendchain.model.AnalysisResult
import com.liuxin.backendchain.settings.ChainAuditSettings
import java.io.File

class HeadlessTaskRunner(private val project: Project, private val projectDir: File) {
    fun run(taskFile: File): HeadlessExecutionSummary {
        if (!projectDir.isDirectory) error("--project 不是目录：${projectDir.absolutePath}")
        if (!taskFile.isFile) error("--task 不是文件：${taskFile.absolutePath}")
        DumbService.getInstance(project).waitForSmartMode()

        val tasks = HeadlessTaskParser.parse(taskFile.readText()).tasks
        val outputs = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var successfulTasks = 0

        tasks.forEachIndexed { index, task ->
            try {
                outputs += executeTask(task)
                successfulTasks++
            } catch (e: Throwable) {
                val cause = e.cause ?: e
                errors += "任务 ${index + 1} 失败：${cause.message ?: cause.javaClass.simpleName}"
            }
        }

        return HeadlessExecutionSummary(
            totalTasks = tasks.size,
            successfulTasks = successfulTasks,
            failedTasks = tasks.size - successfulTasks,
            outputs = outputs,
            errors = errors
        )
    }

    private fun executeTask(task: HeadlessTask): List<String> = when (task) {
        is HeadlessTask.Http -> exportResult(analyze { it.analyzeHttpPathSilently(task.input, options()) }, task.outputs)
        is HeadlessTask.Mq -> exportResult(analyze { it.analyzeMqTopicSilently(task.input, options()) }, task.outputs)
        is HeadlessTask.BatchHttp -> exportBatch(task)
    }

    private fun analyze(action: (ChainAnalysisService) -> AnalysisResult): AnalysisResult =
        ReadAction.nonBlocking<AnalysisResult> {
            action(project.service())
        }
            .inSmartMode(project)
            .expireWith(project)
            .submit(AppExecutorUtil.getAppExecutorService())
            .get()

    private fun exportResult(result: AnalysisResult, outputs: HeadlessTaskOutputs): List<String> {
        val files = mutableListOf<String>()
        outputs.markdown?.let {
            val file = HeadlessOutputPaths.outputFile(projectDir, it)
            file.writeText(ResultExporter.markdown(result))
            files += file.absolutePath
        }
        outputs.csv?.let {
            val file = HeadlessOutputPaths.outputFile(projectDir, it)
            file.writeText(ResultExporter.csv(result))
            files += file.absolutePath
        }
        outputs.mermaid?.let {
            val file = HeadlessOutputPaths.outputFile(projectDir, it)
            file.writeText(ResultExporter.mermaid(result))
            files += file.absolutePath
        }
        return files
    }

    private fun exportBatch(task: HeadlessTask.BatchHttp): List<String> {
        if (!task.outputs.mermaid.isNullOrBlank()) error("batchHttp 不支持 mermaid 输出")
        val report = project.service<ChainAnalysisService>().analyzeBatchHttpPathsSilently(task.inputs, options())
        val files = mutableListOf<String>()
        task.outputs.markdown?.let {
            val file = HeadlessOutputPaths.outputFile(projectDir, it)
            file.writeText(ResultExporter.batchMarkdown(report))
            files += file.absolutePath
        }
        task.outputs.csv?.let {
            val file = HeadlessOutputPaths.outputFile(projectDir, it)
            file.writeText(ResultExporter.batchCsv(report))
            files += file.absolutePath
        }
        return files
    }

    private fun options() = project.service<ChainAuditSettings>().options()
}
