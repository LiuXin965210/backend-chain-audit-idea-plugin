package com.liuxin.backendchain.analysis

import com.liuxin.backendchain.model.ResourceRef
import com.liuxin.backendchain.model.ResourceType

object ExternalHttpResourceFilter {
    fun classify(resources: List<ResourceRef>, patterns: List<String>): List<ResourceRef> {
        val rules = patterns.map(String::trim).filter(String::isNotEmpty)
        if (rules.isEmpty()) return resources
        return resources.map { resource ->
            if (resource.type == ResourceType.EXTERNAL_HTTP && isExcluded(resource.name, rules)) {
                resource.copy(operation = com.liuxin.backendchain.model.Operation.READ)
            } else {
                resource
            }
        }
    }

    fun filter(resources: List<ResourceRef>, patterns: List<String>): List<ResourceRef> {
        val rules = patterns.map(String::trim).filter(String::isNotEmpty)
        if (rules.isEmpty()) return resources
        return resources.filterNot { resource ->
            resource.type == ResourceType.EXTERNAL_HTTP && isExcluded(resource.name, rules)
        }
    }

    fun isExcluded(resourceName: String, patterns: List<String>): Boolean =
        patterns.map(String::trim).filter(String::isNotEmpty).any { matches(resourceName, it) }

    private fun matches(resourceName: String, rule: String): Boolean {
        if (rule.startsWith("regex:", ignoreCase = true)) {
            val pattern = rule.substringAfter(':').trim()
            return runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(resourceName) }
                .getOrDefault(false)
        }
        return resourceName.contains(rule, ignoreCase = true)
    }
}
