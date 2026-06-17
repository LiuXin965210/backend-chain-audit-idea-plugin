package com.liuxin.backendchain.headless

import com.intellij.ide.CliResult
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.io.File

class BackendChainAuditStarter : ApplicationStarter {
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT

    override fun premain(args: List<String>) = Unit

    override fun main(args: List<String>) {
        val exitCode = try {
            runCommand(normalizeArgs(args))
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            System.err.println("Backend Chain Audit 执行失败：${cause.message ?: cause.javaClass.simpleName}")
            1
        }
        exit(exitCode)
    }

    override val isHeadless: Boolean = true

    override fun canProcessExternalCommandLine(): Boolean = true

    override suspend fun processExternalCommandLine(args: List<String>, currentDirectory: String?): CliResult {
        return try {
            val exitCode = runCommand(normalizeArgs(args))
            CliResult(exitCode, if (exitCode == 0) "Backend Chain Audit 执行完成" else "Backend Chain Audit 执行失败")
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            CliResult(1, cause.message ?: cause.javaClass.simpleName)
        }
    }

    private fun runCommand(args: List<String>): Int {
        val options = HeadlessCliParser.parse(args)
        val projectDir = options.projectDir.canonicalFile
        val taskFile = options.taskFile.canonicalFile
        if (!projectDir.isDirectory) error("--project 不是目录：${projectDir.absolutePath}")
        if (!taskFile.isFile) error("--task 不是文件：${taskFile.absolutePath}")

        val project = openProject(projectDir) ?: error("无法打开项目：${projectDir.absolutePath}")
        return try {
            val summary = try {
                HeadlessTaskRunner(project, projectDir).run(taskFile)
            } catch (e: Throwable) {
                val cause = e.cause ?: e
                HeadlessExecutionSummary(
                    totalTasks = 0,
                    successfulTasks = 0,
                    failedTasks = 1,
                    outputs = emptyList(),
                    errors = listOf(cause.message ?: cause.javaClass.simpleName)
                )
            }
            options.summaryFile?.let { HeadlessSummaryWriter.write(summary, resolveSummaryFile(projectDir, it)) }
            printSummary(summary)
            if (summary.failed) 1 else 0
        } finally {
            ProjectManagerEx.getInstanceEx().forceCloseProject(project, true)
        }
    }

    private fun openProject(projectDir: File): Project? =
        ProjectManagerEx.getInstanceEx().openProject(projectDir.toPath(), OpenProjectTask.build())

    private fun resolveSummaryFile(projectDir: File, file: File): File =
        if (file.isAbsolute) file else File(projectDir, file.path)

    private fun printSummary(summary: HeadlessExecutionSummary) {
        println(
            "Backend Chain Audit：总任务 ${summary.totalTasks}，成功 ${summary.successfulTasks}，失败 ${summary.failedTasks}"
        )
        summary.outputs.forEach { println("输出：$it") }
        summary.errors.forEach { System.err.println(it) }
    }

    private fun normalizeArgs(args: List<String>): List<String> =
        if (args.firstOrNull() == COMMAND_NAME) args.drop(1) else args

    private fun exit(exitCode: Int) {
        val application = ApplicationManager.getApplication()
        if (application is ApplicationEx) {
            application.exit(exitCode, ApplicationEx.EXIT_CONFIRMED)
        }
    }

    companion object {
        const val COMMAND_NAME = "backend-chain-audit"
    }
}
