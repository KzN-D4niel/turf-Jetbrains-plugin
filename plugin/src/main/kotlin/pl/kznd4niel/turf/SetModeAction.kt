package pl.kznd4niel.turf

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service

/**
 * Wybor granulacji wlasnosci dla tego projektu. Dwie pozycje zachowuja sie jak radio:
 * klikniecie w juz wybrany tryb nic nie robi, wiec nie da sie zostac bez trybu.
 *
 * Ustawienie ladu w manifescie, nie w konfiguracji IDE - serwer MCP musi orzekac
 * dokladnie tak samo, a jedyne, co obie strony na pewno widza, to `.turf/ownership.json`.
 */
abstract class SetModeAction(private val mode: TurfMode) : ToggleAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        if (project.isDisposed) return false
        return project.service<TurfService>().mode == mode
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (!state) return
        val project = e.project ?: return
        project.service<TurfService>().setMode(mode)
        ProjectView.getInstance(project).refresh()
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.project != null
    }
}

class ModeFileAction : SetModeAction(TurfMode.FILE)

class ModePackageAction : SetModeAction(TurfMode.PACKAGE)
