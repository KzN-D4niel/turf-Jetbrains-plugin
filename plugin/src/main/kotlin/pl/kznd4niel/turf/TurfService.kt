package pl.kznd4niel.turf

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class TurfService(private val project: Project) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    @Volatile
    private var manifest: ManifestData = ManifestData()

    /** Klucze zawsze malymi literami - na Windowsie sciezki roznia sie wielkoscia liter. */
    @Volatile
    private var lookup: Map<String, Owner> = emptyMap()

    @Volatile
    private var compiledPatterns: List<Pair<Regex, Owner>> = emptyList()

    @Volatile
    private var pendingRequests: List<EditRequest> = emptyList()

    private val violationList = CopyOnWriteArrayList<Violation>()

    private val listeners = CopyOnWriteArrayList<Runnable>()

    @Volatile
    var viewMode: ViewMode = ViewMode.BOTH
        private set

    fun cycleViewMode(): ViewMode {
        viewMode = viewMode.next()
        fireChanged()
        return viewMode
    }

    val violations: List<Violation> get() = violationList.toList()

    fun addListener(r: Runnable) = listeners.add(r)
    fun removeListener(r: Runnable) = listeners.remove(r)

    private fun fireChanged() {
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach {
                runCatching { it.run() }.onFailure { e -> thisLogger().warn(e) }
            }
        }
    }

    // ---------- sciezki ----------

    fun root(): Path? = project.basePath?.let { Path.of(it) }

    private fun manifestFile(): Path? = root()?.resolve(".turf")?.resolve("ownership.json")

    private fun requestsDir(): Path? = root()?.resolve(".turf")?.resolve("requests")

    fun relOf(file: VirtualFile): String? {
        val base = project.basePath ?: return null
        val p = file.path
        if (!p.startsWith(base, ignoreCase = true)) return null
        return p.substring(base.length).trimStart('/', '\\').replace('\\', '/')
    }

    fun relOf(path: String): String? {
        val base = project.basePath ?: return null
        if (!path.startsWith(base, ignoreCase = true)) return null
        return path.substring(base.length).trimStart('/', '\\').replace('\\', '/')
    }

    // ---------- odczyt ----------

    fun reload() {
        val mf = manifestFile()
        val data = if (mf != null && Files.exists(mf)) {
            runCatching { gson.fromJson(Files.readString(mf), ManifestData::class.java) }
                .onFailure { thisLogger().warn("Nie moge sparsowac manifestu", it) }
                .getOrNull() ?: ManifestData()
        } else ManifestData()

        manifest = data
        lookup = data.files.entries.associate { (k, v) -> k.lowercase() to Owner.from(v.owner) }
        compiledPatterns = data.patterns.map { globToRegex(it.glob) to Owner.from(it.owner) }
        pendingRequests = readRequests()
        fireChanged()
    }

    private fun readRequests(): List<EditRequest> {
        val d = requestsDir() ?: return emptyList()
        if (!Files.isDirectory(d)) return emptyList()
        return runCatching {
            Files.list(d).use { s ->
                s.filter { it.fileName.toString().endsWith(".json") }
                    .map { p ->
                        runCatching { gson.fromJson(Files.readString(p), EditRequest::class.java) }
                            .getOrNull()
                    }
                    .toList()
                    .filterNotNull()
                    .sortedBy { it.createdAt }
            }
        }.getOrDefault(emptyList())
    }

    fun requests(): List<EditRequest> = pendingRequests

    fun ownerOf(file: VirtualFile): Owner {
        if (file.isDirectory) return Owner.NONE
        val rel = relOf(file) ?: return Owner.NONE
        return ownerOfRel(rel)
    }

    fun ownerOfRel(rel: String): Owner {
        lookup[rel.lowercase()]?.let { return it }
        // Ostatni pasujacy wzorzec wygrywa - tak samo jak po stronie MCP.
        var hit: Owner? = null
        for ((re, owner) in compiledPatterns) if (re.matches(rel)) hit = owner
        return hit ?: Owner.NONE
    }

    // ---------- zapis wlasnosci ----------

    fun setOwner(files: Collection<VirtualFile>, owner: Owner) {
        val rels = ArrayList<String>()
        files.forEach { collectRels(it, rels) }
        if (rels.isEmpty()) return

        val m = manifest
        for (rel in rels) {
            // Klucz moze juz istniec w innej wielkosci liter - inaczej zrobilyby sie duplikaty.
            val existing = m.files.keys.firstOrNull { it.equals(rel, ignoreCase = true) }
            if (owner == Owner.NONE) {
                if (existing != null) m.files.remove(existing)
            } else {
                val e = FileEntry()
                e.owner = owner.id
                e.since = Instant.now().toString()
                e.by = "IDE"
                if (existing != null) m.files.remove(existing)
                m.files[rel] = e
            }
        }
        writeManifest(m)
        reload()
    }

    private fun collectRels(file: VirtualFile, out: MutableList<String>) {
        if (file.isDirectory) {
            file.children?.forEach { collectRels(it, out) }
        } else {
            relOf(file)?.let(out::add)
        }
    }

    private fun writeManifest(m: ManifestData) {
        val mf = manifestFile() ?: return
        runCatching {
            Files.createDirectories(mf.parent)
            val tmp = mf.resolveSibling(mf.fileName.toString() + ".tmp")
            Files.writeString(tmp, gson.toJson(m) + "\n")
            Files.move(tmp, mf, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(mf)
        }.onFailure { thisLogger().warn("Nie moge zapisac manifestu", it) }
    }

    // ---------- wnioski ----------

    /** @return null gdy przyjeto, inaczej powod odmowy. */
    fun accept(req: EditRequest): String? {
        val base = project.basePath ?: return "Projekt bez katalogu bazowego."
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$base/${req.path}")
            ?: return "Nie ma pliku ${req.path}."
        val doc = FileDocumentManager.getInstance().getDocument(vf)
            ?: return "Nie moge otworzyc dokumentu ${req.path}."

        for (e in req.edits) {
            if (e.lineStart < 1 || e.lineEnd > doc.lineCount) {
                return "Zakres ${e.lineStart}-${e.lineEnd} wychodzi poza plik (${doc.lineCount} linii)."
            }
            val old = e.oldText ?: continue
            val cur = doc.getText(
                com.intellij.openapi.util.TextRange(
                    doc.getLineStartOffset(e.lineStart - 1),
                    doc.getLineEndOffset(e.lineEnd - 1)
                )
            )
            if (cur != old) {
                return "Plik zmienil sie od zlozenia wniosku (linie ${e.lineStart}-${e.lineEnd}). " +
                    "Odrzuc go i popros AI o nowy."
            }
        }

        WriteCommandAction.runWriteCommandAction(project, "Turf: Przyjmij Wniosek", null, {
            // Od konca, zeby wczesniejsze zakresy nie przesunely sie po edycji.
            for (e in req.edits.sortedByDescending { it.lineStart }) {
                val start = doc.getLineStartOffset(e.lineStart - 1)
                val end = doc.getLineEndOffset(e.lineEnd - 1)
                doc.replaceString(start, end, e.replacement)
            }
            FileDocumentManager.getInstance().saveDocument(doc)
        })

        decide(req, "accepted", null)
        return null
    }

    fun reject(req: EditRequest, note: String?) = decide(req, "rejected", note)

    private fun decide(req: EditRequest, status: String, note: String?) {
        val d = requestsDir() ?: return
        val f = d.resolve(req.id + ".json")
        req.status = status
        req.decidedAt = Instant.now().toString()
        req.note = note
        runCatching { Files.writeString(f, gson.toJson(req) + "\n") }
            .onFailure { thisLogger().warn("Nie moge zapisac decyzji", it) }
        reload()
    }

    fun deleteDecided() {
        val d = requestsDir() ?: return
        if (!Files.isDirectory(d)) return
        pendingRequests.filter { it.status != "pending" }.forEach {
            runCatching { Files.deleteIfExists(d.resolve(it.id + ".json")) }
        }
        reload()
    }

    // ---------- naruszenia ----------

    fun addViolation(v: Violation) {
        violationList.add(0, v)
        while (violationList.size > 200) violationList.removeAt(violationList.size - 1)
        fireChanged()
    }

    fun clearViolations() {
        violationList.clear()
        fireChanged()
    }

    fun isManifestPath(path: String): Boolean {
        val rel = relOf(path) ?: return false
        return rel.startsWith(".turf/")
    }
}
