package com.liuxin.backendchain.silent

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.liuxin.backendchain.analysis.ChainAnalysisService
import com.liuxin.backendchain.export.ResultExporter
import com.liuxin.backendchain.model.AnalysisResult
import com.liuxin.backendchain.settings.ChainAuditSettings
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class SilentTaskRunner(private val project: Project) {
    fun runIfPresent() {
        val projectDir = project.basePath?.let { File(it) } ?: return
        val taskFile = File(projectDir, TASK_FILE)
        if (!taskFile.isFile) return
        object : Task.Backgroundable(project, "Backend Chain Audit 静默任务", true) {
            override fun run(indicator: ProgressIndicator) {
                execute(taskFile, projectDir, indicator)
            }
        }.queue()
    }

    fun execute(taskFile: File, projectDir: File, indicator: ProgressIndicator): SilentTaskExecutionSummary {
        val failures = mutableListOf<String>()
        var taskCount = 0
        return try {
            val taskFileModel = SilentTaskParser.parse(taskFile.readText())
            taskCount = taskFileModel.tasks.size
            taskFileModel.tasks.forEachIndexed { index, task ->
                ProgressManager.checkCanceled()
                indicator.text = "Backend Chain Audit 静默任务 ${index + 1}/$taskCount"
                try {
                    executeTask(task, index + 1, taskCount, projectDir, indicator)
                } catch (e: Throwable) {
                    val cause = e.cause ?: e
                    LOG.warn("Backend chain silent task failed", cause)
                    failures += "任务 ${index + 1} 失败：${cause.message ?: cause.javaClass.simpleName}"
                }
                indicator.fraction = (index + 1).toDouble() / taskCount.coerceAtLeast(1)
            }
            val summary = SilentTaskExecutionSummary(taskCount, failures)
            archive(taskFile, summary.failed, failures)
            summary
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            LOG.warn("Backend chain silent task file failed", cause)
            failures += "任务文件失败：${cause.message ?: cause.javaClass.simpleName}"
            val summary = SilentTaskExecutionSummary(taskCount, failures)
            archive(taskFile, failed = true, failures)
            summary
        }
    }

    private fun executeTask(
        task: SilentTask,
        index: Int,
        total: Int,
        projectDir: File,
        indicator: ProgressIndicator
    ) {
        when (task) {
            is SilentTask.Http -> {
                indicator.text2 = "HTTP ${task.input}"
                exportResult(analyze { it.analyzeHttpPathSilently(task.input, options()) }, task.outputs, projectDir)
            }
            is SilentTask.Mq -> {
                indicator.text2 = "MQ ${task.input}"
                exportResult(analyze { it.analyzeMqTopicSilently(task.input, options()) }, task.outputs, projectDir)
            }
            is SilentTask.BatchHttp -> {
                indicator.text2 = "批量 HTTP ${task.inputs.size} 个"
                exportBatch(task, index, total, projectDir, indicator)
            }
        }
    }

    private fun analyze(action: (ChainAnalysisService) -> AnalysisResult): AnalysisResult =
        ReadAction.nonBlocking<AnalysisResult> {
            action(project.service())
        }
            .inSmartMode(project)
            .expireWith(project)
            .submit(AppExecutorUtil.getAppExecutorService())
            .get()

    private fun exportResult(result: AnalysisResult, outputs: SilentTaskOutputs, projectDir: File) {
        outputs.markdown?.let { SilentTaskPaths.outputFile(projectDir, it).writeText(ResultExporter.markdown(result)) }
        outputs.csv?.let { SilentTaskPaths.outputFile(projectDir, it).writeText(ResultExporter.csv(result)) }
        outputs.mermaid?.let { SilentTaskPaths.outputFile(projectDir, it).writeText(ResultExporter.mermaid(result)) }
    }

    private fun exportBatch(
        task: SilentTask.BatchHttp,
        index: Int,
        total: Int,
        projectDir: File,
        indicator: ProgressIndicator
    ) {
        if (!task.outputs.mermaid.isNullOrBlank()) error("batchHttp 不支持 mermaid 输出")
        val report = project.service<ChainAnalysisService>().analyzeBatchHttpPathsSilently(task.inputs, options()) { row, rows, input ->
            indicator.text = "Backend Chain Audit 静默任务 $index/$total"
            indicator.text2 = "批量 HTTP $row/$rows：$input"
        }
        task.outputs.markdown?.let { SilentTaskPaths.outputFile(projectDir, it).writeText(ResultExporter.batchMarkdown(report)) }
        task.outputs.csv?.let { SilentTaskPaths.outputFile(projectDir, it).writeText(ResultExporter.batchCsv(report)) }
    }

    private fun options() = project.service<ChainAuditSettings>().options()

    private fun archive(taskFile: File, failed: Boolean, failures: List<String>) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val status = if (failed) "failed" else "done"
        val archive = uniqueArchiveFile(taskFile.parentFile, "silent-task.$timestamp.$status.json")
        val renamed = taskFile.renameTo(archive)
        if (!renamed) error("无法归档静默任务文件：${taskFile.absolutePath}")
        if (failed) {
            File(archive.absolutePath.removeSuffix(".json") + ".log").writeText(failures.joinToString(System.lineSeparator()))
        }
    }

    private fun uniqueArchiveFile(parent: File, name: String): File {
        val candidate = File(parent, name)
        if (!candidate.exists()) return candidate
        val baseName = name.removeSuffix(".json")
        var counter = 1
        while (true) {
            val next = File(parent, "$baseName-$counter.json")
            if (!next.exists()) return next
            counter++
        }
    }

    companion object {
        const val TASK_FILE = ".backend-chain-audit/silent-task.json"
        private val LOG = Logger.getInstance(SilentTaskRunner::class.java)
    }
}

data class SilentTaskExecutionSummary(
    val taskCount: Int,
    val failures: List<String>
) {
    val failed: Boolean get() = failures.isNotEmpty()
}

