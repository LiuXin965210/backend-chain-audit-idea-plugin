package com.liuxin.backendchain.analysis

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiVariable
import com.intellij.psi.SmartPointerManager
import com.liuxin.backendchain.model.*

class InfrastructureExtractor(
    private val customMqProducerAnnotations: List<String> = listOf("JshRabbitProducer"),
    private val customMqConsumerAnnotations: List<String> = listOf("JshRabbitConsumer")
) : ResourceExtractor {
    override fun extract(context: CallContext): List<ResourceRef> {
        val result = mutableListOf<ResourceRef>()
        val call = context.call
        val target = context.resolvedMethod
        val owner = target?.containingClass?.qualifiedName.orEmpty()
        val name = target?.name.orEmpty()
        val args = call?.argumentList?.expressions.orEmpty().mapNotNull(::configuredString)
        val pointer = (call ?: target ?: context.method).let {
            SmartPointerManager.getInstance(it.project).createSmartPsiElementPointer(it)
        }

        val qualifierField = (call?.methodExpression?.qualifierExpression as? PsiReferenceExpression)?.resolve() as? PsiField
        val customProducerAnnotation = qualifierField?.annotations?.firstOrNull {
            matchesConfiguredAnnotation(it, customMqProducerAnnotations)
        }
        val customProducerQueue = annotationString(
            customProducerAnnotation,
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
            isOnsProducer(owner, name) -> rocketMessageDestination(call)?.let { destination ->
                result += ResourceRef(
                    ResourceType.ROCKETMQ,
                    destination,
                    Operation.PRODUCE,
                    confidenceForDestination(destination),
                    "$owner.$name；从 ONS/RocketMQ Message 构造参数解析",
                    pointer
                )
            }
            isRocketConsumerSubscribe(owner, name) -> rocketSubscribeDestination(call)?.let { destination ->
                result += ResourceRef(
                    ResourceType.ROCKETMQ,
                    destination,
                    Operation.CONSUME,
                    confidenceForDestination(destination),
                    "$owner.$name",
                    pointer
                )
            }
            owner.contains("RocketMQ") || owner.endsWith(".RocketMQTemplate") -> {
                val expression = call?.argumentList?.expressions?.firstOrNull()
                val destination = expression?.let(::configuredString)
                val resourceName = destination ?: expression?.text?.let { "动态 RocketMQ destination: $it" }
                resourceName?.let {
                    result += ResourceRef(
                        ResourceType.ROCKETMQ,
                        it,
                        Operation.PRODUCE,
                        if (destination == null) Confidence.UNRESOLVED else Confidence.CONFIRMED,
                        "$owner.$name",
                        pointer
                    )
                }
            }
            owner.contains("Kafka") || name == "send" && context.sourceText.contains("Kafka") -> {
                val topic = kafkaTopic(target, call)
                topic?.let {
                    result += ResourceRef(
                        ResourceType.KAFKA, it, Operation.PRODUCE,
                        if (it.startsWith("\${")) Confidence.CONFIRMED else Confidence.INFERRED,
                        "$owner.$name", pointer
                    )
                }
            }
            customProducerQueue != null -> result += ResourceRef(
                ResourceType.RABBITMQ,
                customProducerQueue,
                Operation.PRODUCE,
                Confidence.CONFIRMED,
                "@${customProducerAnnotation?.nameReferenceElement?.referenceName} ${qualifierField?.name}",
                pointer
            )
            owner.contains("Rabbit") || name in setOf("convertAndSend", "send") && context.sourceText.contains("Rabbit") -> args.firstOrNull()?.let {
                val destination = if (name == "convertAndSend" && args.size >= 2) "${args[0]} -> ${args[1]}" else it
                result += ResourceRef(ResourceType.RABBITMQ, destination, Operation.PRODUCE, Confidence.INFERRED, "$owner.$name", pointer)
            }
        }
        listener(context.method, "KafkaListener")?.let { result += ResourceRef(ResourceType.KAFKA, it, Operation.CONSUME, Confidence.CONFIRMED, "@KafkaListener", pointer) }
        listener(context.method, "RabbitListener")?.let { result += ResourceRef(ResourceType.RABBITMQ, it, Operation.CONSUME, Confidence.CONFIRMED, "Rabbit consumer annotation", pointer) }
        customListener(context.method)?.let {
            result += ResourceRef(ResourceType.RABBITMQ, it, Operation.CONSUME, Confidence.CONFIRMED, "自定义 MQ 消费者注解", pointer)
        }
        rocketListener(context.method)?.let {
            result += ResourceRef(ResourceType.ROCKETMQ, it, Operation.CONSUME, Confidence.CONFIRMED, "@RocketMQMessageListener", pointer)
        }
        return result
    }

    private fun cacheAnnotations(element: PsiElement): List<String> = listOf("Cacheable", "CachePut", "CacheEvict").mapNotNull { name ->
        val annotation = (element as? com.intellij.psi.PsiMethod)?.let { annotation(it, name) }
        annotationString(annotation, "cacheNames", "value")
    }

    private fun listener(method: com.intellij.psi.PsiMethod, vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        annotationString(annotation(method, name), "topics", "topic", "queues", "queue", "value", "name")
    }

    private fun customListener(method: com.intellij.psi.PsiMethod): String? {
        val configured = method.annotations.firstOrNull { matchesConfiguredAnnotation(it, customMqConsumerAnnotations) }
            ?: method.containingClass?.annotations?.firstOrNull { matchesConfiguredAnnotation(it, customMqConsumerAnnotations) }
            ?: return null
        return annotationString(configured, "topics", "topic", "queues", "queue", "value", "name")
    }

    private fun rocketListener(method: com.intellij.psi.PsiMethod): String? {
        val methodListener = annotation(method, "RocketMQMessageListener")
        val listener = methodListener
            ?: annotation(method.containingClass, "RocketMQMessageListener")?.takeIf { method.name == "onMessage" }
            ?: return null
        val topic = annotationString(listener, "topic") ?: return null
        val selector = annotationString(listener, "selectorExpression")
        return if (selector.isNullOrBlank() || selector == "*") topic else "$topic:$selector"
    }

    private fun kafkaTopic(target: com.intellij.psi.PsiMethod?, call: com.intellij.psi.PsiMethodCallExpression?): String? {
        val callTopic = call?.argumentList?.expressions?.firstOrNull()?.let(::configuredString)
        if (callTopic != null && (target?.containingClass?.qualifiedName?.contains("KafkaTemplate") == true || callTopic.contains("topic", true))) {
            return callTopic
        }
        return target?.containingClass?.fields
            ?.firstOrNull { field -> field.name.contains("topic", true) && valuePlaceholder(field) != null }
            ?.let(::valuePlaceholder)
    }

    private fun configuredString(expression: PsiExpression): String? = configuredString(expression, mutableSetOf())

    private fun configuredString(expression: PsiExpression, visiting: MutableSet<PsiVariable>): String? {
        constantString(expression)?.let { return it }
        val variable = (expression as? PsiReferenceExpression)?.resolve() as? PsiVariable ?: return null
        if (!visiting.add(variable)) return null
        if (variable is PsiField) valuePlaceholder(variable)?.let { return it }
        return variable.initializer?.let { configuredString(it, visiting) }
    }

    private fun rocketMessageDestination(call: com.intellij.psi.PsiMethodCallExpression?): String? {
        val messageExpression = call?.argumentList?.expressions?.firstOrNull() ?: return null
        val newMessage = when (messageExpression) {
            is PsiNewExpression -> messageExpression
            is PsiReferenceExpression -> ((messageExpression.resolve() as? PsiVariable)?.initializer as? PsiNewExpression)
            else -> null
        } ?: return null
        val messageClass = newMessage.classReference?.qualifiedName.orEmpty()
        if (!messageClass.contains("Message")) return null
        val args = newMessage.argumentList?.expressions.orEmpty()
        val topic = args.getOrNull(0)?.let(::configuredString) ?: return dynamicDestination(args.getOrNull(0))
        val tag = args.getOrNull(1)?.let(::configuredString)
        return if (tag.isNullOrBlank() || tag == "*") topic else "$topic:$tag"
    }

    private fun rocketSubscribeDestination(call: com.intellij.psi.PsiMethodCallExpression?): String? {
        val args = call?.argumentList?.expressions.orEmpty()
        val topic = args.getOrNull(0)?.let(::configuredString) ?: return dynamicDestination(args.getOrNull(0))
        val tag = args.getOrNull(1)?.let(::configuredString)
        return if (tag.isNullOrBlank() || tag == "*") topic else "$topic:$tag"
    }

    private fun dynamicDestination(expression: PsiExpression?): String? =
        expression?.text?.let { "动态 RocketMQ destination: $it" }

    private fun isOnsProducer(owner: String, name: String): Boolean =
        name in setOf("send", "sendAsync", "sendOneway", "sendOneWay") &&
            (owner.startsWith("com.aliyun.openservices.ons.api.") || owner.startsWith("org.apache.rocketmq."))

    private fun isRocketConsumerSubscribe(owner: String, name: String): Boolean =
        name == "subscribe" &&
            (owner.startsWith("com.aliyun.openservices.ons.api.") || owner.startsWith("org.apache.rocketmq."))

    private fun confidenceForDestination(destination: String): Confidence =
        if (destination.startsWith("动态 RocketMQ destination:")) Confidence.UNRESOLVED else Confidence.CONFIRMED

    private fun matchesConfiguredAnnotation(annotation: PsiAnnotation, configured: List<String>): Boolean {
        val qualifiedName = annotation.qualifiedName.orEmpty()
        val shortName = annotation.nameReferenceElement?.referenceName.orEmpty()
        return configured.any { it == qualifiedName || it == shortName || qualifiedName.endsWith(".$it") }
    }

    private fun valuePlaceholder(field: PsiField): String? = annotationString(
        field.annotations.firstOrNull {
            it.qualifiedName?.endsWith(".Value") == true || it.nameReferenceElement?.referenceName == "Value"
        },
        "value"
    )

    private fun redisOperation(name: String): Operation = if (name.startsWith("get") || name.startsWith("has") || name.startsWith("opsFor")) Operation.READ else Operation.WRITE
}
