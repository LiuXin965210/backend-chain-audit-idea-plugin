package com.liuxin.backendchain.analysis

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.liuxin.backendchain.model.EntryPoint
import com.liuxin.backendchain.model.EntryType

data class LocatedEntry(val entry: EntryPoint, val method: PsiMethod)

class EntryPointLocator(private val project: Project) {
    private val mappings = listOf("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping")

    fun byHttpPath(path: String): List<LocatedEntry> {
        val expected = normalizePath(path)
        return allProjectMethods().mapNotNull { method ->
            ProgressManager.checkCanceled()
            val methodMapping = mappings.firstNotNullOfOrNull { name ->
                annotation(method, name)?.let { name to it }
            } ?: return@mapNotNull null
            val classPath = annotationString(annotation(method.containingClass, "RequestMapping"), "value", "path")
            val methodPath = annotationString(methodMapping.second, "value", "path")
            if (normalizePath(classPath, methodPath) != expected) return@mapNotNull null
            val httpMethod = when (methodMapping.first) {
                "GetMapping" -> "GET"
                "PostMapping" -> "POST"
                "PutMapping" -> "PUT"
                "DeleteMapping" -> "DELETE"
                "PatchMapping" -> "PATCH"
                else -> requestMappingMethod(methodMapping.second) ?: "未声明"
            }
            LocatedEntry(EntryPoint(EntryType.HTTP, "$httpMethod $expected", httpMethod, expected), method)
        }.toList()
    }

    fun byMqTopic(topic: String): List<LocatedEntry> = allProjectMethods().mapNotNull { method ->
        ProgressManager.checkCanceled()
        val text = method.text
        val listener = listOf("KafkaListener", "RabbitListener", "JshRabbitConsumer")
            .firstNotNullOfOrNull { annotation(method, it) }
        val declared = annotationString(listener, "topics", "topic", "queues", "queue", "value", "name")
        if (declared != topic && !text.contains(topic)) return@mapNotNull null
        LocatedEntry(EntryPoint(EntryType.MQ, topic, pathOrTopic = topic), method)
    }.toList()

    private fun allProjectMethods(): Sequence<PsiMethod> {
        val manager = PsiManager.getInstance(project)
        return FileTypeIndex.getFiles(JavaFileType.INSTANCE, GlobalSearchScope.projectScope(project)).asSequence()
            .mapNotNull { manager.findFile(it) as? PsiJavaFile }
            .flatMap { PsiTreeUtil.findChildrenOfType(it, PsiMethod::class.java).asSequence() }
    }

    private fun requestMappingMethod(annotation: PsiAnnotation): String? {
        val text = annotation.findAttributeValue("method")?.text ?: return null
        return Regex("RequestMethod\\.([A-Z]+)").find(text)?.groupValues?.get(1)
    }
}
