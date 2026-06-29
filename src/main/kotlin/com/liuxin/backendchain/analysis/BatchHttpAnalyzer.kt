package com.liuxin.backendchain.analysis

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager
import com.liuxin.backendchain.model.AnalysisOptions
import com.liuxin.backendchain.model.BatchAnalysisRow
import com.liuxin.backendchain.model.BatchRowStatus
import com.liuxin.backendchain.model.MethodRef

class BatchHttpAnalyzer(
    private val project: Project,
    private val options: AnalysisOptions
) {
    private val implementationCache = mutableMapOf<String, List<PsiMethod>>()

    fun analyzeRow(index: Int, input: String): BatchAnalysisRow {
        if (!input.startsWith('/')) {
            return BatchAnalysisRow(index, input, BatchRowStatus.SKIPPED, "不是 HTTP 路径")
        }
        val locator = EntryPointLocator(
            project,
            options.customMqProducerAnnotations,
            options.customMqConsumerAnnotations,
            options.customMqProducerClasses,
            options.customMqConsumerInterfaces
        )
        val entries = locator.byHttpPath(input)
        if (entries.isEmpty()) {
            return BatchAnalysisRow(index, input, BatchRowStatus.SKIPPED, "未定位到接口")
        }
        if (entries.size > 1) {
            return BatchAnalysisRow(index, input, BatchRowStatus.SKIPPED, "定位到多个接口")
        }
        val located = entries.single()
        val root = strictBatchRoot(located.method) ?: return BatchAnalysisRow(
            index,
            input,
            BatchRowStatus.SKIPPED,
            interfaceSkipReason(located.method)
        )
        return try {
            val result = CallGraphAnalyzer(project, options, defaultExtractors(options)).analyze(located.entry, root)
            BatchAnalysisRow(index, input, BatchRowStatus.SUCCESS, result = result, analyzedMethod = methodRef(root))
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            LOG.warn("Backend chain batch row failed: $input", cause)
            BatchAnalysisRow(index, input, BatchRowStatus.FAILED, cause.message ?: cause.javaClass.simpleName)
        }
    }

    private fun strictBatchRoot(method: PsiMethod): PsiMethod? {
        if (method.containingClass?.isInterface != true && !method.hasModifierProperty(PsiModifier.ABSTRACT)) {
            return method
        }
        val implementations = resolveImplementations(project, method, cache = implementationCache)
        return implementations.singleOrNull()
    }

    private fun interfaceSkipReason(method: PsiMethod): String {
        val implementations = resolveImplementations(project, method, cache = implementationCache)
        return if (implementations.isEmpty()) "interface 方法未找到实现" else "interface 方法存在多个实现"
    }

    private fun methodRef(method: PsiMethod): MethodRef =
        MethodRef(
            method.methodKey(),
            method.ownerName(),
            method.name,
            SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method),
            !method.isProjectSource(project)
        )

    companion object {
        private val LOG = Logger.getInstance(BatchHttpAnalyzer::class.java)
    }
}

internal fun defaultExtractors(options: AnalysisOptions): List<ResourceExtractor> = listOf(
    MyBatisExtractor(), MyBatisPlusExtractor(), JpaExtractor(),
    InfrastructureExtractor(
        options.customMqProducerAnnotations,
        options.customMqConsumerAnnotations,
        options.customMqProducerClasses,
        options.customMqConsumerInterfaces
    ),
    ExternalHttpExtractor(options.customHttpClientClassPrefixes)
)
