package pl.kznd4niel.turf.ai

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.wm.WindowManager
import pl.kznd4niel.turf.TurfService

/**
 * Podpina warstwe blokow AI pod kazdy edytor pliku, ktory lezy w tym projekcie.
 * Konsole, diffy i pliki spoza korzenia zostaja nietkniete - tam Turf nic nie orzeka.
 */
class AiEditorWatcher : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (!interesting(editor)) return
        AiDecor.attach(editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        AiDecor.detach(event.editor)
    }

    private fun interesting(editor: Editor): Boolean {
        val project = editor.project ?: return false
        if (project.isDisposed) return false
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        val svc = project.service<TurfService>()
        val rel = svc.relOf(file) ?: return false
        return !svc.isIgnored(rel)
    }
}

/**
 * Przycisk i skrot na fold Turfa. Zwija wszystkie bloki AI w biezacym edytorze, a jesli
 * juz sa zwiniete - rozwija. Zwijanie platformowe zostaje osobno, na swoim skrocie.
 */
class ToggleAiFoldsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val decor = decorOf(e)
        e.presentation.isEnabled = decor != null && decor.blocks().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // Przelacznik jest globalny, zeby nowo otwierany plik zachowywal sie tak samo
        // jak ten, na ktorym wlasnie kliknieto. Edytory przestawia sie same, bo sluchaja
        // serwisu.
        val collapse = project.service<TurfService>().toggleAiFolds()
        WindowManager.getInstance().getStatusBar(project)?.info =
            if (collapse) "Turf: bloki AI zwiniete" else "Turf: bloki AI rozwiniete"
    }

    private fun decorOf(e: AnActionEvent): AiDecor? =
        e.getData(CommonDataKeys.EDITOR)?.let { AiDecor.of(it) }
}

/**
 * Przelacza wyglad zwinietego bloku: napis w kodzie albo sama liczba linii w rynience.
 * Ustawienie jest per projekt i przezywa restart IDE.
 */
class ToggleFoldStyleAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
        if (project == null || project.isDisposed) return
        e.presentation.text = when (project.service<TurfService>().foldStyle) {
            AiFoldStyle.TEXT -> "Turf: Zwiniete bloki jako liczba w rynience"
            AiFoldStyle.COUNTER -> "Turf: Zwiniete bloki jako napis w kodzie"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val next = project.service<TurfService>().toggleFoldStyle()
        WindowManager.getInstance().getStatusBar(project)?.info = "Turf: fold AI - ${next.label}"
    }
}
