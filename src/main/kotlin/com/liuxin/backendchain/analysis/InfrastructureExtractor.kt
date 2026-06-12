package com.liuxin.backendchain.analysis

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.SmartPointerManager
import com.liuxin.backendchain.model.*

class InfrastructureExtractor : ResourceExtractor {
    override fun extract(context: CallContext): List<ResourceRef> {
        val result = mutableListOf<ResourceRef>()
        val call = context.call
        val target = context.resolvedMethod
        val owner = target?.containingClass?.qualifiedName.orEmpty()
        val name = target?.name.orEmpty()
        val args = call?.argumentList?.expressions.orEmpty().mapNotNull(::constantString)
        val pointer = (call ?: target ?: context.method).let {
            SmartPointerManager.getInstance(it.project).createSmartPsiElementPointer(it)
        }

        val qualifierField = (call?.methodExpression?.qualifierExpression as? PsiReferenceExpression)?.resolve() as? PsiField
        val jshProducerQueue = annotationString(
            qualifierField?.annotations?.firstOrNull {
                it.qualifiedName?.endsWith(".JshRabbitProducer") == true || it.nameReferenceElement?.referenceName == "JshRabbitProducer"
            },
            "queue", "value", "name"
        )

        cacheAnnotations(context.method).forEach { cache ->
            result += ResourceRef(ResourceType.REDIS, cache, Operation.UNKNOWN, Confidence.CONFIRMED, "Spring Cache 注解", pointer)
        }
        if (owner.contains("Redis") || context.sourceText.contains("RedisTemplate")) {
            val key = args.firstOrNull() ?: call?.argumentList?.expressions?.firstOrNull()?.text
            key?.let {
                result += ResourceRef(
                    ResourceType.REDIS, it, redisOperation(name),
                    if (args.isEmpty()) Confidence.UNRESOLVED else Confidence.INFERRED,
                    "$owner.$name", pointer
                )
            }
        }

        when {
            owner.contains("Kafka") || name == "send" && context.sourceText.contains("Kafka") -> args.firstOrNull()?.let {
                result += ResourceRef(ResourceType.KAFKA, it, Operation.PRODUCE, Confidence.INFERRED, "$owner.$name", pointer)
            }
            jshProducerQueue != null -> result += ResourceRef(ResourceType.RABBITMQ, jshProducerQueue, Operation.PRODUCE, Confidence.CONFIRMED, "@JshRabbitProducer ${qualifierField?.name}", pointer)
            owner.contains("Rabbit") || name in setOf("convertAndSend", "send") && context.sourceText.contains("Rabbit") -> args.firstOrNull()?.let {
                val destination = if (name == "convertAndSend" && args.size >= 2) "${args[0]} -> ${args[1]}" else it
                result += ResourceRef(ResourceType.RABBITMQ, destination, Operation.PRODUCE, Confidence.INFERRED, "$owner.$name", pointer)
            }
        }
        listener(context.method, "KafkaListener")?.let { result += ResourceRef(ResourceType.KAFKA, it, Operation.CONSUME, Confidence.CONFIRMED, "@KafkaListener", pointer) }
        listener(context.method, "RabbitListener", "JshRabbitConsumer")?.let { result += ResourceRef(ResourceType.RABBITMQ, it, Operation.CONSUME, Confidence.CONFIRMED, "Rabbit consumer annotation", pointer) }
        return result
    }

    private fun cacheAnnotations(element: PsiElement): List<String> = listOf("Cacheable", "CachePut", "CacheEvict").mapNotNull { name ->
        val annotation = (element as? com.intellij.psi.PsiMethod)?.let { annotation(it, name) }
        annotationString(annotation, "cacheNames", "value")
    }

    private fun listener(method: com.intellij.psi.PsiMethod, vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        annotationString(annotation(method, name), "topics", "topic", "queues", "queue", "value", "name")
    }

    private fun redisOperation(name: String): Operation = if (name.startsWith("get") || name.startsWith("has") || name.startsWith("opsFor")) Operation.READ else Operation.WRITE
}
