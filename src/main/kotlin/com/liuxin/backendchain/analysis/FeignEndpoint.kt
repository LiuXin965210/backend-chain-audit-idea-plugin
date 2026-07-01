package com.liuxin.backendchain.analysis

import com.intellij.psi.PsiAnnotation

internal data class FeignEndpoint(
    val service: String,
    val serviceKey: String,
    val httpMethod: String,
    val path: String,
    val displayPath: String,
    val source: String
) {
    val displayName: String get() = "$service $httpMethod $displayPath"
}

internal object FeignEndpointParser {
    private val mappings = listOf("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping")

    fun parse(context: CallContext): FeignEndpoint? {
        val target = context.resolvedMethod ?: return null
        val clazz = target.containingClass ?: return null
        val feign = feignAnnotation(clazz) ?: return null
        val mapping = mappings.firstNotNullOfOrNull { name -> annotation(target, name)?.let { name to it } }
            ?: return null
        val service = if (isStandardFeign(feign)) {
            declaredAnnotationString(feign, "name", "value", "url")
        } else {
            declaredAnnotationString(feign, "url", "name", "value")
        } ?: "待确认服务"
        val serviceKey = service.substringBefore('/').trim()
        val servicePath = service.substringAfter('/', "").trim('/')
        val base = annotationString(feign, "path")
        val classPath = annotationString(annotation(clazz, "RequestMapping"), "path", "value")
        val methodPath = annotationString(mapping.second, "path", "value")
        val displayPath = normalizePath(base, classPath, methodPath)
        return FeignEndpoint(
            service,
            serviceKey,
            mappingMethod(mapping.first, mapping.second),
            normalizePath(servicePath, base, classPath, methodPath),
            displayPath,
            "${annotationShortName(feign)} ${clazz.qualifiedName}.${target.name}"
        )
    }

    private fun mappingMethod(name: String, value: PsiAnnotation): String = when (name) {
        "GetMapping" -> "GET"
        "PostMapping" -> "POST"
        "PutMapping" -> "PUT"
        "DeleteMapping" -> "DELETE"
        "PatchMapping" -> "PATCH"
        else -> Regex("RequestMethod\\.([A-Z]+)").find(value.text)?.groupValues?.get(1) ?: "未声明"
    }

    private fun declaredAnnotationString(annotation: PsiAnnotation, vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            annotation.findDeclaredAttributeValue(name)?.let(::constantString)?.takeIf(String::isNotBlank)
        }

    private fun feignAnnotation(clazz: com.intellij.psi.PsiClass): PsiAnnotation? =
        clazz.annotations.firstOrNull { annotation ->
            annotationShortName(annotation)?.let { it == "FeignClient" || it.endsWith("FeignClient") } == true
        }

    private fun isStandardFeign(annotation: PsiAnnotation): Boolean =
        annotationShortName(annotation) == "FeignClient"

    private fun annotationShortName(annotation: PsiAnnotation): String? =
        annotation.nameReferenceElement?.referenceName ?: annotation.qualifiedName?.substringAfterLast('.')
}
