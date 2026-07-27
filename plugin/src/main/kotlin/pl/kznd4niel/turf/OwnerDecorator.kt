package pl.kznd4niel.turf

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.components.service

/**
 * Oznacza w drzewie projektu pliki nalezace do AI - zamiast ikony typu (niebieskie C
 * przy klasie) wchodzi czerwone AI.
 *
 * Twoje pliki nie dostaja nic. Caly sens jest w tym, zeby cudze rzucalo sie w oczy,
 * a nie zeby przy kazdej pozycji w drzewie wisial jakis dopisek.
 */
class OwnerDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.virtualFile ?: return
        if (file.isDirectory) return
        val project = node.project ?: return
        if (project.isDisposed) return

        if (project.service<TurfService>().ownerOf(file) == Owner.AI) {
            data.setIcon(TurfIcons.AI)
        }
    }
}
