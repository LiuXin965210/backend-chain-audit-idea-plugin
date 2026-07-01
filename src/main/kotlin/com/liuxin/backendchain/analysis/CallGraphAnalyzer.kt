package com.liuxin.backendchain.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager
import com.liuxin.backendchain.model.*

class CallGraphAnalyzer(
    private val project: Project,
    private val options: AnalysisOptions,
    private val extractors: List<ResourceExtractor>,
    private val statusPublisher: ((String) -> Unit)? = null
) {
    private val methods = linkedMapOf<String, MethodRef>()
    private val edges = mutableListOf<CallEdge>()
    private val resources = mutableListOf<ResourceRef>()
    private val warnings = mutableListOf<AnalysisWarning>()
    private val expanded = mutableSetOf<String>()
    private val expandedCrossEntries = mutableSetOf<String>()
    private val crossProjectFeignCache = mutableMapOf<String, CrossProjectFeignCacheEntry>()
    private val implementationCache = mutableMapOf<String, List<PsiMethod>>()
    private val crossProjectResolver = CrossProjectFeignResolver(options, statusPublisher)

    fun analyze(entry: EntryPoint, root: PsiMethod): AnalysisResult {
        trace(root, 0, 0, TracePath(listOf(root.ownerName() + "." + root.name)))
        if (options.followLocalMqConsumers) followMqConsumers(root.methodKey())
        return AnalysisResult(
            entry,
            CallGraph(root.methodKey(), methods.toMap(), edges.distinctBy { "${it.caller}->${it.callee}:${it.reason}" }),
            normalizedResources(),
            warnings.distinct()
        )
    }

    private fun normalizedResources(): List<ResourceRef> {
        val classifiedResources = ExternalHttpResourceFilter.classify(resources, options.excludedExternalHttpPathPatterns)
        return ResourceDeduplicator.normalize(classifiedResources, options.deduplicateResources)
    }

    private fun trace(method: PsiMethod, depth: Int, crossProjectDepth: Int, path: TracePath) {
        ProgressManager.checkCanceled()
        statusPublisher?.invoke("当前工程 ${method.project.auditName()}：扫描调用链 ${method.ownerName()}.${method.name}...")
        register(method)
        val key = method.methodKey()
        if (!expanded.add(key)) return
        if (depth >= options.maxDepth) {
            warnings += AnalysisWarning("调用链达到最大深度 ${options.maxDepth}：${method.ownerName()}.${method.name}")
            return
        }

        collectResources(CallContext(method, null, method, method.text ?: ""), path, crossProjectDepth)
        findMethodCalls(method).forEach { call ->
            ProgressManager.checkCanceled()
            val resolved = call.resolveMethod()
            if (resolved == null) {
                warnings += AnalysisWarning("无法解析调用：${call.methodExpression.text}")
                return@forEach
            }
            collectResources(CallContext(method, call, resolved, call.text ?: ""), path.append(resolved), crossProjectDepth)
            if (!shouldIncludeInGraph(resolved)) return@forEach
            if (options.hideSimpleAccessors && resolved.isSimpleAccessor()) return@forEach
            register(resolved)
            edges += edge(method, resolved, Confidence.CONFIRMED, "PSI resolve", call)
            traceDispatchTargets(resolved, call, call, depth, crossProjectDepth, path)
        }

        findMethodReferences(method).forEach { reference ->
            ProgressManager.checkCanceled()
            if (reference.referenceName == "new") return@forEach
            val resolved = reference.resolve() as? PsiMethod
            if (resolved == null) {
                warnings += AnalysisWarning("无法解析方法引用：${reference.text}")
                return@forEach
            }
            if (!shouldIncludeInGraph(resolved)) return@forEach
            if (options.hideSimpleAccessors && resolved.isSimpleAccessor()) return@forEach
            register(resolved)
            edges += edge(method, resolved, Confidence.CONFIRMED, "PSI 方法引用 resolve", reference)
            traceDispatchTargets(resolved, null, reference, depth, crossProjectDepth, path)
        }
    }

    private fun traceDispatchTargets(
        resolved: PsiMethod,
        call: com.intellij.psi.PsiMethodCallExpression?,
        source: com.intellij.psi.PsiElement,
        depth: Int,
        crossProjectDepth: Int,
        path: TracePath
    ) {
        val targets = resolveImplementations(
            resolved.project,
            resolved,
            call,
            implementationCache,
            options.followConcreteMethodOverrides
        ).ifEmpty { listOf(resolved) }
            .filter(::shouldIncludeInGraph)
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
            if (shouldExpand(target)) {
                val inferredDispatch = target.methodKey() != resolved.methodKey()
                trace(target, depth + 1, crossProjectDepth, path.append(target, inferredDispatch))
            }
        }
    }

    private fun followMqConsumers(rootKey: String) {
        val topics = resources.filter {
            it.operation == Operation.PRODUCE &&
                it.type in setOf(ResourceType.KAFKA, ResourceType.RABBITMQ, ResourceType.ROCKETMQ)
        }
            .map { it.name }.distinct()
        topics.forEach { topic ->
            val locator = EntryPointLocator(
                project,
                options.customMqProducerAnnotations,
                options.customMqConsumerAnnotations,
                options.customMqProducerClasses,
                options.customMqConsumerInterfaces
            )
            locator.byMqTopic(topic).forEach { located ->
                val consumer = located.method
                if (consumer.methodKey() !in methods) {
                    register(consumer)
                    edges += CallEdge(rootKey, consumer.methodKey(), Confidence.INFERRED, "本地 MQ 消费者：$topic", pointer(consumer))
                    trace(consumer, 1, 0, TracePath(listOf(methods[rootKey]?.displayName ?: rootKey)).append(consumer, true))
                }
            }
        }
    }

    private fun collectResources(context: CallContext, path: TracePath, crossProjectDepth: Int) {
        extractors.filter { it.supports(context) }.forEach { extractor ->
            try {
                resources += extractor.extract(context).map { resource ->
                    resource.copy(
                        confidence = lessCertain(resource.confidence, path.confidence),
                        detail = "来源工程：${context.method.project.auditName()}；调用路径：${path.display()}；原始证据：${resource.detail}"
                    )
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                warnings += AnalysisWarning("${extractor.javaClass.simpleName} 解析失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
        followCrossProjectFeign(context, path, crossProjectDepth)
    }

    private fun shouldExpand(method: PsiMethod): Boolean {
        return method.isProjectSource(method.project) && shouldIncludeInGraph(method)
    }

    private fun shouldIncludeInGraph(method: PsiMethod): Boolean =
        !isExcluded(method) && (!options.onlyProjectSource || method.isProjectSource(method.project))

    private fun isExcluded(method: PsiMethod): Boolean =
        options.excludedPackagePrefixes.any(method.ownerName()::startsWith)

    private fun register(method: PsiMethod) {
        val key = method.methodKey()
        methods.putIfAbsent(
            key,
            MethodRef(
                key,
                method.ownerName(),
                method.name,
                pointer(method),
                !method.isProjectSource(method.project),
                method.project.auditName(),
                method.project.basePath
            )
        )
    }

    private fun edge(caller: PsiMethod, callee: PsiMethod, confidence: Confidence, reason: String, source: com.intellij.psi.PsiElement) =
        CallEdge(caller.methodKey(), callee.methodKey(), confidence, reason, pointer(source))

    private fun pointer(element: com.intellij.psi.PsiElement) =
        SmartPointerManager.getInstance(element.project).createSmartPsiElementPointer(element)

    private fun followCrossProjectFeign(context: CallContext, path: TracePath, crossProjectDepth: Int) {
        if (!options.followCrossProjectFeign || context.call == null) return
        val endpoint = FeignEndpointParser.parse(context) ?: return
        if (crossProjectDepth >= options.maxCrossProjectDepth) {
            warnings += AnalysisWarning("Feign ${endpoint.service} ${endpoint.httpMethod} ${endpoint.path} 未续链：达到最大跨工程深度 ${options.maxCrossProjectDepth}")
            return
        }
        val sourceProject = context.method.project
        val cacheKey = crossProjectFeignCacheKey(endpoint)
        when (val cached = crossProjectFeignCache[cacheKey]) {
            is CrossProjectFeignCacheEntry.Target -> {
                linkCrossProjectTarget(context, cached.target, path, crossProjectDepth)
                return
            }
            is CrossProjectFeignCacheEntry.Warning -> return
            null -> Unit
        }
        when (val resolved = crossProjectResolver.resolve(sourceProject, endpoint)) {
            is CrossProjectFeignResolver.ResolveResult.Warning -> {
                crossProjectFeignCache[cacheKey] = CrossProjectFeignCacheEntry.Warning
                warnings += AnalysisWarning(resolved.message)
            }
            is CrossProjectFeignResolver.ResolveResult.Target -> {
                crossProjectFeignCache[cacheKey] = CrossProjectFeignCacheEntry.Target(resolved.target)
                linkCrossProjectTarget(context, resolved.target, path, crossProjectDepth)
            }
        }
    }

    private fun linkCrossProjectTarget(
        context: CallContext,
        target: CrossProjectTarget,
        path: TracePath,
        crossProjectDepth: Int
    ) {
        val targetMethod = target.entry.method
        register(targetMethod)
        edges += CallEdge(
            context.method.methodKey(),
            targetMethod.methodKey(),
            Confidence.INFERRED,
            "跨工程 Feign：${context.method.project.auditName()} -> ${target.project.auditName()}",
            pointer(context.call!!)
        )
        val targetKey = "${target.project.basePath ?: target.project.name}|${target.entry.entry.httpMethod}|${target.entry.entry.pathOrTopic}|${targetMethod.methodKey()}"
        if (!expandedCrossEntries.add(targetKey)) return
        trace(targetMethod, 0, crossProjectDepth + 1, path.append(targetMethod, true))
    }

    private fun crossProjectFeignCacheKey(endpoint: FeignEndpoint): String =
        listOf(
            endpoint.serviceKey,
            endpoint.httpMethod,
            endpoint.path
        ).joinToString("|")

    private sealed class CrossProjectFeignCacheEntry {
        data class Target(val target: CrossProjectTarget) : CrossProjectFeignCacheEntry()
        object Warning : CrossProjectFeignCacheEntry()
    }

    private fun lessCertain(left: Confidence, right: Confidence): Confidence =
        if (left.ordinal >= right.ordinal) left else right

    private data class TracePath(
        val methods: List<String>,
        val confidence: Confidence = Confidence.CONFIRMED
    ) {
        fun append(method: PsiMethod, inferred: Boolean = false): TracePath {
            val name = method.ownerName() + "." + method.name + if (inferred) " [推断分支]" else ""
            val nextConfidence = if (inferred) Confidence.INFERRED else confidence
            return TracePath(methods + name, if (confidence.ordinal >= nextConfidence.ordinal) confidence else nextConfidence)
        }

        fun display(): String {
            val visible = if (methods.size <= 8) methods else listOf(methods.first(), "...") + methods.takeLast(6)
            return visible.joinToString(" -> ")
        }
    }
}
