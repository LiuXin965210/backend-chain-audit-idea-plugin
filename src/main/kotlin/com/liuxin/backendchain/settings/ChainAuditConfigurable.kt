package com.liuxin.backendchain.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class ChainAuditConfigurable(private val project: Project) : Configurable {
    private val maxDepth = JSpinner(SpinnerNumberModel(30, 1, 100, 1))
    private val excludedPackages = JBTextArea(7, 48).apply {
        lineWrap = true
        wrapStyleWord = true
        toolTipText = "每行或逗号分隔一个包前缀，例如 org.slf4j."
    }
    private val followMq = JBCheckBox("沿本地 RabbitMQ/Kafka topic 继续定位消费者")
    private val localServiceDirectories = JBTextField()
    private var component: JPanel? = null

    override fun getDisplayName() = "Backend Chain Audit"

    override fun createComponent(): JComponent {
        reset()
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("最大方法递归深度："), maxDepth)
            .addLabeledComponent(JBLabel("过滤包前缀："), JScrollPane(excludedPackages))
            .addComponent(JBLabel("匹配前缀的方法不会展示、统计或导出；每行或逗号分隔一个前缀。"))
            .addComponent(followMq)
            .addLabeledComponent(JBLabel("本地服务目录："), localServiceDirectories)
            .addComponentFillVertically(JPanel(BorderLayout()), 0)
            .panel.also { component = it }
    }

    override fun isModified(): Boolean {
        val state = project.service<ChainAuditSettings>().state
        return maxDepth.value != state.maxDepth ||
            normalizedPrefixes(excludedPackages.text) != normalizedPrefixes(state.excludedPackages) ||
            followMq.isSelected != state.followLocalMqConsumers ||
            localServiceDirectories.text.trim() != state.localServiceDirectories.trim()
    }

    override fun apply() {
        val state = project.service<ChainAuditSettings>().state
        state.maxDepth = maxDepth.value as Int
        state.excludedPackages = normalizedPrefixes(excludedPackages.text).joinToString(",")
        state.followLocalMqConsumers = followMq.isSelected
        state.localServiceDirectories = localServiceDirectories.text.trim()
    }

    override fun reset() {
        val state = project.service<ChainAuditSettings>().state
        maxDepth.value = state.maxDepth.coerceIn(1, 100)
        excludedPackages.text = normalizedPrefixes(state.excludedPackages).joinToString("\n")
        followMq.isSelected = state.followLocalMqConsumers
        localServiceDirectories.text = state.localServiceDirectories
    }

    override fun disposeUIResources() {
        component = null
    }

    private fun normalizedPrefixes(value: String): List<String> =
        value.split(',', '\n', '\r').map(String::trim).filter(String::isNotEmpty).distinct()
}
