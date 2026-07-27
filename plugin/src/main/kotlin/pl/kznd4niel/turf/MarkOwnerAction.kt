package pl.kznd4niel.turf

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service

abstract class MarkOwnerAction(private val owner: Owner) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && !e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY).isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return
        project.service<TurfService>().setOwner(files.toList(), owner)
        ProjectView.getInstance(project).refresh()
    }
}

class MarkAsMineAction : MarkOwnerAction(Owner.HUMAN)

class MarkAsAiAction : MarkOwnerAction(Owner.AI)

class ClearOwnerAction : MarkOwnerAction(Owner.NONE)
