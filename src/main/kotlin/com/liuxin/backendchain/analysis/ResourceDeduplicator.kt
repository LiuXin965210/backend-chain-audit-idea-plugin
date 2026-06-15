package com.liuxin.backendchain.analysis

import com.liuxin.backendchain.model.ResourceRef

internal object ResourceDeduplicator {
    fun normalize(resources: List<ResourceRef>, deduplicate: Boolean): List<ResourceRef> {
        val distinctEvidence = resources.distinctBy { evidenceKey(it) }
        if (!deduplicate) return distinctEvidence
        return distinctEvidence.groupBy { resourceKey(it) }.values.map(::merge)
    }

    private fun merge(resources: List<ResourceRef>): ResourceRef {
        val first = resources.first()
        return first.copy(
            confidence = resources.maxBy { it.confidence.ordinal }.confidence,
            detail = resources.map { it.detail }.distinct().joinToString("\n---\n"),
            pointer = resources.firstNotNullOfOrNull { it.pointer }
        )
    }

    private fun resourceKey(resource: ResourceRef) =
        "${resource.type}:${resource.name}:${resource.operation}"

    private fun evidenceKey(resource: ResourceRef) =
        "${resourceKey(resource)}:${resource.confidence}:${resource.detail}"
}
