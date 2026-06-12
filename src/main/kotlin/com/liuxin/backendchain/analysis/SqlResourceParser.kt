package com.liuxin.backendchain.analysis

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.liuxin.backendchain.model.*
import net.sf.jsqlparser.util.TablesNamesFinder

object SqlResourceParser {
    fun parse(sql: String, source: PsiElement?, detail: String): List<ResourceRef> {
        val compact = sql.replace(Regex("<[^>]+>"), " ").replace(Regex("#\\{[^}]+}|\\$\\{[^}]+}"), "NULL")
        if (compact.isBlank()) return emptyList()
        val pointer = source?.let { SmartPointerManager.getInstance(it.project).createSmartPsiElementPointer(it) }
        val dynamic = sql.contains("\${")
        return try {
            TablesNamesFinder.findTables(compact).map { table ->
                ResourceRef(
                    ResourceType.MYSQL,
                    table,
                    operation(compact),
                    if (dynamic) Confidence.UNRESOLVED else Confidence.CONFIRMED,
                    detail + if (dynamic) "；包含动态表名表达式" else "",
                    pointer
                )
            }
        } catch (_: Exception) {
            fallback(compact).map { table ->
                ResourceRef(ResourceType.MYSQL, table, operation(compact), Confidence.INFERRED, "$detail；SQL 解析失败，使用后备识别", pointer)
            }
        }
    }

    private fun operation(sql: String): Operation {
        val normalized = sql.trimStart().lowercase()
        return if (normalized.startsWith("select") || normalized.startsWith("with")) Operation.READ else Operation.WRITE
    }

    private fun fallback(sql: String): Set<String> {
        val result = linkedSetOf<String>()
        Regex("(?i)\\b(?:from|join|into|update)\\s+([a-zA-Z_][\\w.]*)").findAll(sql).forEach { result += it.groupValues[1] }
        return result
    }
}
