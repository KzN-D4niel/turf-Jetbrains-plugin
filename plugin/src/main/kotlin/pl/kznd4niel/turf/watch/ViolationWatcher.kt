package pl.kznd4niel.turf.watch

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import pl.kznd4niel.turf.TurfMode
import pl.kznd4niel.turf.TurfService
import pl.kznd4niel.turf.Owner
import pl.kznd4niel.turf.Violation

/**
 * Wykrywa zapisy do plikow, ktore nie naleza do AI, i zaklepuje pliki tworzone w IDE.
 *
 * Rozroznienie opiera sie na tym, kto zglosil zdarzenie. Zapis z edytora IDE ma
 * niepustego requestora (dokument, ktory zapisuje). Zmiana wykryta odswiezeniem
 * systemu plikow - czyli zapis narzedziem z zewnatrz, np. Claude Code - ma requestora
 * pustego. To jest heurystyka, nie dowod: edycja tego samego pliku innym edytorem
 * zewnetrznym tez tu wpadnie.
 *
 * MCP niczego nie zapisuje, wiec to jest jedyne miejsce, w ktorym granica jest
 * naprawde egzekwowana, a nie tylko deklarowana.
 *
 * Ta sama heurystyka dziala w druga strone: utworzenie pliku z niepustym requestorem to
 * plik, ktory zrobiles w IDE, wiec od razu staje sie Twoj.
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

        claimCreatedInIde(projects, events)

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

    /**
     * Nowy plik zrobiony w IDE dostaje wlasnosc czlowieka. Bez tego swieza klasa
     * ladowala jako "niczyj" - wygladala jak plik czekajacy na decyzje, mimo ze
     * decyzja wlasnie zapadla przez samo jej utworzenie.
     *
     * Nie przechodzi tedy nic, co powstalo z zewnatrz (pusty requestor - tym idzie
     * Claude Code) ani z odswiezenia dysku, wiec git checkout niczego nie przejmuje.
     */
    private fun claimCreatedInIde(projects: List<Project>, events: List<VFileEvent>) {
        val perProject = LinkedHashMap<Project, MutableList<String>>()
        for (e in events) {
            if (e !is VFileCreateEvent || e.isDirectory) continue
            if (e.requestor == null || e.isFromRefresh) continue
            val project = projects.firstOrNull { owns(it, e.path) } ?: continue
            val svc = project.service<TurfService>()
            if (svc.mode != TurfMode.FILE) continue
            val rel = svc.relOf(e.path) ?: continue
            if (svc.isIgnored(rel)) continue
            perProject.getOrPut(project) { ArrayList() }.add(rel)
        }
        if (perProject.isEmpty()) return

        // Zapis manifestu konczy sie odswiezeniem VFS, wiec nie robimy tego w srodku
        // obslugi zdarzenia VFS.
        ApplicationManager.getApplication().invokeLater {
            for ((project, rels) in perProject) {
                if (project.isDisposed) continue
                project.service<TurfService>().claimCreated(rels)
            }
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
