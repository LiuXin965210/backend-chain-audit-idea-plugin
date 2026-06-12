package com.liuxin.backendchain.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.liuxin.backendchain.model.ResourceRef

class MyBatisExtractor : ResourceExtractor {
    override fun extract(context: CallContext): List<ResourceRef> {
        val target = context.resolvedMethod ?: return emptyList()
        val namespace = target.containingClass?.qualifiedName ?: return emptyList()
        val statementId = target.name
        val project = target.project
        val result = mutableListOf<ResourceRef>()
        FilenameIndex.getAllFilesByExt(project, "xml", GlobalSearchScope.projectScope(project)).forEach { file ->
            ProgressManager.checkCanceled()
            val xml = PsiManager.getInstance(project).findFile(file) as? XmlFile ?: return@forEach
            val mapper = xml.rootTag?.takeIf { it.name == "mapper" && it.getAttributeValue("namespace") == namespace } ?: return@forEach
            mapper.subTags.filter { it.getAttributeValue("id") == statementId && it.name in setOf("select", "insert", "update", "delete") }
                .forEach { statement ->
                    val sql = expand(statement, mapper, mutableSetOf())
                    result += SqlResourceParser.parse(sql, statement, "MyBatis $namespace#$statementId")
                }
        }
        return result
    }

    private fun expand(tag: XmlTag, mapper: XmlTag, visiting: MutableSet<String>): String {
        val output = StringBuilder()
        tag.value.children.forEach { child ->
            if (child is XmlTag && child.name == "include") {
                val ref = child.getAttributeValue("refid")?.substringAfterLast('.') ?: return@forEach
                if (visiting.add(ref)) {
                    mapper.findSubTags("sql").firstOrNull { it.getAttributeValue("id") == ref }
                        ?.let { output.append(' ').append(expand(it, mapper, visiting)) }
                    visiting.remove(ref)
                }
            } else {
                output.append(' ').append(child.text)
            }
        }
        return output.toString()
    }
}
