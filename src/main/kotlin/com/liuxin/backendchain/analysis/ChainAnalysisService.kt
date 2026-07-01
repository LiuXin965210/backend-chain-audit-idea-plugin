package com.liuxin.backendchain.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.concurrency.AppExecutorUtil
import com.liuxin.backendchain.export.ResultExporter
import com.liuxin.backendchain.model.*
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Service(Service.Level.PROJECT)
class ChainAnalysisService(private val project: Project) {
    private val listeners = CopyOnWriteArrayList<(AnalysisResult) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(AnalysisStatus) -> Unit>()
    @Volatile private var cached: CachedResult? = null

    fun subscribe(parent: Disposable, listener: (AnalysisResult) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
        cached?.result?.let(listener)
    }

    fun subscribeStatus(parent: Disposable, listener: (AnalysisStatus) -> Unit) {
        statusListeners += listener
        Disposer.register(parent) { statusListeners -= listener }
    }

    fun analyzeMethod(method: PsiMethod, entry: EntryPoint = methodEntry(method), options: AnalysisOptions = AnalysisOptions()) {
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        val cacheKey = "${method.methodKey()}:$options"
        cached?.takeIf { it.key == cacheKey && it.modificationCount == stamp }?.result?.let {
            publish(it); return
        }
        runBackground("分析 ${entry.displayName}") {
            val result = CallGraphAnalyzer(project, options, defaultExtractors(options), ::publishRunningStatus).analyze(entry, method)
            cached = CachedResult(cacheKey, stamp, result)
            result
        }
    }

    fun analyzeHttpPath(path: String, options: AnalysisOptions = AnalysisOptions()) = locateAndAnalyze("HTTP $path", options) {
        EntryPointLocator(
            project,
            options.customMqProducerAnnotations,
            options.customMqConsumerAnnotations,
            options.customMqProducerClasses,
            options.customMqConsumerInterfaces
        ).byHttpPath(path)
    }

    fun analyzeMqTopic(topic: String, options: AnalysisOptions = AnalysisOptions()) = locateAndAnalyze("MQ $topic", options) {
        EntryPointLocator(
            project,
            options.customMqProducerAnnotations,
            options.customMqConsumerAnnotations,
            options.customMqProducerClasses,
            options.customMqConsumerInterfaces
        ).byMqTopic(topic)
    }

