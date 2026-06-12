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
            if (isExcluded(resolved)) return@forEach
            register(resolved)
            edges += edge(method, resolved, Confidence.CONFIRMED, "PSI resolve", call)
            traceDispatchTargets(resolved, call, call, depth)
        }

        findMethodReferences(method).forEach { reference ->
            ProgressManager.checkCanceled()
            val resolved = reference.resolve() as? PsiMethod
            if (resolved == null) {
                warnings += AnalysisWarning("无法解析方法引用：${reference.text}")
                return@forEach
            }
            if (isExcluded(resolved)) return@forEach
            register(resolved)
            edges += edge(method, resolved, Confidence.CONFIRMED, "PSI 方法引用 resolve", reference)
            traceDispatchTargets(resolved, null, reference, depth)
        }
    }

    private fun traceDispatchTargets(
        resolved: PsiMethod,
        call: com.intellij.psi.PsiMethodCallExpression?,
        source: com.intellij.psi.PsiElement,
        depth: Int
    ) {
        val targets = resolveImplementations(project, resolved, call).ifEmpty { listOf(resolved) }
        val alternatives = targets.filter { it.methodKey() != resolved.methodKey() }
        alternatives.forEach { implementation ->
            register(implementation)
            val reason = if (resolved.containingClass?.isInterface == true || resolved.hasModifierProperty(com.intellij.psi.PsiModifier.ABSTRACT)) {
                if (targets.size == 1) "唯一实现类" else "接口存在多个实现"
            } else {
                "运行时可能调用覆写方法"
            }
            edges += edge(resolved, implementation, Confidence.INFERRED, reason, source)
        }
        targets.distinctBy { it.methodKey() }.forEach { target ->
            if (shouldExpand(target)) trace(target, depth + 1)
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
        return method.isProjectSource(project) && !isExcluded(method)
    }

    private fun isExcluded(method: PsiMethod): Boolean =
        options.excludedPackagePrefixes.any(method.ownerName()::startsWith)

    private fun register(method: PsiMethod) {
        val key = method.methodKey()
        methods.putIfAbsent(key, MethodRef(key, method.ownerName(), method.name, pointer(method), !method.isProjectSource(project)))
    }

    private fun edge(caller: PsiMethod, callee: PsiMethod, confidence: Confidence, reason: String, source: com.intellij.psi.PsiElement) =
        CallEdge(caller.methodKey(), callee.methodKey(), confidence, reason, pointer(source))

    private fun pointer(element: com.intellij.psi.PsiElement) =
        SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
}
