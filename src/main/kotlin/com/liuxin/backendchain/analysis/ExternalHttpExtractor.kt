package com.liuxin.backendchain.analysis

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.SmartPointerManager
import com.liuxin.backendchain.model.*

class ExternalHttpExtractor(
    private val customHttpClientClassPrefixes: List<String> = listOf("jsh.mgt.lib.http.BasicHttpUtil")
) : ResourceExtractor {
    private val mappings = listOf("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping")

    override fun extract(context: CallContext): List<ResourceRef> {
        val target = context.resolvedMethod ?: return emptyList()
        val clazz = target.containingClass ?: return emptyList()
        val feign = annotation(clazz, "FeignClient")
        if (feign != null) {
            val mapping = mappings.firstNotNullOfOrNull { name -> annotation(target, name)?.let { name to it } }
                ?: return emptyList()
            val service = annotationString(feign, "name", "value", "url") ?: "待确认服务"
            val base = annotationString(feign, "path")
            val classPath = annotationString(annotation(clazz, "RequestMapping"), "path", "value")
            val methodPath = annotationString(mapping.second, "path", "value")
            val httpMethod = mappingMethod(mapping.first, mapping.second)
            val path = normalizePath(base, classPath, methodPath)
            val confidence = if (service.startsWith("\${") || methodPath == null) Confidence.UNRESOLVED else Confidence.CONFIRMED
            return listOf(resource(target, "$service $httpMethod $path", confidence, "Feign ${clazz.qualifiedName}.${target.name}"))
        }

        val owner = clazz.qualifiedName.orEmpty()
        if (isHttpClient(owner)) {
            val url = httpUrl(context.call?.argumentList?.expressions?.firstOrNull()) ?: "待确认URL"
            val httpMethod = httpMethod(target.name)
            return listOf(
                resource(
                    target,
                    "$httpMethod $url",
                    if (url == "待确认URL") Confidence.UNRESOLVED else Confidence.INFERRED,
                    "HTTP client $owner.${target.name}"
                )
            )
        }
        return emptyList()
    }

    private fun isHttpClient(owner: String): Boolean =
        listOf("RestTemplate", "WebClient", "OkHttp").any(owner::contains) ||
            customHttpClientClassPrefixes.any { owner.startsWith(it) || owner == it }

    private fun httpMethod(methodName: String): String = when {
        methodName.startsWith("get", true) -> "GET"
        methodName.startsWith("post", true) -> "POST"
        methodName.startsWith("put", true) -> "PUT"
        methodName.startsWith("delete", true) -> "DELETE"
        methodName.startsWith("patch", true) -> "PATCH"
        else -> "未声明"
    }

    private fun httpUrl(expression: PsiExpression?): String? = when (expression) {
        null -> null
        is PsiLiteralExpression -> expression.value as? String
        is PsiReferenceExpression -> {
            constantString(expression) ?: (expression.resolve() as? PsiField)?.let { field ->
                annotationString(field.annotations.firstOrNull {
                    it.qualifiedName?.endsWith(".Value") == true || it.nameReferenceElement?.referenceName == "Value"
                }, "value")
            }
        }
        is PsiPolyadicExpression -> expression.operands.mapNotNull(::httpUrl).joinToString("").ifBlank { null }
        else -> constantString(expression)
    }

    private fun mappingMethod(name: String, value: PsiAnnotation): String = when (name) {
        "GetMapping" -> "GET"; "PostMapping" -> "POST"; "PutMapping" -> "PUT"; "DeleteMapping" -> "DELETE"; "PatchMapping" -> "PATCH"
        else -> Regex("RequestMethod\\.([A-Z]+)").find(value.text)?.groupValues?.get(1) ?: "未声明"
    }

    private fun resource(target: com.intellij.psi.PsiMethod, name: String, confidence: Confidence, detail: String) = ResourceRef(
        ResourceType.EXTERNAL_HTTP, name, Operation.CALL, confidence, detail,
        SmartPointerManager.getInstance(target.project).createSmartPsiElementPointer(target)
    )
}
