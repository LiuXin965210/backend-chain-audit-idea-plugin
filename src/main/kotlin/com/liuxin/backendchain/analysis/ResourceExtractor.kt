package com.liuxin.backendchain.analysis

import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.liuxin.backendchain.model.ResourceRef

data class CallContext(
    val method: PsiMethod,
    val call: PsiMethodCallExpression?,
    val resolvedMethod: PsiMethod?,
    val sourceText: String
)

interface ResourceExtractor {
    fun supports(context: CallContext): Boolean = true
    fun extract(context: CallContext): List<ResourceRef>
}
