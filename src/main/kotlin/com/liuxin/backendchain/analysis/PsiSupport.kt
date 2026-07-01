package com.liuxin.backendchain.analysis

import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.MethodSignatureUtil
import com.intellij.psi.util.PsiTreeUtil

internal fun PsiMethod.methodKey(): String {
    val projectId = project.basePath ?: project.name
    val owner = containingClass?.qualifiedName ?: containingFile?.virtualFile?.path ?: "<unknown>"
    val signature = parameterList.parameters.joinToString(",") { it.type.canonicalText }
    return "$projectId::$owner#$name($signature)"
}

internal fun PsiMethod.ownerName(): String = containingClass?.qualifiedName ?: containingClass?.name ?: "<unknown>"

internal fun Project.auditName(): String = name.ifBlank { basePath ?: "未知工程" }

internal fun PsiMethod.isSimpleAccessor(): Boolean {
    val fieldName = when {
        name.startsWith("get") && name.length > 3 && parameterList.parametersCount == 0 && returnType != PsiTypes.voidType() ->
            name.substring(3).replaceFirstChar(Char::lowercase)
        name.startsWith("is") && name.length > 2 && parameterList.parametersCount == 0 &&
            returnType?.canonicalText in setOf("boolean", "java.lang.Boolean") ->
            name.substring(2).replaceFirstChar(Char::lowercase)
        name.startsWith("set") && name.length > 3 && parameterList.parametersCount == 1 ->
            name.substring(3).replaceFirstChar(Char::lowercase)
        else -> return false
    }
    val field = containingClass?.findFieldByName(fieldName, true) ?: return false
    val statements = body?.statements ?: return true
    if (statements.size != 1) return false
    val statementText = statements.single().text.replace(Regex("\\s+"), "")
    return when {
        name.startsWith("set") -> statementText.contains("$fieldName=")
        else -> statementText == "return$fieldName;" || statementText == "returnthis.$fieldName;"
    }
}

internal fun PsiMethod.isProjectSource(project: Project): Boolean {
    val file = containingFile?.virtualFile ?: return false
    return com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInSourceContent(file)
}

internal fun PsiClass.isProjectSource(project: Project): Boolean {
    val file = containingFile?.virtualFile ?: return false
    return com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInSourceContent(file)
}

internal fun findMethodCalls(method: PsiMethod): Collection<PsiMethodCallExpression> =
    PsiTreeUtil.findChildrenOfType(method.body, PsiMethodCallExpression::class.java)

internal fun findMethodReferences(method: PsiMethod): Collection<PsiMethodReferenceExpression> =
    PsiTreeUtil.findChildrenOfType(method.body, PsiMethodReferenceExpression::class.java)

internal fun resolveImplementations(
    project: Project,
    method: PsiMethod,
    call: PsiMethodCallExpression? = null,
    cache: MutableMap<String, List<PsiMethod>>? = null,
    includeConcreteOverrides: Boolean = true
): List<PsiMethod> {
    ProgressManager.checkCanceled()
    val qualifier = beanQualifier(call)
    val cacheKey = method.methodKey() + "|" + qualifier.orEmpty() + "|$includeConcreteOverrides"
    return cache?.getOrPut(cacheKey) { resolveImplementations(project, method, qualifier, includeConcreteOverrides) }
        ?: resolveImplementations(project, method, qualifier, includeConcreteOverrides)
}

private fun resolveImplementations(
    project: Project,
    method: PsiMethod,
    qualifier: String?,
    includeConcreteOverrides: Boolean
): List<PsiMethod> {
    ProgressManager.checkCanceled()
    val owner = method.containingClass ?: return emptyList()
    if (!owner.isInterface && !method.hasModifierProperty(PsiModifier.ABSTRACT)) {
        if (!includeConcreteOverrides || !isVirtual(method)) return listOf(method)
        val overrides = ClassInheritorsSearch.search(owner, GlobalSearchScope.projectScope(project), true)
            .findAll()
            .flatMap { inheritor ->
                ProgressManager.checkCanceled()
                inheritor.findMethodsByName(method.name, false)
                    .filter { it.hierarchicalMethodSignature.superSignatures.any { signature -> signature.method == method } }
            }
            .distinctBy { it.methodKey() }
        return listOf(method) + overrides
    }
    val implementations = ClassInheritorsSearch.search(owner, GlobalSearchScope.projectScope(project), true)
        .findAll()
        .mapNotNull { inheritor ->
            ProgressManager.checkCanceled()
            MethodSignatureUtil.findMethodBySuperMethod(inheritor, method, false)
        }
        .filter { !it.hasModifierProperty(PsiModifier.ABSTRACT) }
        .distinctBy { it.methodKey() }
    if (implementations.size <= 1) return implementations

    if (qualifier != null) {
        implementations.filter { beanName(it.containingClass) == qualifier }.takeIf { it.isNotEmpty() }?.let { return it }
    }
    implementations.filter { annotation(it.containingClass, "Primary") != null }.takeIf { it.size == 1 }?.let { return it }
    return implementations
}

private fun beanQualifier(call: PsiMethodCallExpression?): String? {
    val reference = call?.methodExpression?.qualifierExpression as? PsiReferenceExpression
    return when (val injectionPoint = reference?.resolve()) {
        is PsiField -> injectionQualifier(injectionPoint.annotations)
        is PsiParameter -> injectionQualifier(injectionPoint.annotations)
        else -> null
    }
}

private fun injectionQualifier(annotations: Array<PsiAnnotation>): String? =
    annotationString(annotations.firstOrNull { it.nameReferenceElement?.referenceName == "Qualifier" }, "value")
        ?: annotationString(annotations.firstOrNull { it.nameReferenceElement?.referenceName == "Resource" }, "name", "value")

private fun isVirtual(method: PsiMethod): Boolean =
    !method.isConstructor &&
        !method.hasModifierProperty(PsiModifier.PRIVATE) &&
        !method.hasModifierProperty(PsiModifier.STATIC) &&
        !method.hasModifierProperty(PsiModifier.FINAL) &&
        method.containingClass?.hasModifierProperty(PsiModifier.FINAL) != true

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
