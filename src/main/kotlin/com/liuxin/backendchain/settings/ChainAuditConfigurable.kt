package com.liuxin.backendchain.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseWheelListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.ScrollPaneConstants
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

class ChainAuditConfigurable(private val project: Project) : Configurable {
    private val useApplicationSettings = JBCheckBox("使用应用全局配置")
    private val maxDepth = JSpinner(SpinnerNumberModel(30, 1, 100, 1))
    private val onlyProjectSource = JBCheckBox("调用链仅展示当前工程源码方法")
    private val excludedPackages = JBTextArea(7, 48).apply {
        toolTipText = "每行或逗号分隔一个包前缀，例如 org.slf4j."
    }
    private val followMq = JBCheckBox("沿本地 RabbitMQ/Kafka/RocketMQ topic 继续定位消费者")
    private val deduplicateResources = JBCheckBox("对所有资源类型去重")
    private val hideSimpleAccessors = JBCheckBox("隐藏简单 getter/setter 调用")
    private val followConcreteOverrides = JBCheckBox("跟踪普通方法的子类覆写候选")
    private val followCrossProjectFeign = JBCheckBox("沿 Feign 调用续链到已打开工程")
    private val maxCrossProjectDepth = JSpinner(SpinnerNumberModel(3, 1, 10, 1))
    private val batchRowTimeoutSeconds = JSpinner(SpinnerNumberModel(0, 0, 86_400, 10))
    private val crossProjectFeignMappings = JBTextArea(4, 48).apply {
        toolTipText = "每行一个映射：Feign服务名或占位符=已打开工程名或工程basePath"
    }
    private val excludedExternalHttpPathPatterns = JBTextArea(4, 48).apply {
        toolTipText = "每行一个查询类路径片段；也可用 regex: 前缀配置正则"
    }
    private val customHttpClientClasses = JBTextArea(4, 48).apply {
        toolTipText = "每行或逗号分隔一个 HTTP 工具类完整类名或包前缀"
    }
    private val customMqProducerAnnotations = JBTextArea(3, 48).apply {
        toolTipText = "每行或逗号分隔一个生产者注解短名或完整类名，默认启用 JshRabbitProducer"
    }
    private val customMqConsumerAnnotations = JBTextArea(3, 48).apply {
        toolTipText = "每行或逗号分隔一个消费者注解短名或完整类名，默认启用 JshRabbitConsumer"
    }
    private val customMqProducerClasses = JBTextArea(3, 48).apply {
        toolTipText = "每行或逗号分隔一个生产者类短名或完整类名，默认启用 JshRocketMqProducer"
    }
    private val customMqConsumerInterfaces = JBTextArea(3, 48).apply {
        toolTipText = "每行或逗号分隔一个消费者接口短名或完整类名，默认启用 JshRocketMqListener"
    }
    private val localServiceDirectories = JBTextField()
    private var component: JPanel? = null

    override fun getDisplayName() = "Backend Chain Audit"

