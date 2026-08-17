package pl.kznd4niel.turf.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import pl.kznd4niel.turf.TurfMode
import pl.kznd4niel.turf.TurfService
import pl.kznd4niel.turf.Owner
import java.awt.Component
import java.awt.event.MouseEvent
import com.intellij.util.Consumer

private const val WIDGET_ID = "Turf.Owner"

class OwnerWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "Turf: wlasciciel pliku"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = OwnerWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class OwnerWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null

    // Trzymane w polu, bo ::update tworzyloby przy kazdym uzyciu inny obiekt
    // i removeListener nie mialby czego usunac.
    private val onChanged = Runnable { update() }

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val conn = project.messageBus.connect(this)
        conn.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = update()
            }
        )
        project.service<TurfService>().addListener(onChanged)
        update()
    }

    override fun dispose() {
        if (!project.isDisposed) {
            project.service<TurfService>().removeListener(onChanged)
        }
        statusBar = null
    }

    private fun update() {
        statusBar?.updateWidget(WIDGET_ID)
    }

    private fun current(): Owner? {
        if (project.isDisposed) return null
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null
        return project.service<TurfService>().ownerOf(file)
    }

    private fun mode(): TurfMode? =
        if (project.isDisposed) null else project.service<TurfService>().mode

    override fun getText(): String {
        val czyj = when (current()) {
            Owner.AI -> "AI"
            Owner.HUMAN -> "Ty"
            Owner.SHARED -> "wspolny"
            Owner.NONE -> "niczyj"
            null -> "—"
        }
        val tryb = mode()?.let { " · ${it.label}" } ?: ""
        val ukryte = !project.isDisposed && !project.service<TurfService>().showAiIcons
        return if (ukryte) "Turf: $czyj$tryb  [ikony AI ukryte]" else "Turf: $czyj$tryb"
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String {
        val skad = if (mode() == TurfMode.PACKAGE) {
            "Tryb pakietowy: wlasnosc idzie z najblizszego pakietu, nie z pliku."
        } else {
            "Tryb plikowy: kazdy plik ma swojego wlasciciela."
        }
        val czyj = when (current()) {
            Owner.AI -> "Plik nalezy do AI. Ty mozesz go edytowac, ale to jej terytorium."
            Owner.HUMAN -> "Twoj plik. AI nie ma prawa zapisu, moze tylko zlozyc wniosek na 3 linijki."
            Owner.SHARED -> "Teren wspolny. AI moze dopisywac drobiazgi bez wniosku, " +
                "ale ma zostawiac adnotacje - kod docelowo jest Twoj."
            Owner.NONE -> "Plik nieoznaczony. AI nie ma prawa zapisu ani wniosku - oznacz go."
            null -> "Brak otwartego pliku."
        }
        return "$czyj\n$skad"
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? = null
}
