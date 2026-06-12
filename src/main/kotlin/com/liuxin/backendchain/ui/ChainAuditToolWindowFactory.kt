package com.liuxin.backendchain.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.liuxin.backendchain.analysis.ChainAnalysisService

class ChainAuditToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChainAuditPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "扫描结果", false))
        project.service<ChainAnalysisService>().subscribe(panel) { panel.showResult(it) }
    }

    companion object {
        fun activate(project: Project) {
            ToolWindowManager.getInstance(project).getToolWindow("Backend Chain Audit")?.activate(null)
        }
    }
}
