package com.liuxin.backendchain.analysis

import com.intellij.psi.PsiClass

internal data class ResolvedTable(val name: String, val explicit: Boolean)

internal object EntityTableResolver {
    fun resolve(entity: PsiClass): ResolvedTable? {
        if (GenericEntityResolver.isPlaceholder(entity)) return null
        annotationString(annotation(entity, "TableName"), "value")?.takeIf { it.isNotBlank() }
            ?.let { return ResolvedTable(it, true) }
        annotationString(annotation(entity, "Table"), "name")?.takeIf { it.isNotBlank() }
            ?.let { return ResolvedTable(it, true) }
        return entity.name?.let { ResolvedTable(camelToSnake(it.removeSuffix("Example")), false) }
    }

    fun inferredFromClassName(name: String): ResolvedTable? {
        val entityName = name.removeSuffix("Mapper").removeSuffix("Example")
        if (entityName in setOf("T", "E", "K", "V", "Object", "Any", "?")) return null
        return entityName.takeIf { it.isNotBlank() }?.let { ResolvedTable(camelToSnake(it), false) }
    }

    private fun camelToSnake(value: String): String = value
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .lowercase()
}
