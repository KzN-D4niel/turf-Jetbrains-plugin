package pl.kznd4niel.turf

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.components.service
import com.intellij.ui.JBColor

/**
 * Znakuje drzewo projektu wedlug wlasnosci.
 *
 * Pliki AI dostaja czerwona ikone w miejsce ikony typu. Nieprzypisane sa wyszarzone -
 * to stan, w ktorym AI nie ma prawa ani pisac, ani wnioskowac, wiec ma byc widoczny,
 * ale nie ma krzyczec. Twoje pliki wygladaja normalnie: brak dekoracji znaczy, ze
 * wszystko jest jak byc powinno.
 *
 * Tryb widoku (skrot z keymapy) decyduje, ktora strona jest wygaszona.
 */
class OwnerDecorator : ProjectViewNodeDecorator {

    private companion object {
        val DIM: JBColor = JBColor(0x9AA0A6, 0x6E7681)
    }

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.virtualFile ?: return
        if (file.isDirectory) return
        val project = node.project ?: return
        if (project.isDisposed) return

        val svc = project.service<TurfService>()
        val owner = svc.ownerOf(file)

        if (owner == Owner.AI) data.setIcon(TurfIcons.AI)

        val dim = when (svc.viewMode) {
            ViewMode.BOTH -> owner == Owner.NONE
            ViewMode.AI -> owner != Owner.AI
            ViewMode.HUMAN -> owner != Owner.HUMAN
        }
        if (dim) data.forcedTextForeground = DIM
    }
}
