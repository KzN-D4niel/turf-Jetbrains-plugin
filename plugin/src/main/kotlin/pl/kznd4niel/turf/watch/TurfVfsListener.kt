package pl.kznd4niel.turf.watch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
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
 *     w IDE, czyli od pierwszej sekundy Twoj,
 *   - zmiana nazwy albo przeniesienie to ta sama tresc pod nowa sciezka, wiec wpis w
 *     manifescie jedzie razem z nia.
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
        followMoves(projects, events)
    }

    /**
     * Zmiana nazwy i przeniesienie: wlasnosc idzie za plikiem.
     *
     * IntelliJ robi refaktor sam - zmiana nazwy pakietu, przeciagniecie klasy do innego
     * katalogu, Move w drzewie. Dla manifestu to byla dotad znikajaca sciezka: klucz
     * zostawal na starej, plik ladowal na nowej i cale oznaczenie przepadalo. Przy
     * przenoszeniu pakietu przepadalo hurtem.
     *
     * Bierzemy oba zdarzenia, bo IDE rozdziela te dwie rzeczy: sama nazwa idzie jako
     * zmiana wlasciwosci pliku, a przeprowadzka do innego rodzica jako `VFileMoveEvent`.
     * Katalogow nie rozwijamy - dzieci nie dostaja wlasnych zdarzen, a serwis dopasowuje
     * klucze prefiksem, wiec caly pakiet przenosi sie jednym wpisem.
     */
    private fun followMoves(projects: List<Project>, events: List<VFileEvent>) {
        val perProject = LinkedHashMap<Project, MutableList<Pair<String, String>>>()
        for (e in events) {
            val (oldPath, file) = when {
                e is VFileMoveEvent -> e.oldPath to e.file
                e is VFilePropertyChangeEvent && e.propertyName == VirtualFile.PROP_NAME ->
                    e.oldPath to e.file
                else -> continue
            }
            val newPath = file.path
            val project = projects.firstOrNull { owns(it, newPath) || owns(it, oldPath) } ?: continue
            val svc = project.service<TurfService>()
            val from = svc.relOf(oldPath) ?: continue
            val to = svc.relOf(newPath) ?: continue
            // Wyprowadzka poza projekt i tak konczy sie brakiem wpisu, wiec zostaje tylko
            // to, co po obu stronach jest sciezka, ktora w ogole sie oznacza.
            if (svc.isIgnored(from) || svc.isIgnored(to)) continue
            perProject.getOrPut(project) { ArrayList() }.add(from to to)
        }
        if (perProject.isEmpty()) return

        // Jak przy zaklepywaniu nowych plikow: zapis manifestu odswieza VFS, wiec nie
        // robimy go w srodku obslugi zdarzenia VFS.
        ApplicationManager.getApplication().invokeLater {
            for ((project, moves) in perProject) {
                if (project.isDisposed) continue
                project.service<TurfService>().relocate(moves)
            }
        }
    }

    /**
     * Nowy plik zrobiony w IDE dostaje wlasnosc czlowieka. Bez tego swieza klasa
     * ladowala jako "niczyj" - wygladala jak plik czekajacy na decyzje, mimo ze
     * decyzja wlasnie zapadla przez samo jej utworzenie.
     *
     * Kopia liczy sie tak samo jak nowy plik: wklejenie klasy w drzewie to Twoja decyzja,
     * a wpis oryginalu i tak zostaje przy oryginale, wiec bez tego kopia ladowalaby jako
     * "niczyj".
     *
     * Nie przechodzi tedy nic, co powstalo z zewnatrz (pusty requestor - tym idzie
     * Claude Code) ani z odswiezenia dysku, wiec git checkout niczego nie przejmuje.
     */
    private fun claimCreatedInIde(projects: List<Project>, events: List<VFileEvent>) {
        val perProject = LinkedHashMap<Project, MutableList<String>>()
        for (e in events) {
            // Katalog pomijamy w obu przypadkach: w trybie plikowym jednostka wlasnosci
            // jest plik, a kopia katalogu przychodzi jednym zdarzeniem na sam katalog.
            val created = when (e) {
                is VFileCreateEvent -> !e.isDirectory
                is VFileCopyEvent -> !e.file.isDirectory
                else -> false
            }
            if (!created) continue
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
