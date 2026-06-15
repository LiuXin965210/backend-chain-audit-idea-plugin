package com.liuxin.backendchain.analysis

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter

internal object GenericEntityResolver {
    fun resolve(type: PsiClassType?, markerIndex: (PsiClass) -> Int?): PsiClass? {
        type ?: return null
        return resolve(type, markerIndex, mutableSetOf())
    }

    fun concreteClass(type: PsiType?): PsiClass? {
        val clazz = (type as? PsiClassType)?.resolve() ?: return null
        return clazz.takeUnless(::isPlaceholder)
    }

    fun isPlaceholder(clazz: PsiClass): Boolean =
        clazz is PsiTypeParameter || clazz.qualifiedName == "java.lang.Object" || clazz.name == "Object"

    private fun resolve(
        type: PsiClassType,
        markerIndex: (PsiClass) -> Int?,
        visiting: MutableSet<String>
    ): PsiClass? {
        if (!visiting.add(type.canonicalText)) return null
        val resolved = type.resolveGenerics()
        val clazz = resolved.element ?: return null
        markerIndex(clazz)?.let { index ->
            concreteClass(type.parameters.getOrNull(index))?.let { return it }
        }
        clazz.superTypes.forEach { superType ->
            val substituted = resolved.substitutor.substitute(superType) as? PsiClassType ?: return@forEach
            resolve(substituted, markerIndex, visiting)?.let { return it }
        }
        return null
    }
}
