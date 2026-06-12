package com.liuxin.backendchain.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.pom.Navigatable
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.table.JBTable
import com.liuxin.backendchain.export.ResultExporter
import com.liuxin.backendchain.model.*
import com.liuxin.backendchain.settings.ChainAuditSettings
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class ChainAuditPanel(private val project: Project) : JBPanel<ChainAuditPanel>(BorderLayout()), Disposable {
    private val title = JBLabel("尚未执行扫描")
    private val tree = JTree(DefaultMutableTreeNode("调用链"))
    private val tableModel = ResourceTableModel()
    private val table = JBTable(tableModel)
    private var result: AnalysisResult? = null

    init {
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(title)
            add(Box.createHorizontalGlue())
            add(JButton("设置").apply { addActionListener { editSettings() } })
            add(JButton("导出 Markdown").apply { addActionListener { export("md") } })
            add(JButton("导出 Mermaid").apply { addActionListener { export("mmd") } })
        }
        val splitter = JBSplitter(false, 0.45f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(tree)
            secondComponent = ScrollPaneFactory.createScrollPane(table)
        }
        add(toolbar, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        installNavigation()
    }

    fun showResult(result: AnalysisResult) {
        this.result = result
        title.text = "${result.entry.displayName}：${result.callGraph.methods.size} 个方法，${result.resources.size} 个资源，${result.warnings.size} 个提示"
        tree.model = DefaultTreeModel(buildTree(result))
        tableModel.setResources(result.resources)
        for (row in 0 until tree.rowCount) tree.expandRow(row)
        if (result.warnings.isNotEmpty()) treeRoot().add(DefaultMutableTreeNode("提示：${result.warnings.joinToString { it.message }}"))
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
        (pointer?.element as? Navigatable)?.navigate(true)
    }

    private fun export(extension: String) {
        val current = result ?: return
        val descriptor = FileSaverDescriptor("导出扫描结果", "选择保存位置", extension)
        val projectDir = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, this)
            .save(projectDir, "backend-chain-audit.$extension") ?: return
        val text = if (extension == "md") ResultExporter.markdown(current) else ResultExporter.mermaid(current)
        wrapper.file.writeText(text)
    }

    private fun editSettings() {
        val settings = project.service<ChainAuditSettings>()
        val state = settings.state
        val depth = Messages.showInputDialog(project, "最大方法递归深度（1-100）：", "Backend Chain Audit 设置", null, state.maxDepth.toString(), null)
            ?.toIntOrNull()?.coerceIn(1, 100) ?: return
        val excludes = Messages.showInputDialog(project, "排除包前缀，逗号分隔：", "Backend Chain Audit 设置", null, state.excludedPackages, null) ?: return
        val followMq = Messages.showYesNoDialog(project, "是否沿本地 RabbitMQ/Kafka topic 继续定位消费者？", "Backend Chain Audit 设置", null) == Messages.YES
        state.maxDepth = depth
        state.excludedPackages = excludes
        state.followLocalMqConsumers = followMq
    }

    private fun treeRoot() = tree.model.root as DefaultMutableTreeNode
    override fun dispose() = Unit

    private data class TreeItem(val method: MethodRef, val confidence: Confidence, val reason: String) {
        override fun toString() = "${method.displayName} [${confidence.displayName}：$reason]"
    }
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