    override fun createComponent(): JComponent {
        reset()
        return FormBuilder.createFormBuilder()
            .addComponent(useApplicationSettings)
            .addComponent(JBLabel("开启后本页配置对所有工程生效；关闭后仅保存到当前工程。"))
            .addLabeledComponent(JBLabel("最大方法递归深度："), maxDepth)
            .addComponent(onlyProjectSource)
            .addComponent(JBLabel("开启后隐藏依赖库和第三方工具包方法，例如仅保留 com.haier.jsh.order 等工程源码。"))
            .addLabeledComponent(JBLabel("过滤包前缀："), settingsTextArea(excludedPackages))
            .addComponent(JBLabel("匹配前缀的方法不会展示、统计或导出；每行或逗号分隔一个前缀。"))
            .addComponent(followMq)
            .addComponent(deduplicateResources)
            .addComponent(hideSimpleAccessors)
            .addComponent(JBLabel("仅隐藏与类字段对应的平凡访问器，不影响 getOrderInfo 等业务方法。"))
            .addComponent(followConcreteOverrides)
            .addComponent(JBLabel("关闭时仍会展开接口和抽象方法实现，但不对普通 public/protected 方法做全项目子类覆写搜索。"))
            .addComponent(followCrossProjectFeign)
            .addLabeledComponent(JBLabel("最大跨工程 Feign 深度："), maxCrossProjectDepth)
            .addLabeledComponent(JBLabel("批量单接口超时秒数："), batchRowTimeoutSeconds)
            .addComponent(JBLabel("0 表示不限制超时时间；设置为大于 0 时，单个接口超过该秒数会标记失败并继续后续接口。"))
            .addLabeledComponent(JBLabel("跨工程 Feign 服务映射："), settingsTextArea(crossProjectFeignMappings))
            .addComponent(JBLabel("每行格式：${'$'}{ylh.cloud.service.stock}=ylh-cloud-service-stock，或 stock-service=/path/to/project。批量统计不会使用该能力。"))
            .addLabeledComponent(JBLabel("外围接口查询分组路径："), settingsTextArea(excludedExternalHttpPathPatterns))
            .addComponent(JBLabel("匹配的外围接口会标记为查询并参与展示、统计和导出；例如 /get-、/list-、/search-，或 regex:/api/.*/(get|list|search)-。"))
            .addLabeledComponent(JBLabel("HTTP 工具类前缀："), settingsTextArea(customHttpClientClasses))
            .addComponent(JBLabel("工具类方法名用于推断 HTTP 方法，首个参数用于解析 URL。"))
            .addLabeledComponent(JBLabel("自定义 MQ 生产者注解："), settingsTextArea(customMqProducerAnnotations))
            .addLabeledComponent(JBLabel("自定义 MQ 消费者注解："), settingsTextArea(customMqConsumerAnnotations))
            .addLabeledComponent(JBLabel("自定义 MQ 生产者类："), settingsTextArea(customMqProducerClasses))
            .addLabeledComponent(JBLabel("自定义 MQ 消费者接口："), settingsTextArea(customMqConsumerInterfaces))
            .addComponent(JBLabel("支持短名或完整类名；默认保留 JSH RabbitMQ 注解和 JSH RocketMQ 包装器规则。"))
            .addLabeledComponent(JBLabel("本地服务目录："), localServiceDirectories)
            .addComponentFillVertically(JPanel(BorderLayout()), 0)
            .panel.also { component = it }
    }

    override fun isModified(): Boolean {
        val projectState = project.service<ChainAuditSettings>().state
        val target = targetState(useApplicationSettings.isSelected)
        return useApplicationSettings.isSelected != projectState.useApplicationSettings ||
            maxDepth.value != target.maxDepth ||
            onlyProjectSource.isSelected != target.onlyProjectSource ||
            normalizedPrefixes(excludedPackages.text) != normalizedPrefixes(target.excludedPackages) ||
            followMq.isSelected != target.followLocalMqConsumers ||
            deduplicateResources.isSelected != target.deduplicateResources ||
            hideSimpleAccessors.isSelected != target.hideSimpleAccessors ||
            followConcreteOverrides.isSelected != target.followConcreteMethodOverrides ||
            followCrossProjectFeign.isSelected != target.followCrossProjectFeign ||
            maxCrossProjectDepth.value != target.maxCrossProjectDepth ||
            batchRowTimeoutSeconds.value != target.batchRowTimeoutSeconds ||
            normalizedLines(crossProjectFeignMappings.text) != normalizedLines(target.crossProjectFeignMappings) ||
            normalizedLines(excludedExternalHttpPathPatterns.text) != normalizedLines(target.excludedExternalHttpPathPatterns) ||
            normalizedPrefixes(customHttpClientClasses.text) != normalizedPrefixes(target.customHttpClientClasses) ||
            normalizedPrefixes(customMqProducerAnnotations.text) != normalizedPrefixes(target.customMqProducerAnnotations) ||
            normalizedPrefixes(customMqConsumerAnnotations.text) != normalizedPrefixes(target.customMqConsumerAnnotations) ||
            normalizedPrefixes(customMqProducerClasses.text) != normalizedPrefixes(target.customMqProducerClasses) ||
            normalizedPrefixes(customMqConsumerInterfaces.text) != normalizedPrefixes(target.customMqConsumerInterfaces) ||
            localServiceDirectories.text.trim() != target.localServiceDirectories.trim()
    }

