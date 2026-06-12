package com.liuxin.backendchain.analysis

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.SmartPointerManager
import com.liuxin.backendchain.model.*

class ExternalHttpExtractor : ResourceExtractor {
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
        if (owner.contains("RestTemplate") || owner.contains("WebClient") || owner.contains("OkHttp")) {
            val url = context.call?.argumentList?.expressions?.firstNotNullOfOrNull(::constantString) ?: "待确认URL"
            return listOf(resource(target, "$owner.${target.name} $url", if (url == "待确认URL") Confidence.UNRESOLVED else Confidence.INFERRED, "HTTP client"))
        }
        return emptyList()
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
