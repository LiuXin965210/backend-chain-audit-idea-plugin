package com.liuxin.backendchain.analysis

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.SmartPointerManager
import com.liuxin.backendchain.model.Confidence
import com.liuxin.backendchain.model.Operation
import com.liuxin.backendchain.model.ResourceRef
import com.liuxin.backendchain.model.ResourceType

class MyBatisPlusExtractor : ResourceExtractor {
    override fun supports(context: CallContext): Boolean {
        val target = context.resolvedMethod ?: return false
        if (operation(target.name) == null) return false
        val qualifierClass = (context.call?.methodExpression?.qualifierExpression?.type as? PsiClassType)?.resolve()
        return isMyBatisPlusType(target.containingClass) || isMyBatisPlusType(qualifierClass)
    }

    override fun extract(context: CallContext): List<ResourceRef> {
        val target = context.resolvedMethod ?: return emptyList()
        val operation = operation(target.name) ?: return emptyList()
        val entity = resolveEntity(context, target) ?: return emptyList()
        val table = EntityTableResolver.resolve(entity) ?: return emptyList()
        val source = context.call ?: target
        return listOf(
            ResourceRef(
                ResourceType.MYSQL,
                table.name,
                operation,
                if (table.explicit) Confidence.CONFIRMED else Confidence.INFERRED,
                "MyBatis-Plus ${target.ownerName()}.${target.name}；实体 ${entity.qualifiedName ?: entity.name}" +
                    if (table.explicit) "；@TableName/@Table" else "；按默认命名规则推断",
                SmartPointerManager.getInstance(source.project).createSmartPsiElementPointer(source)
            )
        )
    }

    private fun operation(name: String): Operation? = when {
        listOf("select", "get", "list", "page", "count").any(name::startsWith) -> Operation.READ
        listOf("insert", "update", "delete", "remove", "save").any(name::startsWith) -> Operation.WRITE
        else -> null
    }

    private fun resolveEntity(context: CallContext, target: PsiMethod): PsiClass? {
        context.call?.argumentList?.expressions.orEmpty().forEach { argument ->
            if (argument is PsiClassObjectAccessExpression) {
                argument.operand.type.resolveClass()?.let { return it }
            }
            entityFromWrapper(argument.type)?.let { return it }
        }
        val qualifierType = context.call?.methodExpression?.qualifierExpression?.type as? PsiClassType
        entityFromHierarchy(qualifierType)?.let { return it }
        target.containingClass?.let { entityFromClassHierarchy(it)?.let { entity -> return entity } }
        context.call?.argumentList?.expressions.orEmpty().forEach { argument ->
            argument.type.resolveClass()?.takeUnless(::isWrapper)?.let { return it }
        }
        return null
    }

    private fun entityFromHierarchy(type: PsiClassType?): PsiClass? {
        return GenericEntityResolver.resolve(type, ::markerEntityIndex)
    }

    private fun entityFromClassHierarchy(clazz: PsiClass): PsiClass? =
        clazz.superTypes.firstNotNullOfOrNull(::entityFromHierarchy)

    private fun markerEntityIndex(clazz: PsiClass): Int? {
        val name = clazz.qualifiedName ?: clazz.name.orEmpty()
        return when {
            name.endsWith(".BaseMapper") || name == "BaseMapper" -> 0
            name.endsWith(".IService") || name == "IService" -> 0
            name.endsWith(".ServiceImpl") || name == "ServiceImpl" -> 1
            else -> null
        }
    }

    private fun entityFromWrapper(type: PsiType?): PsiClass? {
        val classType = type as? PsiClassType ?: return null
        val clazz = classType.resolve() ?: return null
        if (!isWrapper(clazz)) return null
        return classType.parameters.firstOrNull()?.resolveClass()
    }

    private fun isMyBatisPlusType(clazz: PsiClass?): Boolean {
        clazz ?: return false
        val name = clazz.qualifiedName ?: clazz.name.orEmpty()
        if (name.endsWith(".Db") || name == "Db") return true
        if (name.endsWith(".BaseMapper") || name == "BaseMapper" ||
            name.endsWith(".IService") || name == "IService" ||
            name.endsWith(".ServiceImpl") || name == "ServiceImpl") return true
        return clazz.superTypes.any { isMyBatisPlusType(it.resolve()) }
    }

    private fun isWrapper(clazz: PsiClass): Boolean {
        val name = clazz.qualifiedName ?: clazz.name.orEmpty()
        return name.contains("QueryWrapper") || name.contains("UpdateWrapper") || name.endsWith(".Wrapper")
    }

    private fun PsiType?.resolveClass(): PsiClass? = (this as? PsiClassType)?.resolve()
}
