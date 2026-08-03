package pl.kznd4niel.turf

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service

/**
 * Nadanie wlasnosci z drzewa projektu. To, co dokladnie zostanie oznaczone, zalezy od
 * trybu: w plikowym zaznaczone pliki (rekurencyjnie po katalogach), w pakietowym pakiet,
 * w ktorym leza. Nazwa pozycji w menu mowi to wprost, zeby nie trzeba bylo zgadywac,
 * jak daleko siegnie klikniecie.
 */
abstract class MarkOwnerAction(
    private val owner: Owner,
    private val fileText: String,
    private val packageText: String,
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isEnabledAndVisible = project != null && !files.isNullOrEmpty()
        if (project == null || project.isDisposed || files.isNullOrEmpty()) return

        val svc = project.service<TurfService>()
        if (svc.mode != TurfMode.PACKAGE) {
            e.presentation.text = fileText
            return
        }

        e.presentation.text = packageText
        val pakiety = files.mapNotNull { svc.packageOf(it) }.distinct()
        e.presentation.description = when (pakiety.size) {
            1 -> "Obejmie caly pakiet ${pakiety[0].ifEmpty { "(korzen repozytorium)" }}"
            else -> "Obejmie ${pakiety.size} pakiety wraz z zawartoscia"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        project.service<TurfService>().setOwner(files.toList(), owner)
        ProjectView.getInstance(project).refresh()
    }
}

class MarkAsMineAction : MarkOwnerAction(
    Owner.HUMAN,
    "Oznacz jako moje",
    "Oznacz pakiet jako moj",
)

class MarkAsAiAction : MarkOwnerAction(
    Owner.AI,
    "Oznacz jako AI",
    "Oznacz pakiet jako AI",
)

class ClearOwnerAction : MarkOwnerAction(
    Owner.NONE,
    "Wyczysc wlasnosc",
    "Wyczysc wlasnosc pakietu",
)
