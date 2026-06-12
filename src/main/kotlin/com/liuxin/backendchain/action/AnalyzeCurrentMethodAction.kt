package com.liuxin.backendchain.action

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.liuxin.backendchain.analysis.ChainAnalysisService
import com.liuxin.backendchain.settings.ChainAuditSettings
import com.liuxin.backendchain.ui.ChainAuditToolWindowFactory
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElementOfType

class AnalyzeCurrentMethodAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = file != null && editor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val element = file.findElementAt(editor.caretModel.offset) ?: return
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            ?: element.toUElementOfType<UMethod>()?.javaPsi
            ?: generateSequence(element.parent) { it.parent }.mapNotNull { it.toUElementOfType<UMethod>()?.javaPsi }.firstOrNull()
            ?: return
        ChainAuditToolWindowFactory.activate(project)
        project.service<ChainAnalysisService>().analyzeMethod(method, options = project.service<ChainAuditSettings>().options())
    }
}