    fun analyzeBatchHttpPaths(
        inputs: List<String>,
        csvFile: File?,
        markdownFile: File?,
        excelFile: File?,
        options: AnalysisOptions = AnalysisOptions()
    ) {
        publishStatus(AnalysisStatus("批量统计等待 IDEA 索引并开始扫描...", running = true))
        object : Task.Backgroundable(project, "批量统计 HTTP 接口", true) {
            private val rows = mutableListOf<BatchAnalysisRow>()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                var canceled = false
                try {
                    val analyzer = BatchHttpAnalyzer(project, options.copy(followCrossProjectFeign = false), ::publishRunningStatus)
                    val rowTimeoutMs = options.batchRowTimeoutSeconds.takeIf { it > 0 }?.toLong()?.times(1000L)
                    inputs.forEachIndexed { offset, raw ->
                        ProgressManager.checkCanceled()
                        val index = offset + 1
                        val input = raw.trim()
                        updateBatchProgress(indicator, index, inputs.size, input)
                        try {
                            rows += runReadActionWithRetry(
                                indicator,
                                "批量统计 $input",
                                rowTimeoutMs
                            ) {
                                analyzer.analyzeRow(index, input)
                            }
                        } catch (e: AnalysisTimeoutException) {
                            LOG.warn("Backend chain batch row timed out: $input")
                            rows += BatchAnalysisRow(index, input, BatchRowStatus.FAILED, e.message)
                        }
                        indicator.fraction = index.toDouble() / inputs.size.coerceAtLeast(1)
                    }
                    indicator.fraction = 1.0
                } catch (e: ProcessCanceledException) {
                    canceled = true
                } catch (e: Throwable) {
                    val cause = e.cause ?: e
                    LOG.warn("Backend chain batch analysis failed", cause)
                    rows += BatchAnalysisRow(
                        rows.size + 1,
                        "<批量任务>",
                        BatchRowStatus.FAILED,
                        cause.message ?: cause.javaClass.simpleName
                    )
                } finally {
                    val report = BatchAnalysisReport(rows.toList(), canceled)
                    try {
                        csvFile?.writeText(ResultExporter.batchCsv(report))
                        markdownFile?.writeText(ResultExporter.batchMarkdown(report))
                        excelFile?.writeBytes(ResultExporter.batchExcel(report))
                        val suffix = if (canceled) "，用户取消，已导出完成部分" else "，扫描完成"
                        publishStatus(
                            AnalysisStatus(
                                "批量统计$suffix：${exportedFiles(csvFile, markdownFile, excelFile)}",
                                running = false
                            )
                        )
                    } catch (e: Throwable) {
                        val cause = e.cause ?: e
                        LOG.warn("Backend chain batch export failed", cause)
                        publishStatus(
                            AnalysisStatus(
                                "批量统计导出失败：${cause.message ?: cause.javaClass.simpleName}",
                                running = false,
                                error = true
                            )
                        )
                    }
                }
            }
        }.queue()
    }

    private fun updateBatchProgress(indicator: ProgressIndicator, index: Int, total: Int, input: String) {
        val message = "进度 $index/$total：$input"
        indicator.text = message
        indicator.text2 = ""
        indicator.fraction = (index - 1).toDouble() / total.coerceAtLeast(1)
        publishStatus(AnalysisStatus(message, running = true))
    }

    private fun exportedFiles(csvFile: File?, markdownFile: File?, excelFile: File?): String =
        listOfNotNull(
            csvFile?.let { "CSV ${it.absolutePath}" },
            markdownFile?.let { "Markdown ${it.absolutePath}" },
            excelFile?.let { "Excel ${it.absolutePath}" }
        ).joinToString("，")

    private fun locateAndAnalyze(label: String, options: AnalysisOptions, locate: () -> List<LocatedEntry>) {
        runBackground("定位 $label") {
            publishRunningStatus("分析 $label，等待当前工程索引...")
            val entries = locate()
            if (entries.isEmpty()) {
                AnalysisResult(
                    EntryPoint(if (label.startsWith("HTTP")) EntryType.HTTP else EntryType.MQ, label),
                    CallGraph("", emptyMap(), emptyList()), emptyList(),
                    listOf(AnalysisWarning("未找到入口：$label"))
                )
            } else {
                val results = entries.map { CallGraphAnalyzer(project, options, defaultExtractors(options), ::publishRunningStatus).analyze(it.entry, it.method) }
                merge(results, options)
            }
        }
    }

    private fun runBackground(title: String, action: () -> AnalysisResult) {
        publishStatus(AnalysisStatus("$title，等待 IDEA 索引并开始扫描...", running = true))
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = runReadActionWithRetry(indicator, title, action = action)
                    publishStatus(AnalysisStatus("$title，扫描完成", running = false))
                    publish(result)
                } catch (e: ProcessCanceledException) {
                    publishStatus(AnalysisStatus("$title，扫描已取消", running = false))
                } catch (e: Throwable) {
                    val cause = e.cause ?: e
                    LOG.warn("Backend chain analysis failed: $title", cause)
                    publishStatus(
                        AnalysisStatus(
                            "$title，扫描失败：${cause.message ?: cause.javaClass.simpleName}",
                            running = false,
                            error = true
                        )
                    )
                }
            }
        }.queue()
    }

    private fun <T> awaitCancellable(future: Future<T>, indicator: ProgressIndicator): T {
        return awaitCancellable(future, indicator, null, null, "")
    }

    private fun <T> awaitCancellable(
        future: Future<T>,
        indicator: ProgressIndicator,
        deadlineNanos: Long?,
        timeoutMs: Long?,
        timeoutLabel: String
    ): T {
        while (true) {
            try {
                indicator.checkCanceled()
                ProgressManager.checkCanceled()
                if (deadlineNanos != null && System.nanoTime() > deadlineNanos) {
                    future.cancel(true)
                    throw AnalysisTimeoutException(timeoutLabel, timeoutMs ?: 0L)
                }
                return future.get(100, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                continue
            } catch (e: ProcessCanceledException) {
                future.cancel(true)
                throw e
            } catch (e: CancellationException) {
                throw ProcessCanceledException(e)
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                if (cause is ProcessCanceledException) throw cause
                throw cause
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw ProcessCanceledException(e)
            }
        }
    }

    private fun <T> runReadActionWithRetry(
        indicator: ProgressIndicator,
        label: String,
        timeoutMs: Long? = null,
        action: () -> T
    ): T {
        var last: ProcessCanceledException? = null
        val deadlineNanos = timeoutMs?.let { System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(it) }
        repeat(READ_ACTION_MAX_ATTEMPTS) { attempt ->
            try {
                val future = ReadAction.nonBlocking(action)
                    .inSmartMode(project)
                    .expireWith(project)
                    .submit(AppExecutorUtil.getAppExecutorService())
                return awaitCancellable(future, indicator, deadlineNanos, timeoutMs, label)
            } catch (e: AnalysisTimeoutException) {
                throw e
            } catch (e: ProcessCanceledException) {
                if (indicator.isCanceled || project.isDisposed) throw e
                last = e
                val nextAttempt = attempt + 1
                if (nextAttempt >= READ_ACTION_MAX_ATTEMPTS) throw e
                publishRunningStatus("$label，IDE 取消了本次读动作，正在重试 $nextAttempt/${READ_ACTION_MAX_ATTEMPTS - 1}...")
                Thread.sleep(READ_ACTION_RETRY_DELAY_MS)
            }
        }
        throw last ?: ProcessCanceledException()
    }

    private fun publish(result: AnalysisResult) = com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
        listeners.forEach { it(result) }
    }

    private fun publishStatus(status: AnalysisStatus) =
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            statusListeners.forEach { it(status) }
        }

    private fun publishRunningStatus(message: String) = publishStatus(AnalysisStatus(message, running = true))

    private fun merge(results: List<AnalysisResult>, options: AnalysisOptions): AnalysisResult {
        if (results.size == 1) return results.first()
        val methods = results.flatMap { it.callGraph.methods.values }.associateBy { it.key }
        val resources = results.flatMap { it.resources }
        val mergedResources = ResourceDeduplicator.normalize(resources, options.deduplicateResources)
        return AnalysisResult(
            EntryPoint(results.first().entry.type, results.joinToString { it.entry.displayName }),
            CallGraph(results.first().callGraph.root, methods, results.flatMap { it.callGraph.edges }),
            mergedResources,
            results.flatMap { it.warnings } + AnalysisWarning("同一入口匹配 ${results.size} 个方法，已合并全部候选")
        )
    }

    private data class CachedResult(val key: String, val modificationCount: Long, val result: AnalysisResult)

    companion object {
        private val LOG = Logger.getInstance(ChainAnalysisService::class.java)
        private const val READ_ACTION_MAX_ATTEMPTS = 4
        private const val READ_ACTION_RETRY_DELAY_MS = 150L

        fun methodEntry(method: PsiMethod) = EntryPoint(EntryType.METHOD, "${method.ownerName()}.${method.name}")
    }
}

private class AnalysisTimeoutException(label: String, timeoutMs: Long) :
    RuntimeException("$label 超过 ${timeoutMs / 1000} 秒未完成，已跳过该接口")
