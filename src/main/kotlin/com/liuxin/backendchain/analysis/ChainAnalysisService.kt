package com.liuxin.backendchain.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.concurrency.AppExecutorUtil
import com.liuxin.backendchain.model.*
import java.util.concurrent.CopyOnWriteArrayList

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
            val result = CallGraphAnalyzer(project, options, extractors(options)).analyze(entry, method)
            cached = CachedResult(cacheKey, stamp, result)
            result
        }
    }

    fun analyzeHttpPath(path: String, options: AnalysisOptions = AnalysisOptions()) = locateAndAnalyze("HTTP $path", options) {
        EntryPointLocator(project).byHttpPath(path)
    }

    fun analyzeMqTopic(topic: String, options: AnalysisOptions = AnalysisOptions()) = locateAndAnalyze("MQ $topic", options) {
        EntryPointLocator(project).byMqTopic(topic)
    }

    private fun locateAndAnalyze(label: String, options: AnalysisOptions, locate: () -> List<LocatedEntry>) {
        runBackground("定位 $label") {
            val entries = locate()
            if (entries.isEmpty()) {
                AnalysisResult(
                    EntryPoint(if (label.startsWith("HTTP")) EntryType.HTTP else EntryType.MQ, label),
                    CallGraph("", emptyMap(), emptyList()), emptyList(),
                    listOf(AnalysisWarning("未找到入口：$label"))
                )
            } else {
                val results = entries.map { CallGraphAnalyzer(project, options, extractors(options)).analyze(it.entry, it.method) }
                merge(results, options)
            }
        }
    }

    private fun runBackground(title: String, action: () -> AnalysisResult) {
        publishStatus(AnalysisStatus("$title，等待 IDEA 索引并开始扫描...", running = true))
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = ReadAction.nonBlocking(action)
                        .inSmartMode(project)
                        .expireWith(project)
                        .submit(AppExecutorUtil.getAppExecutorService())
                        .get()
                    publishStatus(AnalysisStatus("$title，扫描完成", running = false))
                    publish(result)
                } catch (e: ProcessCanceledException) {
                    publishStatus(AnalysisStatus("$title，扫描已取消", running = false))
                    throw e
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

    private fun publish(result: AnalysisResult) = com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
        listeners.forEach { it(result) }
    }

    private fun publishStatus(status: AnalysisStatus) =
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            statusListeners.forEach { it(status) }
        }

    private fun extractors(options: AnalysisOptions): List<ResourceExtractor> = listOf(
        MyBatisExtractor(), JpaExtractor(), InfrastructureExtractor(),
        ExternalHttpExtractor(options.customHttpClientClassPrefixes)
    )

    private fun merge(results: List<AnalysisResult>, options: AnalysisOptions): AnalysisResult {
        if (results.size == 1) return results.first()
        val methods = results.flatMap { it.callGraph.methods.values }.associateBy { it.key }
        val resources = results.flatMap { it.resources }
        val mergedResources = if (options.deduplicateResources) {
            resources.distinctBy {
                when (it.type) {
                    ResourceType.MYSQL, ResourceType.EXTERNAL_HTTP -> "${it.type}:${it.name}:${it.operation}"
                    else -> "${it.type}:${it.name}:${it.operation}:${it.detail}"
                }
            }
        } else {
            resources.distinctBy { "${it.type}:${it.name}:${it.operation}:${it.detail}" }
        }
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

        fun methodEntry(method: PsiMethod) = EntryPoint(EntryType.METHOD, "${method.ownerName()}.${method.name}")
    }
}
