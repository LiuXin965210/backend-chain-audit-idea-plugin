package com.liuxin.backendchain.settings

import com.intellij.openapi.components.*
import com.liuxin.backendchain.model.AnalysisOptions

@Service(Service.Level.PROJECT)
@State(name = "BackendChainAuditSettings", storages = [Storage("backend-chain-audit.xml")])
class ChainAuditSettings : PersistentStateComponent<ChainAuditSettings.State> {
    data class State(
        var maxDepth: Int = 30,
        var excludedPackages: String = "java.,javax.,jakarta.,kotlin.,org.springframework.",
        var followLocalMqConsumers: Boolean = true,
        var deduplicateResources: Boolean = false,
        var customHttpClientClasses: String = "jsh.mgt.lib.http.BasicHttpUtil",
        var localServiceDirectories: String = ""
    )

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    fun options() = AnalysisOptions(
        maxDepth = state.maxDepth.coerceIn(1, 100),
        excludedPackagePrefixes = state.excludedPackages.split(',', '\n', '\r').map(String::trim).filter(String::isNotEmpty),
        followLocalMqConsumers = state.followLocalMqConsumers,
        deduplicateResources = state.deduplicateResources,
        customHttpClientClassPrefixes = state.customHttpClientClasses.split(',', '\n', '\r')
            .map(String::trim).filter(String::isNotEmpty)
    )
}
