package com.liuxin.backendchain.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.table.JBTable
import com.liuxin.backendchain.action.AnalyzeBatchHttpAction
import com.liuxin.backendchain.export.ResultExporter
import com.liuxin.backendchain.model.*
import com.liuxin.backendchain.settings.ChainAuditConfigurable
import com.liuxin.backendchain.settings.ChainAuditSettings
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class ChainAuditPanel(private val project: Project) : JBPanel<ChainAuditPanel>(BorderLayout()), Disposable {
    private val title = JBLabel("尚未执行扫描")
    private val tree = JTree(DefaultMutableTreeNode("调用链"))
    private val tableModel = ResourceTableModel()
    private val table = object : JBTable(tableModel) {
        override fun getToolTipText(event: MouseEvent): String? {
            val row = rowAtPoint(event.point)
            val column = columnAtPoint(event.point)
            if (row < 0 || column < 0) return null
            return getValueAt(row, column)?.toString()
        }
    }
    private val tableSorter = TableRowSorter(tableModel)
    private val typeFilter = JComboBox(arrayOf("全部类型") + ResourceType.entries.map { it.displayName }.toTypedArray())
    private val resourceFilter = JTextField(18)
    private val operationFilter = JComboBox(arrayOf("全部操作") + Operation.entries.map { it.displayName }.toTypedArray())
    private var result: AnalysisResult? = null

    init {
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(title)
            add(Box.createHorizontalGlue())
            add(JButton("设置").apply { addActionListener { openSettings() } })
            add(JButton("批量统计").apply { addActionListener { AnalyzeBatchHttpAction.start(project) } })
            add(JButton("导出 Markdown").apply { addActionListener { export("md") } })
            add(JButton("导出 CSV").apply { addActionListener { export("csv") } })
            add(JButton("导出 Mermaid").apply { addActionListener { export("mmd") } })
        }
        table.rowSorter = tableSorter
        val resourceFilters = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
            add(JLabel("类型：")); add(typeFilter)
            add(Box.createHorizontalStrut(8))
            add(JLabel("资源：")); add(resourceFilter)
            add(Box.createHorizontalStrut(8))
            add(JLabel("操作：")); add(operationFilter)
            add(Box.createHorizontalGlue())
        }
        val resourcePanel = JPanel(BorderLayout()).apply {
            add(resourceFilters, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        }
        val splitter = JBSplitter(false, 0.45f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree)
            secondComponent = resourcePanel
        }
        add(toolbar, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        installNavigation()
        installResourceFilters()
    }

    fun showResult(result: AnalysisResult) {
        this.result = result
        title.text = "${result.entry.displayName}：${result.callGraph.methods.size} 个方法，${result.resources.size} 个资源，${result.warnings.size} 个提示"
        tree.model = DefaultTreeModel(buildTree(result))
        tableModel.setResources(result.resources)
        for (row in 0 until tree.rowCount) tree.expandRow(row)
        if (result.warnings.isNotEmpty()) treeRoot().add(DefaultMutableTreeNode("提示：${result.warnings.joinToString { it.message }}"))
    }

    fun showStatus(status: AnalysisStatus) {
        title.text = status.message
        title.toolTipText = if (status.error) "请查看 IDEA 日志中的 Backend Chain Audit 异常" else null
    }

    private fun buildTree(result: AnalysisResult): DefaultMutableTreeNode {
        val rootRef = result.callGraph.methods[result.callGraph.root]
        val root = DefaultMutableTreeNode(rootRef ?: result.entry.displayName)
        val children = result.callGraph.edges.groupBy { it.caller }
        fun append(parent: DefaultMutableTreeNode, key: String, path: Set<String>) {
            children[key].orEmpty().forEach { edge ->
                val method = result.callGraph.methods[edge.callee] ?: return@forEach
                val node = DefaultMutableTreeNode(TreeItem(method, edge.confidence, edge.reason))
                parent.add(node)
                if (edge.callee !in path) append(node, edge.callee, path + edge.callee)
            }
        }
        if (rootRef != null) append(root, rootRef.key, setOf(rootRef.key))
        return root
    }

    private fun installNavigation() {
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigate((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject)
            }
        })
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && table.selectedRow >= 0) navigate(tableModel.resourceAt(table.convertRowIndexToModel(table.selectedRow)))
            }
        })
    }

    private fun navigate(value: Any?) {
        val pointer = when (value) {
            is TreeItem -> value.method.pointer
            is MethodRef -> value.pointer
            is ResourceRef -> value.pointer
            else -> null
        }
        val target = ReadAction.compute<NavigationTarget?, RuntimeException> {
            val element = pointer?.element ?: return@compute null
            val navigationElement = element.navigationElement
            val file = navigationElement.containingFile?.virtualFile ?: return@compute null
            NavigationTarget(file, navigationElement.textOffset.coerceAtLeast(0))
        } ?: return
        OpenFileDescriptor(project, target.file, target.offset).navigate(true)
    }

    private fun export(extension: String) {
        val current = result ?: return
        val descriptor = FileSaverDescriptor("导出扫描结果", "选择保存位置", extension)
        val projectDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, this)
            .save(projectDir, "backend-chain-audit.$extension") ?: return
        val text = when (extension) {
            "md" -> ResultExporter.markdown(current)
            "csv" -> ResultExporter.csv(current)
            else -> ResultExporter.mermaid(current)
        }
        wrapper.file.writeText(text)
    }

    private fun installResourceFilters() {
        typeFilter.addActionListener { applyResourceFilters() }
        operationFilter.addActionListener { applyResourceFilters() }
        resourceFilter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyResourceFilters()
            override fun removeUpdate(e: DocumentEvent) = applyResourceFilters()
            override fun changedUpdate(e: DocumentEvent) = applyResourceFilters()
        })
    }

    private fun applyResourceFilters() {
        val selectedType = typeFilter.selectedItem?.toString().orEmpty()
        val selectedOperation = operationFilter.selectedItem?.toString().orEmpty()
        val resourceText = resourceFilter.text.trim()
        tableSorter.rowFilter = object : RowFilter<ResourceTableModel, Int>() {
            override fun include(entry: Entry<out ResourceTableModel, out Int>): Boolean {
                val typeMatches = selectedType == "全部类型" || entry.getStringValue(0) == selectedType
                val resourceMatches = resourceText.isBlank() || entry.getStringValue(1).contains(resourceText, ignoreCase = true)
                val operationMatches = selectedOperation == "全部操作" || entry.getStringValue(2) == selectedOperation
                return typeMatches && resourceMatches && operationMatches
            }
        }
    }

    private fun openSettings() =
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ChainAuditConfigurable::class.java)

    private fun treeRoot() = tree.model.root as DefaultMutableTreeNode
    override fun dispose() = Unit

    private data class TreeItem(val method: MethodRef, val confidence: Confidence, val reason: String) {
        override fun toString() = "${method.displayName} [${confidence.displayName}：$reason]"
    }

    private data class NavigationTarget(val file: com.intellij.openapi.vfs.VirtualFile, val offset: Int)
}

private class ResourceTableModel : AbstractTableModel() {
    private val columns = arrayOf("类型", "资源", "操作", "置信度", "证据")
    private var resources: List<ResourceRef> = emptyList()
    fun setResources(resources: List<ResourceRef>) { this.resources = resources; fireTableDataChanged() }
    fun resourceAt(row: Int) = resources[row]
    override fun getRowCount() = resources.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = resources[rowIndex].let {
        when (columnIndex) { 0 -> it.type.displayName; 1 -> it.name; 2 -> it.operation.displayName; 3 -> it.confidence.displayName; else -> it.detail }
    }
}
