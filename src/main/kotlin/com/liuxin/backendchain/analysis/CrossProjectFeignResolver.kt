package com.liuxin.backendchain.analysis

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.progress.ProgressManager
import com.liuxin.backendchain.model.AnalysisOptions

internal data class CrossProjectTarget(
    val project: Project,
    val entry: LocatedEntry
)

internal class CrossProjectFeignResolver(
    private val options: AnalysisOptions,
    private val statusPublisher: ((String) -> Unit)? = null
) {
    fun resolve(sourceProject: Project, endpoint: FeignEndpoint): ResolveResult {
        ProgressManager.checkCanceled()
        statusPublisher?.invoke("发现 Feign 调用：${endpoint.service} ${endpoint.httpMethod} ${endpoint.path}，定位目标工程...")
        val mapping = mappingValue(endpoint) ?: return ResolveResult.Warning(
            "Feign ${endpoint.service} ${endpoint.httpMethod} ${endpoint.path} 未续链：未配置服务映射"
        )
        val targetProject = ProjectManager.getInstance().openProjects.firstOrNull { project ->
            ProgressManager.checkCanceled()
            !project.isDisposed && (project.name == mapping || project.basePath == mapping)
        } ?: return ResolveResult.Warning(
            "Feign ${endpoint.service} ${endpoint.httpMethod} ${endpoint.path} 未续链：目标工程未打开（映射：$mapping）"
        )
        if (targetProject == sourceProject) {
            return ResolveResult.Warning(
                "Feign ${endpoint.service} ${endpoint.httpMethod} ${endpoint.path} 未续链：目标工程与当前工程相同"
            )
        }
        if (DumbService.isDumb(targetProject)) {
            return ResolveResult.Warning(
                "Feign ${endpoint.service} ${endpoint.httpMethod} ${endpoint.path} 未续链：目标工程 ${targetProject.auditName()} 正在索引"
            )
        }
        ProgressManager.checkCanceled()
        val entries = EntryPointLocator(
            targetProject,
            options.customMqProducerAnnotations,
            options.customMqConsumerAnnotations,
            options.customMqProducerClasses,
            options.customMqConsumerInterfaces
        ).byHttpPath(endpoint.path)
        return when {
            entries.isEmpty() -> ResolveResult.Warning(
                "Feign ${endpoint.service} ${endpoint.httpMethod} ${endpoint.path} 未续链：目标工程 ${targetProject.auditName()} 未找到入口"
            )
            entries.size > 1 -> ResolveResult.Warning(
                "Feign ${endpoint.service} ${endpoint.httpMethod} ${endpoint.path} 未续链：目标工程 ${targetProject.auditName()} 命中多个入口"
            )
            else -> {
                statusPublisher?.invoke("目标工程 ${targetProject.auditName()}：命中 ${endpoint.httpMethod} ${endpoint.path}，继续扫描...")
                ResolveResult.Target(CrossProjectTarget(targetProject, entries.single()))
            }
        }
    }

    private fun mappingValue(endpoint: FeignEndpoint): String? {
        val candidates = listOf(
            endpoint.service,
            endpoint.serviceKey,
            endpoint.service.removeSurrounding("\${", "}"),
            endpoint.serviceKey.removeSurrounding("\${", "}")
        ).distinct()
        return candidates.firstNotNullOfOrNull { options.crossProjectFeignMappings[it] }
    }

    sealed class ResolveResult {
        data class Target(val target: CrossProjectTarget) : ResolveResult()
        data class Warning(val message: String) : ResolveResult()
    }
}
