package pl.kznd4niel.turf

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.WindowManager

/** Przelacza, na czym skupia sie drzewo: oba -> AI -> Ty -> oba. */
class ToggleViewAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val mode = project.service<TurfService>().cycleViewMode()
        ProjectView.getInstance(project).refresh()

        val opis = when (mode) {
            ViewMode.BOTH -> "oba terytoria — wyszarzone tylko nieprzypisane"
            ViewMode.AI -> "tylko AI — reszta wyszarzona"
            ViewMode.HUMAN -> "tylko Twoje — reszta wyszarzona"
        }
        WindowManager.getInstance().getStatusBar(project)?.info = "Turf: $opis"
    }
}
