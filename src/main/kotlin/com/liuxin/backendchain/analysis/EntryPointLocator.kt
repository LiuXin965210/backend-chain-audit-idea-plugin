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
import com.liuxin.backendchain.model.Operation
import com.liuxin.backendchain.model.ResourceType

data class LocatedEntry(val entry: EntryPoint, val method: PsiMethod)

class EntryPointLocator(
    private val project: Project,
    private val customMqProducerAnnotations: List<String> = listOf("JshRabbitProducer"),
    private val customMqConsumerAnnotations: List<String> = listOf("JshRabbitConsumer")
) {
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
        val expectedTopic = topic.substringBefore(':')
        consumerTopic(method)?.takeIf { matchesTopic(it, topic, expectedTopic) }?.let {
            return@mapNotNull LocatedEntry(EntryPoint(EntryType.MQ, topic, pathOrTopic = topic), method)
        }
        val extractor = InfrastructureExtractor(customMqProducerAnnotations, customMqConsumerAnnotations)
        val contexts = sequenceOf(CallContext(method, null, method, text)) +
            findMethodCalls(method).asSequence()
                .filter { it.methodExpression.referenceName == "subscribe" }
                .map { call -> CallContext(method, call, call.resolveMethod(), call.text) }
        val consumes = contexts.flatMap { extractor.extract(it).asSequence() }
            .filter { it.operation == Operation.CONSUME && it.type in setOf(ResourceType.KAFKA, ResourceType.RABBITMQ, ResourceType.ROCKETMQ) }
            .map { it.name }
            .toList()
        val matchesResource = consumes.any { matchesTopic(it, topic, expectedTopic) }
        if (!matchesResource && !text.contains(topic) && !text.contains(expectedTopic)) {
            return@mapNotNull null
        }
        LocatedEntry(EntryPoint(EntryType.MQ, topic, pathOrTopic = topic), method)
    }.toList()

    private fun consumerTopic(method: PsiMethod): String? =
        annotationString(annotation(method, "KafkaListener"), "topics", "topic", "value")
            ?: annotationString(annotation(method, "RabbitListener"), "queues", "queue", "value", "name")
            ?: customConsumerTopic(method)
            ?: rocketConsumerTopic(method)

    private fun customConsumerTopic(method: PsiMethod): String? {
        val configured = method.annotations.firstOrNull { matchesConfiguredAnnotation(it, customMqConsumerAnnotations) }
            ?: method.containingClass?.annotations?.firstOrNull { matchesConfiguredAnnotation(it, customMqConsumerAnnotations) }
            ?: return null
        return annotationString(configured, "topics", "topic", "queues", "queue", "value", "name")
    }

    private fun rocketConsumerTopic(method: PsiMethod): String? {
        val listener = annotation(method, "RocketMQMessageListener")
            ?: annotation(method.containingClass, "RocketMQMessageListener")?.takeIf { method.name == "onMessage" }
            ?: return null
        val topic = annotationString(listener, "topic") ?: return null
        val selector = annotationString(listener, "selectorExpression")
        return if (selector.isNullOrBlank() || selector == "*") topic else "$topic:$selector"
    }

    private fun matchesTopic(candidate: String, topic: String, expectedTopic: String): Boolean =
        candidate == topic || candidate == expectedTopic || candidate.substringBefore(':') == expectedTopic

    private fun matchesConfiguredAnnotation(annotation: PsiAnnotation, configured: List<String>): Boolean {
        val qualifiedName = annotation.qualifiedName.orEmpty()
        val shortName = annotation.nameReferenceElement?.referenceName.orEmpty()
        return configured.any { it == qualifiedName || it == shortName || qualifiedName.endsWith(".$it") }
    }

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
