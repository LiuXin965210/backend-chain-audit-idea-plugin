package com.liuxin.backendchain.analysis

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.SmartPointerManager
import com.liuxin.backendchain.model.*

class JpaExtractor : ResourceExtractor {
    override fun extract(context: CallContext): List<ResourceRef> {
        val target = context.resolvedMethod ?: return emptyList()
        val query = annotation(target, "Query")
        annotationString(query, "value")?.let { sql ->
            val native = query?.findAttributeValue("nativeQuery")?.text == "true"
            if (native) return SqlResourceParser.parse(sql, target, "JPA native query ${target.ownerName()}.${target.name}")
        }

        val repository = context.call?.methodExpression?.qualifierExpression?.type
            ?.let { (it as? com.intellij.psi.PsiClassType)?.resolve() }
            ?.takeIf { isRepository(it) }
            ?: target.containingClass?.takeIf { isRepository(it) }
            ?: return emptyList()
        val entity = resolveEntity(repository, target) ?: return emptyList()
        val table = EntityTableResolver.resolve(entity) ?: return emptyList()
        val operation = when {
            target.name.startsWith("save") || target.name.startsWith("delete") || target.name.startsWith("remove") -> Operation.WRITE
            else -> Operation.READ
        }
        return listOf(
            ResourceRef(
                ResourceType.MYSQL,
                table.name,
                operation,
                if (table.explicit) Confidence.CONFIRMED else Confidence.INFERRED,
                "JPA Repository ${repository.qualifiedName}.${target.name}" +
                    if (table.explicit) "；实体注解表名" else "；按默认命名规则推断",
                SmartPointerManager.getInstance(target.project).createSmartPsiElementPointer(target)
            )
        )
    }

    private fun isRepository(clazz: PsiClass): Boolean =
        clazz.supers.any { it.qualifiedName?.contains("Repository") == true } || clazz.name?.endsWith("Repository") == true

    private fun resolveEntity(repository: PsiClass, method: PsiMethod): PsiClass? {
        repository.extendsListTypes.forEach { type -> type.parameters.firstOrNull()?.resolveClass()?.let { return it } }
        method.parameterList.parameters.forEach { parameter -> parameter.type.resolveClass()?.takeIf(::isEntity)?.let { return it } }
        return method.returnType.resolveClass()?.takeIf(::isEntity)
    }

    private fun isEntity(clazz: PsiClass): Boolean = annotation(clazz, "Entity") != null || annotation(clazz, "Table") != null

    private fun PsiType?.resolveClass(): PsiClass? = (this as? com.intellij.psi.PsiClassType)?.resolve()
}
