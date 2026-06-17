package com.liuxin.backendchain.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.liuxin.backendchain.analysis.ChainAnalysisService
import com.liuxin.backendchain.settings.ChainAuditSettings
import com.liuxin.backendchain.ui.ChainAuditToolWindowFactory
import java.awt.BorderLayout
import java.io.File
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

private const val MAX_BATCH_HTTP_INPUTS = 100

class AnalyzeBatchHttpAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        start(project)
    }

    companion object {
        fun start(project: Project) {
            run(project)
        }
    }
}

private fun run(project: Project) {
    val dialog = BatchHttpInputDialog(project)
    if (!dialog.showAndGet()) return
    val validation = validateBatchHttpInputs(dialog.rawInput())
    if (validation.errors.isNotEmpty()) {
        Messages.showErrorDialog(project, validation.errorMessage(), "批量统计输入校验失败")
        return
    }
    if (validation.duplicateLines.isNotEmpty()) {
        Messages.showInfoMessage(project, validation.deduplicateMessage(), "批量统计已去重")
    }
    val inputs = validation.inputs
    val format = dialog.exportFormat()
    if (!format.includesCsv && !format.includesMarkdown) {
        Messages.showWarningDialog(project, "批量统计必须至少选择一种导出格式。", "Backend Chain Audit")
        return
    }
    val selectedFile = chooseExportFile(project, format) ?: return
    val csvFile = if (format.includesCsv) csvFileFor(selectedFile) else null
    val markdownFile = if (format.includesMarkdown) markdownFileFor(selectedFile) else null
    ChainAuditToolWindowFactory.activate(project)
    project.service<ChainAnalysisService>().analyzeBatchHttpPaths(
        inputs,
        csvFile,
        markdownFile,
        project.service<ChainAuditSettings>().options()
    )
}

internal fun validateBatchHttpInputs(rawInput: String): BatchHttpInputValidation {
    val errors = mutableListOf<String>()
    val seen = linkedSetOf<String>()
    val inputs = mutableListOf<String>()
    val duplicateLines = mutableListOf<Pair<Int, String>>()
    rawInput.lineSequence().forEachIndexed { index, rawLine ->
        val lineNumber = index + 1
        val value = rawLine.trim()
        if (value.isEmpty()) return@forEachIndexed
        val error = validateHttpPath(value)
        if (error != null) {
            errors += "第 ${lineNumber} 行 `$value`：$error"
            return@forEachIndexed
        }
        if (seen.add(value)) {
            inputs += value
        } else {
            duplicateLines += lineNumber to value
        }
    }
    if (inputs.isEmpty() && errors.isEmpty()) {
        errors += "请输入至少一个 HTTP 接口路径。"
    }
    if (inputs.size > MAX_BATCH_HTTP_INPUTS) {
        errors += "批量统计单次最多支持 $MAX_BATCH_HTTP_INPUTS 个去重后的接口，当前为 ${inputs.size} 个。"
    }
    return BatchHttpInputValidation(inputs, duplicateLines, errors)
}

private fun validateHttpPath(value: String): String? = when {
    !value.startsWith("/") -> "必须以 `/` 开头，请输入接口路径，不要输入完整 URL。"
    value == "/" -> "不能只输入根路径 `/`。"
    value.any(Char::isWhitespace) -> "路径中不能包含空格或制表符；请确认是否一行写了多个接口。"
    value.contains("://") -> "请只输入路径部分，例如 `/api/order/save`。"
    value.contains("?") || value.contains("#") -> "请只输入路径，不要包含 query 参数或 fragment。"
    else -> null
}

internal data class BatchHttpInputValidation(
    val inputs: List<String>,
    val duplicateLines: List<Pair<Int, String>>,
    val errors: List<String>
) {
    fun errorMessage(): String = buildString {
        appendLine("请修正以下输入后再执行批量统计：")
        errors.take(20).forEach { appendLine("- $it") }
        if (errors.size > 20) appendLine("- 还有 ${errors.size - 20} 条错误未显示。")
    }.trimEnd()

    fun deduplicateMessage(): String = buildString {
        appendLine("已自动去重 ${duplicateLines.size} 行重复接口，将仅扫描 ${inputs.size} 个唯一接口。")
        duplicateLines.take(10).forEach { (line, value) -> appendLine("- 第 ${line} 行重复：`$value`") }
        if (duplicateLines.size > 10) appendLine("- 还有 ${duplicateLines.size - 10} 行重复未显示。")
    }.trimEnd()
}

private fun chooseExportFile(project: Project, format: BatchExportFormat): File? {
    val descriptor = FileSaverDescriptor("导出批量统计结果", format.description, format.primaryExtension)
    val projectDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
    val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        .save(projectDir, "backend-chain-audit-batch.${format.primaryExtension}") ?: return null
    val file = wrapper.file
    return if (file.name.endsWith(".${format.primaryExtension}", ignoreCase = true)) {
        file
    } else {
        File(file.parentFile ?: File("."), "${file.name}.${format.primaryExtension}")
    }
}

private fun csvFileFor(file: File): File =
    if (file.name.endsWith(".csv", ignoreCase = true)) file else siblingWithExtension(file, "csv")

private fun markdownFileFor(file: File): File =
    if (file.name.endsWith(".md", ignoreCase = true)) file else siblingWithExtension(file, "md")

private fun siblingWithExtension(file: File, extension: String): File {
    val baseName = file.name.substringBeforeLast('.')
    return File(file.parentFile ?: File("."), "$baseName.$extension")
}

private class BatchHttpInputDialog(project: Project) : DialogWrapper(project) {
    private val textArea = JBTextArea(16, 72).apply {
        lineWrap = false
        emptyText.text = "/api/order/save\n/api/order/detail"
    }
    private val csvCheckBox = JBCheckBox("CSV", false)
    private val markdownCheckBox = JBCheckBox("Markdown", true)

    init {
        title = "批量统计 HTTP 接口"
        init()
    }

    fun rawInput(): String = textArea.text

    fun exportFormat(): BatchExportFormat = BatchExportFormat(
        includesCsv = csvCheckBox.isSelected,
        includesMarkdown = markdownCheckBox.isSelected
    )

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JBLabel("一行一个 HTTP 接口路径，最多 100 个。导出："))
            add(Box.createHorizontalStrut(8))
            add(csvCheckBox)
            add(Box.createHorizontalStrut(8))
            add(markdownCheckBox)
            add(Box.createHorizontalGlue())
        }
        add(header, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(textArea), BorderLayout.CENTER)
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)
}

private data class BatchExportFormat(
    val includesCsv: Boolean,
    val includesMarkdown: Boolean
) {
    val primaryExtension: String = if (includesCsv) "csv" else "md"
    val description: String = when {
        includesCsv && includesMarkdown -> "选择 CSV 保存位置；同名 Markdown 会一起生成"
        includesCsv -> "选择 CSV 保存位置"
        includesMarkdown -> "选择 Markdown 保存位置"
        else -> "选择导出保存位置"
    }
}
