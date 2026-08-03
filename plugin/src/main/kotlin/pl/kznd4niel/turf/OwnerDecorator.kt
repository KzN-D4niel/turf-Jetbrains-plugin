package pl.kznd4niel.turf

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.components.service
import com.intellij.ui.JBColor

/**
 * Znakuje drzewo projektu wedlug wlasnosci.
 *
 * Pliki AI dostaja czerwona ikone w miejsce ikony typu - i to wlasnie ona chodzi pod
 * skrotem: jednym klawiszem znikaja, drugim wracaja, zeby dalo sie spojrzec na drzewo
 * bez tego oznaczenia. Nieprzypisane sa wyszarzone niezaleznie od przelacznika, bo to
 * nie jest oznaczenie AI tylko sygnal, ze plik czeka na Twoja decyzje. Twoje pliki nie
 * dostaja nic: brak dekoracji znaczy, ze wszystko jest jak byc powinno.
 *
 * W trybie pakietowym te same reguly obejmuja katalogi, bo to one sa jednostka
 * wlasnosci. Pliki w srodku i tak dostaja oznaczenie odziedziczone, wiec granica
 * pakietu jest widoczna od razu, a nie dopiero po rozwinieciu.
 */
class OwnerDecorator : ProjectViewNodeDecorator {

    private companion object {
        val DIM: JBColor = JBColor(0x9AA0A6, 0x6E7681)
    }

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.virtualFile ?: return
        val project = node.project ?: return
        if (project.isDisposed) return

        val svc = project.service<TurfService>()
        if (file.isDirectory && svc.mode != TurfMode.PACKAGE) return
        // Poza korzeniem projektu Turf nic nie orzeka, wiec nie znakuje: inaczej caly
        // wezel bibliotek zewnetrznych wygladalby jak kod czekajacy na decyzje.
        if (svc.relOf(file) == null) return
        when (svc.ownerOf(file)) {
            Owner.AI -> if (svc.showAiIcons) data.setIcon(TurfIcons.AI)
            Owner.NONE -> data.forcedTextForeground = DIM
            Owner.HUMAN -> {}
        }
    }
}
