package pl.kznd4niel.turf

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.components.service

/** Dopisuje w drzewie projektu, czyj jest plik. */
class OwnerDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.virtualFile ?: return
        if (file.isDirectory) return
        val project = node.project ?: return
        if (project.isDisposed) return

        when (project.service<TurfService>().ownerOf(file)) {
            Owner.AI -> data.locationString = "AI"
            Owner.HUMAN -> data.locationString = "Ty"
            Owner.NONE -> {}
        }
    }
}
