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
    private val followMq = JBCheckBox("沿本地 RabbitMQ/Kafka/RocketMQ topic 继续定位消费者")
    private val deduplicateResources = JBCheckBox("对所有资源类型去重")
    private val hideSimpleAccessors = JBCheckBox("隐藏简单 getter/setter 调用")
    private val customHttpClientClasses = JBTextArea(4, 48).apply {
        lineWrap = true
        wrapStyleWord = true
        toolTipText = "每行或逗号分隔一个 HTTP 工具类完整类名或包前缀"
    }
    private val customMqProducerAnnotations = JBTextArea(3, 48).apply {
        lineWrap = true
        wrapStyleWord = true
        toolTipText = "每行或逗号分隔一个生产者注解短名或完整类名，默认启用 JshRabbitProducer"
    }
    private val customMqConsumerAnnotations = JBTextArea(3, 48).apply {
        lineWrap = true
        wrapStyleWord = true
        toolTipText = "每行或逗号分隔一个消费者注解短名或完整类名，默认启用 JshRabbitConsumer"
    }
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
            .addComponent(deduplicateResources)
            .addComponent(hideSimpleAccessors)
            .addComponent(JBLabel("仅隐藏与类字段对应的平凡访问器，不影响 getOrderInfo 等业务方法。"))
            .addLabeledComponent(JBLabel("HTTP 工具类前缀："), JScrollPane(customHttpClientClasses))
            .addComponent(JBLabel("工具类方法名用于推断 HTTP 方法，首个参数用于解析 URL。"))
            .addLabeledComponent(JBLabel("自定义 MQ 生产者注解："), JScrollPane(customMqProducerAnnotations))
            .addLabeledComponent(JBLabel("自定义 MQ 消费者注解："), JScrollPane(customMqConsumerAnnotations))
            .addComponent(JBLabel("支持注解短名或完整类名；默认保留 JSH RabbitMQ 规则。"))
            .addLabeledComponent(JBLabel("本地服务目录："), localServiceDirectories)
            .addComponentFillVertically(JPanel(BorderLayout()), 0)
            .panel.also { component = it }
    }

    override fun isModified(): Boolean {
        val state = project.service<ChainAuditSettings>().state
        return maxDepth.value != state.maxDepth ||
            normalizedPrefixes(excludedPackages.text) != normalizedPrefixes(state.excludedPackages) ||
            followMq.isSelected != state.followLocalMqConsumers ||
            deduplicateResources.isSelected != state.deduplicateResources ||
            hideSimpleAccessors.isSelected != state.hideSimpleAccessors ||
            normalizedPrefixes(customHttpClientClasses.text) != normalizedPrefixes(state.customHttpClientClasses) ||
            normalizedPrefixes(customMqProducerAnnotations.text) != normalizedPrefixes(state.customMqProducerAnnotations) ||
            normalizedPrefixes(customMqConsumerAnnotations.text) != normalizedPrefixes(state.customMqConsumerAnnotations) ||
            localServiceDirectories.text.trim() != state.localServiceDirectories.trim()
    }

    override fun apply() {
        val state = project.service<ChainAuditSettings>().state
        state.maxDepth = maxDepth.value as Int
        state.excludedPackages = normalizedPrefixes(excludedPackages.text).joinToString(",")
        state.followLocalMqConsumers = followMq.isSelected
        state.deduplicateResources = deduplicateResources.isSelected
        state.hideSimpleAccessors = hideSimpleAccessors.isSelected
        state.customHttpClientClasses = normalizedPrefixes(customHttpClientClasses.text).joinToString(",")
        state.customMqProducerAnnotations = normalizedPrefixes(customMqProducerAnnotations.text).joinToString(",")
        state.customMqConsumerAnnotations = normalizedPrefixes(customMqConsumerAnnotations.text).joinToString(",")
        state.localServiceDirectories = localServiceDirectories.text.trim()
    }

    override fun reset() {
        val state = project.service<ChainAuditSettings>().state
        maxDepth.value = state.maxDepth.coerceIn(1, 100)
        excludedPackages.text = normalizedPrefixes(state.excludedPackages).joinToString("\n")
        followMq.isSelected = state.followLocalMqConsumers
        deduplicateResources.isSelected = state.deduplicateResources
        hideSimpleAccessors.isSelected = state.hideSimpleAccessors
        customHttpClientClasses.text = normalizedPrefixes(state.customHttpClientClasses).joinToString("\n")
        customMqProducerAnnotations.text = normalizedPrefixes(state.customMqProducerAnnotations).joinToString("\n")
        customMqConsumerAnnotations.text = normalizedPrefixes(state.customMqConsumerAnnotations).joinToString("\n")
        localServiceDirectories.text = state.localServiceDirectories
    }

    override fun disposeUIResources() {
        component = null
    }

    private fun normalizedPrefixes(value: String): List<String> =
        value.split(',', '\n', '\r').map(String::trim).filter(String::isNotEmpty).distinct()
}