    override fun apply() {
        val projectState = project.service<ChainAuditSettings>().state
        projectState.useApplicationSettings = useApplicationSettings.isSelected
        val state = targetState(useApplicationSettings.isSelected)
        state.maxDepth = maxDepth.value as Int
        state.onlyProjectSource = onlyProjectSource.isSelected
        state.excludedPackages = normalizedPrefixes(excludedPackages.text).joinToString(",")
        state.followLocalMqConsumers = followMq.isSelected
        state.deduplicateResources = deduplicateResources.isSelected
        state.hideSimpleAccessors = hideSimpleAccessors.isSelected
        state.followConcreteMethodOverrides = followConcreteOverrides.isSelected
        state.followCrossProjectFeign = followCrossProjectFeign.isSelected
        state.maxCrossProjectDepth = maxCrossProjectDepth.value as Int
        state.batchRowTimeoutSeconds = batchRowTimeoutSeconds.value as Int
        state.crossProjectFeignMappings = normalizedLines(crossProjectFeignMappings.text).joinToString("\n")
        state.excludedExternalHttpPathPatterns = normalizedLines(excludedExternalHttpPathPatterns.text).joinToString("\n")
        state.customHttpClientClasses = normalizedPrefixes(customHttpClientClasses.text).joinToString(",")
        state.customMqProducerAnnotations = normalizedPrefixes(customMqProducerAnnotations.text).joinToString(",")
        state.customMqConsumerAnnotations = normalizedPrefixes(customMqConsumerAnnotations.text).joinToString(",")
        state.customMqProducerClasses = normalizedPrefixes(customMqProducerClasses.text).joinToString(",")
        state.customMqConsumerInterfaces = normalizedPrefixes(customMqConsumerInterfaces.text).joinToString(",")
        state.localServiceDirectories = localServiceDirectories.text.trim()
    }

    override fun reset() {
        val projectState = project.service<ChainAuditSettings>().state
        useApplicationSettings.isSelected = projectState.useApplicationSettings
        val state = targetState(projectState.useApplicationSettings)
        maxDepth.value = state.maxDepth.coerceIn(1, 100)
        onlyProjectSource.isSelected = state.onlyProjectSource
        excludedPackages.text = normalizedPrefixes(state.excludedPackages).joinToString("\n")
        followMq.isSelected = state.followLocalMqConsumers
        deduplicateResources.isSelected = state.deduplicateResources
        hideSimpleAccessors.isSelected = state.hideSimpleAccessors
        followConcreteOverrides.isSelected = state.followConcreteMethodOverrides
        followCrossProjectFeign.isSelected = state.followCrossProjectFeign
        maxCrossProjectDepth.value = state.maxCrossProjectDepth.coerceIn(1, 10)
        batchRowTimeoutSeconds.value = state.batchRowTimeoutSeconds.coerceIn(0, 86_400)
        crossProjectFeignMappings.text = normalizedLines(state.crossProjectFeignMappings).joinToString("\n")
        excludedExternalHttpPathPatterns.text = normalizedLines(state.excludedExternalHttpPathPatterns).joinToString("\n")
        customHttpClientClasses.text = normalizedPrefixes(state.customHttpClientClasses).joinToString("\n")
        customMqProducerAnnotations.text = normalizedPrefixes(state.customMqProducerAnnotations).joinToString("\n")
        customMqConsumerAnnotations.text = normalizedPrefixes(state.customMqConsumerAnnotations).joinToString("\n")
        customMqProducerClasses.text = normalizedPrefixes(state.customMqProducerClasses).joinToString("\n")
        customMqConsumerInterfaces.text = normalizedPrefixes(state.customMqConsumerInterfaces).joinToString("\n")
        localServiceDirectories.text = state.localServiceDirectories
    }

    override fun disposeUIResources() {
        component = null
    }

    private fun normalizedPrefixes(value: String): List<String> =
        value.split(',', '\n', '\r').map(String::trim).filter(String::isNotEmpty).distinct()

    private fun normalizedLines(value: String): List<String> =
        value.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()

    private fun targetState(useGlobal: Boolean): ChainAuditSettings.State =
        if (useGlobal) {
            ApplicationManager.getApplication().service<ChainAuditApplicationSettings>().state
        } else {
            project.service<ChainAuditSettings>().state
        }

    private fun settingsTextArea(textArea: JBTextArea): JScrollPane {
        val scrollPane = JScrollPane(
            textArea,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ).apply {
            isWheelScrollingEnabled = false
            preferredSize = Dimension(580, textArea.preferredSize.height + 4)
        }
        val forwardWheel = MouseWheelListener { event ->
            val parentScrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, scrollPane.parent) as? JScrollPane
                ?: return@MouseWheelListener
            val converted = SwingUtilities.convertMouseEvent(event.component, event, parentScrollPane)
            parentScrollPane.dispatchEvent(converted)
            event.consume()
        }
        scrollPane.addMouseWheelListener(forwardWheel)
        textArea.addMouseWheelListener(forwardWheel)
        return scrollPane
    }
}
