package com.liuxin.backendchain.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager
import com.liuxin.backendchain.model.*

class CallGraphAnalyzer(
    private val project: Project,
    private val options: AnalysisOptions,
    private val extractors: List<ResourceExtractor>
) {
    private val methods = linkedMapOf<String, MethodRef>()
    private val edges = mutableListOf<CallEdge>()
    private val resources = mutableListOf<ResourceRef>()
    private val warnings = mutableListOf<AnalysisWarning>()
    private val expanded = mutableSetOf<String>()

    fun analyze(entry: EntryPoint, root: PsiMethod): AnalysisResult {
        trace(root, 0)
        if (options.followLocalMqConsumers) followMqConsumers(root.methodKey())
        return AnalysisResult(
            entry,
            CallGraph(root.methodKey(), methods.toMap(), edges.distinctBy { "${it.caller}->${it.callee}:${it.reason}" }),
            resources.distinctBy { "${it.type}:${it.name}:${it.operation}:${it.detail}" },
            warnings.distinct()
        )
    }

    private fun trace(method: PsiMethod, depth: Int) {
        ProgressManager.checkCanceled()
        register(method)
        val key = method.methodKey()
        if (!expanded.add(key)) return
        if (depth >= options.maxDepth) {
            warnings += AnalysisWarning("调用链达到最大深度 ${options.maxDepth}：${method.ownerName()}.${method.name}")
            return
        }

        collectResources(CallContext(method, null, method, method.text ?: ""))
        findMethodCalls(method).forEach { call ->
            ProgressManager.checkCanceled()
            val resolved = call.resolveMethod()
            if (resolved == null) {
                warnings += AnalysisWarning("无法解析调用：${call.methodExpression.text}")
                return@forEach
            }
            collectResources(CallContext(method, call, resolved, call.text ?: ""))
            register(resolved)
            edges += edge(method, resolved, Confidence.CONFIRMED, "PSI resolve", call)

            val implementations = resolveImplementations(project, resolved, call)
            if (implementations.size > 1) {
                implementations.forEach { implementation ->
                    register(implementation)
                    edges += edge(resolved, implementation, Confidence.INFERRED, "接口存在多个实现", call)
                    if (shouldExpand(implementation)) trace(implementation, depth + 1)
                }
            } else {
                val target = implementations.singleOrNull() ?: resolved
                if (target != resolved) {
                    register(target)
                    edges += edge(resolved, target, Confidence.INFERRED, "唯一实现类", call)
                }
                if (shouldExpand(target)) trace(target, depth + 1)
            }
        }
    }

    private fun followMqConsumers(rootKey: String) {
        val topics = resources.filter { it.operation == Operation.PRODUCE && it.type in setOf(ResourceType.KAFKA, ResourceType.RABBITMQ) }
            .map { it.name }.distinct()
        val locator = EntryPointLocator(project)
        topics.forEach { topic ->
            locator.byMqTopic(topic).forEach { located ->
                val consumer = located.method
                if (consumer.methodKey() !in methods) {
                    register(consumer)
                    edges += CallEdge(rootKey, consumer.methodKey(), Confidence.INFERRED, "本地 MQ 消费者：$topic", pointer(consumer))
                    trace(consumer, 1)
                }
            }
        }
    }

    private fun collectResources(context: CallContext) {
        extractors.filter { it.supports(context) }.forEach { extractor ->
            try {
                resources += extractor.extract(context)
            } catch (e: Exception) {
                warnings += AnalysisWarning("${extractor.javaClass.simpleName} 解析失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun shouldExpand(method: PsiMethod): Boolean {
        val owner = method.ownerName()
        return method.isProjectSource(project) && options.excludedPackagePrefixes.none(owner::startsWith)
    }

    private fun register(method: PsiMethod) {
        val key = method.methodKey()
        methods.putIfAbsent(key, MethodRef(key, method.ownerName(), method.name, pointer(method), !method.isProjectSource(project)))
    }

    private fun edge(caller: PsiMethod, callee: PsiMethod, confidence: Confidence, reason: String, source: com.intellij.psi.PsiElement) =
        CallEdge(caller.methodKey(), callee.methodKey(), confidence, reason, pointer(source))

    private fun pointer(element: com.intellij.psi.PsiElement) =
        SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
}
