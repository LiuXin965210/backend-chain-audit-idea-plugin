package com.liuxin.backendchain.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
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
    @Volatile private var cached: CachedResult? = null

    fun subscribe(parent: Disposable, listener: (AnalysisResult) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
        cached?.result?.let(listener)
    }

    fun analyzeMethod(method: PsiMethod, entry: EntryPoint = methodEntry(method), options: AnalysisOptions = AnalysisOptions()) {
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        val cacheKey = "${method.methodKey()}:$options"
        cached?.takeIf { it.key == cacheKey && it.modificationCount == stamp }?.result?.let {
            publish(it); return
        }
        runBackground("分析 ${entry.displayName}") {
            val result = CallGraphAnalyzer(project, options, extractors()).analyze(entry, method)
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
                val results = entries.map { CallGraphAnalyzer(project, options, extractors()).analyze(it.entry, it.method) }
                merge(results)
            }
        }
    }

    private fun runBackground(title: String, action: () -> AnalysisResult) {
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val result = ReadAction.nonBlocking(action)
                    .inSmartMode(project)
                    .expireWith(project)
                    .submit(AppExecutorUtil.getAppExecutorService())
                    .get()
                publish(result)
            }
        }.queue()
    }

    private fun publish(result: AnalysisResult) = com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
        listeners.forEach { it(result) }
    }

    private fun extractors(): List<ResourceExtractor> = listOf(
        MyBatisExtractor(), JpaExtractor(), InfrastructureExtractor(), ExternalHttpExtractor()
    )

    private fun merge(results: List<AnalysisResult>): AnalysisResult {
        if (results.size == 1) return results.first()
        val methods = results.flatMap { it.callGraph.methods.values }.associateBy { it.key }
        return AnalysisResult(
            EntryPoint(results.first().entry.type, results.joinToString { it.entry.displayName }),
            CallGraph(results.first().callGraph.root, methods, results.flatMap { it.callGraph.edges }),
            results.flatMap { it.resources }.distinctBy { "${it.type}:${it.name}:${it.operation}" },
            results.flatMap { it.warnings } + AnalysisWarning("同一入口匹配 ${results.size} 个方法，已合并全部候选")
        )
    }

    private data class CachedResult(val key: String, val modificationCount: Long, val result: AnalysisResult)

    companion object {
        fun methodEntry(method: PsiMethod) = EntryPoint(EntryType.METHOD, "${method.ownerName()}.${method.name}")
    }
}
