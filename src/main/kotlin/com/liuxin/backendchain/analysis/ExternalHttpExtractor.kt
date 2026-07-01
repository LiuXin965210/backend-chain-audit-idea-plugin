package com.liuxin.backendchain.analysis

import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiPolyadicExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.SmartPointerManager
import com.liuxin.backendchain.model.*

class ExternalHttpExtractor(
    private val customHttpClientClassPrefixes: List<String> = listOf("jsh.mgt.lib.http.BasicHttpUtil")
) : ResourceExtractor {
    override fun extract(context: CallContext): List<ResourceRef> {
        val target = context.resolvedMethod ?: return emptyList()
        val clazz = target.containingClass ?: return emptyList()
        FeignEndpointParser.parse(context)?.let { endpoint ->
            val confidence = if (endpoint.service.startsWith("\${") || endpoint.displayPath == "/") Confidence.UNRESOLVED else Confidence.CONFIRMED
            val displayName = ConfigValueResolver.resolvePlaceholders(target.project, endpoint.displayName)
            return listOf(resource(target, displayName, confidence, detail("Feign ${endpoint.source}", endpoint.displayName, displayName)))
        }

        val owner = clazz.qualifiedName.orEmpty()
        if (isHttpClient(owner)) {
            val url = httpUrl(context.call?.argumentList?.expressions?.firstOrNull()) ?: "待确认URL"
            val resolvedUrl = ConfigValueResolver.resolvePlaceholders(target.project, url)
            val httpMethod = httpMethod(target.name)
            return listOf(
                resource(
                    target,
                    "$httpMethod $resolvedUrl",
                    if (url == "待确认URL") Confidence.UNRESOLVED else Confidence.INFERRED,
                    detail("HTTP client $owner.${target.name}", url, resolvedUrl)
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

    private fun httpUrl(expression: PsiExpression?, depth: Int = 0): String? = when {
        depth > 6 -> null
        expression == null -> null
        expression is PsiLiteralExpression -> expression.value as? String
        expression is PsiReferenceExpression -> {
            constantString(expression) ?: when (val resolved = expression.resolve()) {
                is PsiLocalVariable -> httpUrl(resolved.initializer, depth + 1)
                is PsiField -> annotationString(resolved.annotations.firstOrNull {
                    it.qualifiedName?.endsWith(".Value") == true || it.nameReferenceElement?.referenceName == "Value"
                }, "value")
                else -> null
            }
        }
        expression is PsiMethodCallExpression -> stringFormatUrl(expression, depth + 1)
        expression is PsiPolyadicExpression -> expression.operands.mapNotNull { httpUrl(it, depth + 1) }.joinToString("").ifBlank { null }
        else -> constantString(expression)
    }

    private fun stringFormatUrl(call: PsiMethodCallExpression, depth: Int): String? {
        val expression = call.methodExpression
        if (expression.referenceName != "format" || expression.qualifierExpression?.text != "String") return null
        val args = call.argumentList.expressions
        val template = httpUrl(args.firstOrNull(), depth) ?: return null
        var index = 1
        val result = StringBuilder()
        var cursor = 0
        FORMAT_PLACEHOLDER.findAll(template).forEach { match ->
            result.append(template.substring(cursor, match.range.first))
            if (match.value == "%%") {
                result.append("%")
            } else {
                result.append(httpUrl(args.getOrNull(index++), depth) ?: return null)
            }
            cursor = match.range.last + 1
        }
        result.append(template.substring(cursor))
        return result.toString()
    }

    private fun detail(base: String, original: String, resolved: String): String =
        if (original == resolved) base else "$base；配置解析：$original -> $resolved"

    private fun resource(target: com.intellij.psi.PsiMethod, name: String, confidence: Confidence, detail: String) = ResourceRef(
        ResourceType.EXTERNAL_HTTP, name, Operation.CALL, confidence, detail,
        SmartPointerManager.getInstance(target.project).createSmartPsiElementPointer(target)
    )

    private companion object {
        private val FORMAT_PLACEHOLDER = Regex("""%%|%s""")
    }
}
