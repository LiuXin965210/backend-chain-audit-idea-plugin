package com.liuxin.backendchain.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages
import com.liuxin.backendchain.analysis.ChainAnalysisService
import com.liuxin.backendchain.settings.ChainAuditSettings
import com.liuxin.backendchain.ui.ChainAuditToolWindowFactory

class AnalyzeByInputAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val input = Messages.showInputDialog(
            project,
            "输入完整 HTTP 路径（以 / 开头）或 MQ topic/queue：",
            "Backend Chain Audit",
            Messages.getQuestionIcon()
        )?.trim()?.takeIf { it.isNotEmpty() } ?: return
        ChainAuditToolWindowFactory.activate(project)
        val service = project.service<ChainAnalysisService>()
        val options = project.service<ChainAuditSettings>().options()
        if (input.startsWith('/')) service.analyzeHttpPath(input, options) else service.analyzeMqTopic(input, options)
    }
}
