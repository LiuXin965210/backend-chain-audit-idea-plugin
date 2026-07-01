package com.liuxin.backendchain.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.application.ApplicationManager
import com.liuxin.backendchain.model.AnalysisOptions

@Service(Service.Level.APP)
@State(name = "BackendChainAuditApplicationSettings", storages = [Storage("backend-chain-audit-global.xml")])
class ChainAuditApplicationSettings : PersistentStateComponent<ChainAuditSettings.State> {
    private var state = ChainAuditSettings.State()
    override fun getState(): ChainAuditSettings.State = state
    override fun loadState(state: ChainAuditSettings.State) { this.state = state }
}

@Service(Service.Level.PROJECT)
@State(name = "BackendChainAuditSettings", storages = [Storage("backend-chain-audit.xml")])
class ChainAuditSettings : PersistentStateComponent<ChainAuditSettings.State> {
    data class State(
        var useApplicationSettings: Boolean = true,
        var maxDepth: Int = 30,
        var excludedPackages: String = "java.,javax.,jakarta.,kotlin.,org.springframework.",
        var onlyProjectSource: Boolean = false,
        var followLocalMqConsumers: Boolean = true,
        var deduplicateResources: Boolean = false,
        var hideSimpleAccessors: Boolean = true,
        var followConcreteMethodOverrides: Boolean = false,
        var followCrossProjectFeign: Boolean = false,
        var crossProjectFeignMappings: String = "",
        var maxCrossProjectDepth: Int = 3,
        var batchRowTimeoutSeconds: Int = 0,
        var excludedExternalHttpPathPatterns: String = "/get-\n/list-\n/search-",
        var customHttpClientClasses: String = "jsh.mgt.lib.http.BasicHttpUtil",
        var customMqProducerAnnotations: String = "JshRabbitProducer",
        var customMqConsumerAnnotations: String = "JshRabbitConsumer",
        var customMqProducerClasses: String = "jsh.mgt.lib.rocketmq.producer.JshRocketMqProducer",
        var customMqConsumerInterfaces: String = "jsh.mgt.lib.rocketmq.consumer.JshRocketMqListener",
        var localServiceDirectories: String = ""
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    fun effectiveState(): State =
        if (state.useApplicationSettings) ApplicationManager.getApplication().service<ChainAuditApplicationSettings>().state else state

    fun options(): AnalysisOptions = options(effectiveState())

    companion object {
        fun options(state: State) = AnalysisOptions(
            maxDepth = state.maxDepth.coerceIn(1, 100),
            excludedPackagePrefixes = state.excludedPackages.split(',', '\n', '\r').map(String::trim).filter(String::isNotEmpty),
            onlyProjectSource = state.onlyProjectSource,
            followLocalMqConsumers = state.followLocalMqConsumers,
            deduplicateResources = state.deduplicateResources,
            hideSimpleAccessors = state.hideSimpleAccessors,
            followConcreteMethodOverrides = state.followConcreteMethodOverrides,
            followCrossProjectFeign = state.followCrossProjectFeign,
            crossProjectFeignMappings = parseMappings(state.crossProjectFeignMappings),
            maxCrossProjectDepth = state.maxCrossProjectDepth.coerceIn(1, 10),
            batchRowTimeoutSeconds = state.batchRowTimeoutSeconds.coerceIn(0, 86_400),
            excludedExternalHttpPathPatterns = state.excludedExternalHttpPathPatterns.split('\n', '\r')
                .map(String::trim).filter(String::isNotEmpty),
            customHttpClientClassPrefixes = state.customHttpClientClasses.split(',', '\n', '\r')
                .map(String::trim).filter(String::isNotEmpty),
            customMqProducerAnnotations = state.customMqProducerAnnotations.split(',', '\n', '\r')
                .map(String::trim).filter(String::isNotEmpty),
            customMqConsumerAnnotations = state.customMqConsumerAnnotations.split(',', '\n', '\r')
                .map(String::trim).filter(String::isNotEmpty),
            customMqProducerClasses = state.customMqProducerClasses.split(',', '\n', '\r')
                .map(String::trim).filter(String::isNotEmpty),
            customMqConsumerInterfaces = state.customMqConsumerInterfaces.split(',', '\n', '\r')
                .map(String::trim).filter(String::isNotEmpty)
        )

        private fun parseMappings(value: String): Map<String, String> =
            value.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val index = line.indexOf('=')
                    if (index <= 0 || index == line.lastIndex) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
                }
                .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
                .toMap()
    }
}
