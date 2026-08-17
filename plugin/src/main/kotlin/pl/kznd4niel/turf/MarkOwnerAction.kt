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
    /** Wariant "tylko ten plik": wyjatek od wlasnosci pakietu, wiec tylko w tym trybie. */
    private val fileOverride: Boolean = false,
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isEnabledAndVisible = project != null && !files.isNullOrEmpty()
        if (project == null || project.isDisposed || files.isNullOrEmpty()) return

        val svc = project.service<TurfService>()
        if (fileOverride) {
            e.presentation.isEnabledAndVisible = svc.mode == TurfMode.PACKAGE
            e.presentation.text = fileText
            e.presentation.description = "Obejmie sam plik, mimo wlasnosci pakietu"
            return
        }
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
        project.service<TurfService>().setOwner(files.toList(), owner, fileOverride)
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

class MarkAsSharedAction : MarkOwnerAction(
    Owner.SHARED,
    "Oznacz jako wspolne",
    "Oznacz pakiet jako wspolny",
)

class ClearOwnerAction : MarkOwnerAction(
    Owner.NONE,
    "Wyczysc wlasnosc",
    "Wyczysc wlasnosc pakietu",
)

// Wyjatki od pakietu: pojedynczy plik dostaje wlasnosc wbrew temu, co mowi jego pakiet.
// Widoczne wylacznie w trybie pakietowym - w plikowym kazdy wpis i tak jest plikowy.

class OverrideAsMineAction : MarkOwnerAction(
    Owner.HUMAN, "Tylko ten plik: moj", "", fileOverride = true,
)

class OverrideAsAiAction : MarkOwnerAction(
    Owner.AI, "Tylko ten plik: AI", "", fileOverride = true,
)

class OverrideAsSharedAction : MarkOwnerAction(
    Owner.SHARED, "Tylko ten plik: wspolny", "", fileOverride = true,
)

class OverrideClearAction : MarkOwnerAction(
    Owner.NONE, "Tylko ten plik: wyczysc wyjatek", "", fileOverride = true,
)
