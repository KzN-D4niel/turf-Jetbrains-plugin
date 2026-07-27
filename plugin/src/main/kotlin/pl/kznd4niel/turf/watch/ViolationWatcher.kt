package pl.kznd4niel.turf.watch

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import pl.kznd4niel.turf.TurfService
import pl.kznd4niel.turf.Owner
import pl.kznd4niel.turf.Violation

/**
 * Wykrywa zapisy do plikow, ktore nie naleza do AI.
 *
 * Rozroznienie opiera sie na tym, kto zglosil zdarzenie. Zapis z edytora IDE ma
 * niepustego requestora (dokument, ktory zapisuje). Zmiana wykryta odswiezeniem
 * systemu plikow - czyli zapis narzedziem z zewnatrz, np. Claude Code - ma requestora
 * pustego. To jest heurystyka, nie dowod: edycja tego samego pliku innym edytorem
 * zewnetrznym tez tu wpadnie.
 *
 * MCP niczego nie zapisuje, wiec to jest jedyne miejsce, w ktorym granica jest
 * naprawde egzekwowana, a nie tylko deklarowana.
 */
class ViolationWatcher : BulkFileListener {

    private companion object {
        /** Powyzej tylu zmian naraz to operacja gita, nie edycja. Nie zglaszamy. */
        const val MASS_CHANGE = 20
    }

    override fun after(events: MutableList<out VFileEvent>) {
        val projects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        if (projects.isEmpty()) return

        // Zmiany w .turf to zapisy manifestu albo wnioskow od MCP - przeladuj i wyjdz.
        var touchedTurf = false
        for (e in events) {
            for (p in projects) {
                if (p.service<TurfService>().isManifestPath(e.path)) touchedTurf = true
            }
        }
        if (touchedTurf) {
            projects.forEach { it.service<TurfService>().reload() }
        }

        if (events.size > MASS_CHANGE) return

        for (e in events) {
            if (e !is VFileContentChangeEvent && e !is VFileCreateEvent) continue
            if (e.requestor != null) continue
            val project = projects.firstOrNull { owns(it, e.path) } ?: continue
            val svc = project.service<TurfService>()
            if (svc.isManifestPath(e.path)) continue

            val rel = svc.relOf(e.path) ?: continue
            val owner = svc.ownerOfRel(rel)
            if (owner == Owner.AI) continue

            svc.addViolation(Violation(rel, owner, System.currentTimeMillis()))
            notify(project, rel, owner)
        }
    }

    private fun owns(project: Project, path: String): Boolean {
        val base = project.basePath ?: return false
        return path.startsWith(base, ignoreCase = true)
    }

    private fun notify(project: Project, rel: String, owner: Owner) {
        val czyj = if (owner == Owner.HUMAN) "Twoj plik" else "plik bez wlasciciela"
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Turf")
            .createNotification(
                "Turf: wejscie na cudzy teren",
                "Zapis z zewnatrz do $czyj: $rel",
                NotificationType.WARNING
            )
            .notify(project)
    }
}
