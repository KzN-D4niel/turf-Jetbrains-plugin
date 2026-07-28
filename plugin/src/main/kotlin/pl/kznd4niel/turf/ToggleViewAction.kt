package pl.kznd4niel.turf

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.WindowManager

/** Pokazuje albo ukrywa czerwone ikony przy plikach AI. */
class ToggleViewAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
        if (project != null && !project.isDisposed) {
            e.presentation.text =
                if (project.service<TurfService>().showAiIcons) "Turf: Ukryj ikony AI"
                else "Turf: Pokaz ikony AI"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val widoczne = project.service<TurfService>().toggleAiIcons()
        ProjectView.getInstance(project).refresh()
        WindowManager.getInstance().getStatusBar(project)?.info =
            if (widoczne) "Turf: ikony AI widoczne" else "Turf: ikony AI ukryte"
    }
}
