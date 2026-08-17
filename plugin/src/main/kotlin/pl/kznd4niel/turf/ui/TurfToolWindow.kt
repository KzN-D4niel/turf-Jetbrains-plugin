package pl.kznd4niel.turf.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import pl.kznd4niel.turf.EditRequest
import pl.kznd4niel.turf.TurfService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel

class TurfToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val requests = RequestsPanel(project)

        val svc = project.service<TurfService>()
        val refresh = Runnable { requests.refresh() }
        svc.addListener(refresh)
        Disposer.register(toolWindow.disposable) { svc.removeListener(refresh) }
        refresh.run()

        val content = ContentFactory.getInstance().createContent(requests, null, false)
        toolWindow.contentManager.addContent(content)
    }
}

private fun openAt(project: Project, rel: String, line: Int) {
    val base = project.basePath ?: return
    val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$base/$rel") ?: return
    OpenFileDescriptor(project, vf, (line - 1).coerceAtLeast(0), 0).navigate(true)
}

// ---------------------------------------------------------------- wnioski

class RequestsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val model = DefaultListModel<EditRequest>()
    private val list = JBList(model)
    private val details = JTextArea().apply {
        isEditable = false
        lineWrap = false
    }

    private val accept = JButton("Przyjmij")
    private val reject = JButton("Odrzuc")
    private val open = JButton("Otworz plik")
    private val purge = JButton("Usun rozpatrzone")

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                l: JList<*>?, value: Any?, index: Int, sel: Boolean, focus: Boolean
            ): Component {
                val c = super.getListCellRendererComponent(l, value, index, sel, focus)
                val r = value as? EditRequest ?: return c
                val mark = when (r.status) {
                    "accepted" -> "[przyjety] "
                    "rejected" -> "[odrzucony] "
                    else -> ""
                }
                text = "$mark${r.path}  (linie ${r.zakres})"
                return c
            }
        }
        list.addListSelectionListener { showDetails() }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT))
        listOf(accept, reject, open, purge).forEach { buttons.add(it) }

        accept.addActionListener {
            val r = selected() ?: return@addActionListener
            val err = project.service<TurfService>().accept(r)
            if (err != null) Messages.showErrorDialog(project, err, "Turf")
        }
        reject.addActionListener {
            val r = selected() ?: return@addActionListener
            val note = Messages.showInputDialog(
                project, "Komentarz dla AI (mozesz zostawic puste):", "Turf: odrzuc wniosek", null
            )
            project.service<TurfService>().reject(r, note?.ifBlank { null })
        }
        open.addActionListener {
            val r = selected() ?: return@addActionListener
            openAt(project, r.path, r.edits.firstOrNull()?.lineStart ?: 1)
        }
        purge.addActionListener { project.service<TurfService>().deleteDecided() }

        val split = OnePixelSplitter(false, 0.4f)
        split.firstComponent = JBScrollPane(list)
        split.secondComponent = JBScrollPane(details)
        add(split, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }

    private fun selected(): EditRequest? = list.selectedValue

    fun refresh() {
        val keep = list.selectedIndex
        model.clear()
        project.service<TurfService>().requests().forEach(model::addElement)
        if (keep in 0 until model.size()) list.selectedIndex = keep
        showDetails()
        val hasPending = selected()?.status == "pending"
        accept.isEnabled = hasPending
        reject.isEnabled = hasPending
        open.isEnabled = selected() != null
    }

    private fun showDetails() {
        val r = selected()
        if (r == null) {
            details.text = ""
            return
        }
        val sb = StringBuilder()
        sb.append("plik:    ").append(r.path).append('\n')
        sb.append("status:  ").append(r.status).append('\n')
        sb.append("zlozony: ").append(r.createdAt).append('\n')
        r.note?.let { sb.append("komentarz: ").append(it).append('\n') }
        sb.append("\npowod:\n").append(r.reason).append("\n\n")
        for (e in r.edits) {
            sb.append("--- linie ${e.lineStart}-${e.lineEnd} ---\n")
            sb.append("bylo:\n").append(e.oldText ?: "(nie zapisano)").append('\n')
            sb.append("ma byc:\n").append(e.replacement).append("\n\n")
        }
        details.text = sb.toString()
        details.caretPosition = 0

        val pending = r.status == "pending"
        accept.isEnabled = pending
        reject.isEnabled = pending
        open.isEnabled = true
    }
}
