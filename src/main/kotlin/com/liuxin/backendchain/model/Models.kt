package com.liuxin.backendchain.model

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer

enum class Confidence(val displayName: String) {
    CONFIRMED("已确认"),
    INFERRED("推断"),
    UNRESOLVED("待确认")
}

enum class EntryType { METHOD, HTTP, MQ }

enum class ResourceType(val displayName: String) {
    MYSQL("MySQL"),
    REDIS("Redis"),
    RABBITMQ("RabbitMQ"),
    KAFKA("Kafka"),
    ROCKETMQ("RocketMQ"),
    EXTERNAL_HTTP("外围接口")
}

enum class Operation(val displayName: String) {
    READ("只读"), WRITE("写入"), PRODUCE("生产"), CONSUME("消费"), CALL("调用"), UNKNOWN("未知")
}

data class EntryPoint(
    val type: EntryType,
    val displayName: String,
    val httpMethod: String? = null,
    val pathOrTopic: String? = null
)

data class MethodRef(
    val key: String,
    val className: String,
    val methodName: String,
    val pointer: SmartPsiElementPointer<out PsiElement>?,
    val external: Boolean = false
) {
    val displayName: String get() = "$className.$methodName"
}

data class CallEdge(
    val caller: String,
    val callee: String,
    val confidence: Confidence,
    val reason: String,
    val pointer: SmartPsiElementPointer<out PsiElement>?
)

data class CallGraph(
    val root: String,
    val methods: Map<String, MethodRef>,
    val edges: List<CallEdge>
)

data class ResourceRef(
    val type: ResourceType,
    val name: String,
    val operation: Operation,
    val confidence: Confidence,
    val detail: String,
    val pointer: SmartPsiElementPointer<out PsiElement>?
)

data class AnalysisWarning(val message: String)

data class AnalysisStatus(
    val message: String,
    val running: Boolean,
    val error: Boolean = false
)

data class AnalysisResult(
    val entry: EntryPoint,
    val callGraph: CallGraph,
    val resources: List<ResourceRef>,
    val warnings: List<AnalysisWarning>
)

data class AnalysisOptions(
    val maxDepth: Int = 30,
    val excludedPackagePrefixes: List<String> = listOf("java.", "javax.", "jakarta.", "kotlin.", "org.springframework."),
    val followLocalMqConsumers: Boolean = true,
    val deduplicateResources: Boolean = false,
    val hideSimpleAccessors: Boolean = true,
    val customHttpClientClassPrefixes: List<String> = listOf("jsh.mgt.lib.http.BasicHttpUtil"),
    val customMqProducerAnnotations: List<String> = listOf("JshRabbitProducer"),
    val customMqConsumerAnnotations: List<String> = listOf("JshRabbitConsumer")
)
