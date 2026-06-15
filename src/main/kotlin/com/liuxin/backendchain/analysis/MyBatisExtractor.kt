package com.liuxin.backendchain.analysis

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiClassType
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.liuxin.backendchain.model.ResourceRef
import com.liuxin.backendchain.model.Confidence
import com.liuxin.backendchain.model.Operation
import com.liuxin.backendchain.model.ResourceType

class MyBatisExtractor : ResourceExtractor {
    private val mappersByNamespace = mutableMapOf<String, List<XmlTag>>()

    override fun supports(context: CallContext): Boolean {
        val mapperClass = context.resolvedMethod?.containingClass ?: return false
        return mapperClass.name?.endsWith("Mapper") == true || annotation(mapperClass, "Mapper") != null
    }

    override fun extract(context: CallContext): List<ResourceRef> {
        val target = context.resolvedMethod ?: return emptyList()
        val namespace = target.containingClass?.qualifiedName ?: return emptyList()
        val statementId = target.name
        val result = mutableListOf<ResourceRef>()
        mapperTags(target.project, namespace).forEach { mapper ->
            mapper.subTags.filter { it.getAttributeValue("id") == statementId && it.name in setOf("select", "insert", "update", "delete") }
                .forEach { statement ->
                    val sql = expand(statement, mapper, mutableSetOf())
                    result += SqlResourceParser.parse(sql, statement, "MyBatis $namespace#$statementId")
                }
        }
        if (result.isEmpty() && isGeneratedMapperMethod(target)) {
            val qualifierType = context.call?.methodExpression?.qualifierExpression?.type as? PsiClassType
            val entity = GenericEntityResolver.resolve(qualifierType, ::tkMapperEntityIndex)
                ?: target.parameterList.parameters
                    .mapNotNull { GenericEntityResolver.concreteClass(it.type) }
                    .firstOrNull { it.name?.endsWith("Example") != true }
            val table = entity?.let(EntityTableResolver::resolve)
                ?: target.containingClass
                    ?.takeIf { it.isProjectSource(target.project) }
                    ?.name
                    ?.let(EntityTableResolver::inferredFromClassName)
            if (table != null) {
                result += ResourceRef(
                    ResourceType.MYSQL,
                    table.name,
                    mbgOperation(statementId),
                    Confidence.INFERRED,
                    "MyBatis 通用 Mapper $namespace#$statementId；XML/Provider 未解析出表，按调用点泛型实体推断",
                    SmartPointerManager.getInstance(target.project).createSmartPsiElementPointer(target)
                )
            }
        }
        return result
    }

    private fun isGeneratedMapperMethod(method: com.intellij.psi.PsiMethod): Boolean {
        val name = method.name
        val owner = method.containingClass?.qualifiedName.orEmpty()
        return owner.startsWith("tk.mybatis.mapper.") ||
            name.contains("ByExample") ||
            name in setOf("insertSelective", "updateByPrimaryKeySelective")
    }

    private fun tkMapperEntityIndex(clazz: com.intellij.psi.PsiClass): Int? =
        if (clazz.qualifiedName?.startsWith("tk.mybatis.mapper.") == true && clazz.typeParameters.isNotEmpty()) 0 else null

    private fun mbgOperation(name: String): Operation =
        if (name.startsWith("select") || name.startsWith("count") || name.startsWith("exists")) Operation.READ else Operation.WRITE

    private fun mapperTags(project: com.intellij.openapi.project.Project, namespace: String): List<XmlTag> =
        mappersByNamespace.getOrPut(namespace) {
            FilenameIndex.getAllFilesByExt(project, "xml", GlobalSearchScope.projectScope(project)).mapNotNull { file ->
                ProgressManager.checkCanceled()
                val xml = PsiManager.getInstance(project).findFile(file) as? XmlFile ?: return@mapNotNull null
                xml.rootTag?.takeIf { it.name == "mapper" && it.getAttributeValue("namespace") == namespace }
            }
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
