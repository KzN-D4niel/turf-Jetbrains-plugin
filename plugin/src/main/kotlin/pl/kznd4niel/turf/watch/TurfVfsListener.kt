package pl.kznd4niel.turf.watch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import pl.kznd4niel.turf.TurfMode
import pl.kznd4niel.turf.TurfService

/**
 * Trzyma stan wtyczki zgodny z dyskiem i zaklepuje pliki tworzone w IDE.
 *
 * Nie ma tu juz wykrywania "wejscia na cudzy teren". Opieralo sie na tym, ze zapis z
 * zewnatrz ma pustego requestora, a to zalozenie nie trzymalo sie w praktyce: alarm
 * milczal tam, gdzie mial krzyczec, i odzywal sie przy zwyklym odswiezeniu dysku.
 * Granica jest deklarowana po stronie MCP i to zostaje jedynym miejscem, ktore o niej
 * orzeka - falszywy strażnik byl gorszy niz jego brak.
 *
 * Zostaje to, co dziala na twardych faktach:
 *   - zmiana w `.turf` to zapis manifestu albo wniosku od MCP, wiec przeladowanie,
 *   - utworzenie pliku z niepustym requestorem i nie z odswiezenia to plik zrobiony
 *     w IDE, czyli od pierwszej sekundy Twoj.
 */
class TurfVfsListener : BulkFileListener {

    override fun after(events: MutableList<out VFileEvent>) {
        val projects = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }
        if (projects.isEmpty()) return

        val touchedTurf = events.any { e ->
            projects.any { it.service<TurfService>().isManifestPath(e.path) }
        }
        if (touchedTurf) projects.forEach { it.service<TurfService>().reload() }

        claimCreatedInIde(projects, events)
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
}
