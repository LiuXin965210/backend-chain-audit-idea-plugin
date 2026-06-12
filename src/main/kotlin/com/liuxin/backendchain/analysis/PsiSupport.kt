package com.liuxin.backendchain.analysis

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiTreeUtil

internal fun PsiMethod.methodKey(): String {
    val owner = containingClass?.qualifiedName ?: containingFile?.virtualFile?.path ?: "<unknown>"
    val signature = parameterList.parameters.joinToString(",") { it.type.canonicalText }
    return "$owner#$name($signature)"
}

internal fun PsiMethod.ownerName(): String = containingClass?.qualifiedName ?: containingClass?.name ?: "<unknown>"

internal fun PsiMethod.isProjectSource(project: Project): Boolean {
    val file = containingFile?.virtualFile ?: return false
    return com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInSourceContent(file)
}

internal fun findMethodCalls(method: PsiMethod): Collection<PsiMethodCallExpression> =
    PsiTreeUtil.findChildrenOfType(method.body, PsiMethodCallExpression::class.java)

internal fun resolveImplementations(project: Project, method: PsiMethod, call: PsiMethodCallExpression? = null): List<PsiMethod> {
    val owner = method.containingClass ?: return emptyList()
    if (!owner.isInterface && !method.hasModifierProperty(PsiModifier.ABSTRACT)) return listOf(method)
    val implementations = ClassInheritorsSearch.search(owner, GlobalSearchScope.projectScope(project), true)
        .findAll()
        .flatMap { inheritor ->
            inheritor.findMethodsBySignature(method, true).filter { !it.hasModifierProperty(PsiModifier.ABSTRACT) }
        }
        .distinctBy { it.methodKey() }
    if (implementations.size <= 1) return implementations

    val reference = call?.methodExpression?.qualifierExpression as? PsiReferenceExpression
    val injectionPoint = reference?.resolve()
    val qualifier = when (injectionPoint) {
        is PsiField -> annotationString(injectionPoint.annotations.firstOrNull { it.nameReferenceElement?.referenceName == "Qualifier" }, "value")
        is PsiParameter -> annotationString(injectionPoint.annotations.firstOrNull { it.nameReferenceElement?.referenceName == "Qualifier" }, "value")
        else -> null
    }
    if (qualifier != null) {
        implementations.filter { beanName(it.containingClass) == qualifier }.takeIf { it.isNotEmpty() }?.let { return it }
    }
    implementations.filter { annotation(it.containingClass, "Primary") != null }.takeIf { it.size == 1 }?.let { return it }
    return implementations
}

private fun beanName(clazz: PsiClass?): String? {
    clazz ?: return null
    val declared = listOf("Service", "Component", "Repository").firstNotNullOfOrNull { name ->
        annotationString(annotation(clazz, name), "value")
    }
    return declared ?: clazz.name?.replaceFirstChar { it.lowercase() }
}

internal fun annotation(method: PsiMethod, shortName: String): PsiAnnotation? =
    method.annotations.firstOrNull { it.qualifiedName?.endsWith(".$shortName") == true || it.nameReferenceElement?.referenceName == shortName }

internal fun annotation(clazz: PsiClass?, shortName: String): PsiAnnotation? =
    clazz?.annotations?.firstOrNull { it.qualifiedName?.endsWith(".$shortName") == true || it.nameReferenceElement?.referenceName == shortName }

internal fun annotationString(annotation: PsiAnnotation?, vararg names: String): String? {
    if (annotation == null) return null
    for (name in names) {
        val value = annotation.findAttributeValue(name) ?: continue
        constantString(value)?.let { return it }
    }
    return null
}

internal fun constantString(value: PsiAnnotationMemberValue?): String? = when (value) {
    is PsiLiteralExpression -> value.value as? String
    is PsiReferenceExpression -> (value.resolve() as? PsiField)?.computeConstantValue() as? String
    is PsiArrayInitializerMemberValue -> value.initializers.firstNotNullOfOrNull(::constantString)
    is PsiExpression -> JavaPsiFacade.getInstance(value.project).constantEvaluationHelper.computeConstantExpression(value) as? String
    else -> null
}

internal fun constantString(expression: PsiExpression?): String? = expression?.let {
    JavaPsiFacade.getInstance(it.project).constantEvaluationHelper.computeConstantExpression(it) as? String
}

internal fun normalizePath(vararg parts: String?): String {
    val joined = parts.filterNotNull().filter { it.isNotBlank() }
        .joinToString("/") { it.trim().trim('/') }
        .replace(Regex("/+"), "/")
    return if (joined.isBlank()) "/" else "/$joined"
}
