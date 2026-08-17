package pl.kznd4niel.turf

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import pl.kznd4niel.turf.ai.AiFoldStyle
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

    /** To samo dla katalogow - czytane wylacznie w trybie pakietowym. */
    @Volatile
    private var dirLookup: Map<String, Owner> = emptyMap()

    /** Wpisy plikowe z flaga `override` - jedyne, ktore przebijaja wlasnosc pakietu. */
    @Volatile
    private var overrideLookup: Map<String, Owner> = emptyMap()

    @Volatile
    var mode: TurfMode = TurfMode.FILE
        private set

    @Volatile
    private var compiledPatterns: List<Pair<Regex, Owner>> = emptyList()

    @Volatile
    private var pendingRequests: List<EditRequest> = emptyList()

    private val listeners = CopyOnWriteArrayList<Runnable>()

    /** Czy drzewo pokazuje czerwone ikony przy plikach AI. Przelaczane skrotem. */
    @Volatile
    var showAiIcons: Boolean = true
        private set

    fun toggleAiIcons(): Boolean {
        showAiIcons = !showAiIcons
        fireChanged()
        return showAiIcons
    }

    /** Czy bloki oznaczone znacznikiem AI maja byc zwiniete zaraz po otwarciu pliku. */
    @Volatile
    var foldAiBlocks: Boolean = true
        private set

    fun toggleAiFolds(): Boolean {
        foldAiBlocks = !foldAiBlocks
        fireChanged()
        return foldAiBlocks
    }

    /** Wyglad zwinietego bloku. Sprawa czysto wizualna, wiec nie manifest, tylko IDE. */
    @Volatile
    var foldStyle: AiFoldStyle =
        AiFoldStyle.from(PropertiesComponent.getInstance(project).getValue(FOLD_STYLE_KEY))
        private set

    fun toggleFoldStyle(): AiFoldStyle {
        val next = if (foldStyle == AiFoldStyle.TEXT) AiFoldStyle.COUNTER else AiFoldStyle.TEXT
        foldStyle = next
        PropertiesComponent.getInstance(project).setValue(FOLD_STYLE_KEY, next.id)
        fireChanged()
        return next
    }

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
        mode = TurfMode.from(data.mode)
        lookup = data.files.entries.associate { (k, v) -> k.lowercase() to Owner.from(v.owner) }
        dirLookup = data.dirs.entries.associate { (k, v) -> k.lowercase() to Owner.from(v.owner) }
        overrideLookup = data.files.entries
            .filter { (_, v) -> v.override == true }
            .associate { (k, v) -> k.lowercase() to Owner.from(v.owner) }
        compiledPatterns = data.patterns.map { globToRegex(it.glob) to Owner.from(it.owner) }
        pendingRequests = readRequests()
        fireChanged()
    }

    /** Tryb wybiera uzytkownik, ale zapisany jest w manifescie - MCP musi orzekac tak samo. */
    fun setMode(next: TurfMode) {
        if (mode == next) return
        val m = manifest
        m.mode = next.id
        writeManifest(m)
        reload()
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
        val rel = relOf(file) ?: return Owner.NONE
        if (file.isDirectory) {
            // Katalog ma wlasciciela tylko wtedy, kiedy w ogole jest jednostka wlasnosci.
            return if (mode == TurfMode.PACKAGE) nearestDir(rel) ?: Owner.NONE else Owner.NONE
        }
        return ownerOfRel(rel)
    }

    fun ownerOfRel(rel: String): Owner {
        val direct = when (mode) {
            // Wyjatek plikowy bije pakiet - inaczej jeden wspolny plik w cudzym pakiecie
            // wymagalby rozbijania calego pakietu.
            TurfMode.PACKAGE -> overrideLookup[rel.lowercase()] ?: nearestDir(parentDir(rel))
            TurfMode.FILE -> lookup[rel.lowercase()]
        }
        direct?.let { return it }
        // Ostatni pasujacy wzorzec wygrywa - tak samo jak po stronie MCP.
        var hit: Owner? = null
        for ((re, owner) in compiledPatterns) if (re.matches(rel)) hit = owner
        return hit ?: Owner.NONE
    }

    /** Najblizszy oznaczony katalog w gore. Zagniezdzony pakiet przykrywa nadrzedny. */
    private fun nearestDir(dir: String): Owner? {
        var d = dir
        while (true) {
            dirLookup[d.lowercase()]?.let { return it }
            if (d.isEmpty()) return null
            d = parentDir(d)
        }
    }

    /** Katalog, ktoremu w trybie pakietowym nadaje sie wlasnosc po kliknieciu w ten plik. */
    fun packageOf(file: VirtualFile): String? {
        val rel = relOf(file) ?: return null
        return if (file.isDirectory) rel else parentDir(rel)
    }

    // ---------- zapis wlasnosci ----------

    /**
     * @param fileOverride w trybie pakietowym oznacza sam plik jako wyjatek od pakietu,
     *        zamiast oznaczac caly pakiet. W trybie plikowym nie zmienia niczego.
     */
    fun setOwner(files: Collection<VirtualFile>, owner: Owner, fileOverride: Boolean = false) {
        val m = manifest
        val packageWide = mode == TurfMode.PACKAGE && !fileOverride
        val target = if (packageWide) m.dirs else m.files

        val keys = LinkedHashSet<String>()
        if (packageWide) {
            // Jeden wpis na pakiet, bez schodzenia w glab: po to sa pakiety, zeby caly
            // podkatalog szedl jednym oznaczeniem. Zagniezdzony wpis zostaje i dalej
            // przykrywa nadrzedny, bo wygrywa najblizszy.
            files.forEach { f -> packageOf(f)?.let(keys::add) }
        } else {
            files.forEach { f -> collectRels(f, keys) }
        }
        if (keys.isEmpty()) return

        keys.forEach {
            putEntry(target, it, owner, "IDE", override = mode == TurfMode.PACKAGE && fileOverride)
        }
        writeManifest(m)
        reload()
    }

    /**
     * Plik utworzony w IDE jest Twoj od pierwszej sekundy - bez tego nowa klasa ladowala
     * jako "niczyj", czyli wygladala tak samo jak plik czekajacy na decyzje.
     *
     * Dziala tylko w trybie plikowym: w pakietowym wpis pliku i tak nie bylby czytany,
     * a plik dziedziczy wlasnosc pakietu, w ktorym powstal.
     */
    fun claimCreated(rels: Collection<String>) {
        if (mode != TurfMode.FILE) return
        val m = manifest
        var changed = false
        for (rel in rels) {
            if (isIgnored(rel)) continue
            // Istniejacego wpisu nie ruszamy. Dotyczy to zwlaszcza rezerwacji zrobionej
            // przez turf_check: AI zaklepala te sciezke i ma prawo ja utworzyc.
            if (m.files.keys.any { it.equals(rel, ignoreCase = true) }) continue
            putEntry(m.files, rel, Owner.HUMAN, "IDE (nowy plik)")
            changed = true
        }
        if (!changed) return
        writeManifest(m)
        reload()
    }

    private fun putEntry(
        map: MutableMap<String, FileEntry>,
        key: String,
        owner: Owner,
        by: String,
        override: Boolean = false,
    ) {
        // Klucz moze juz istniec w innej wielkosci liter - inaczej zrobilyby sie duplikaty.
        val existing = map.keys.firstOrNull { it.equals(key, ignoreCase = true) }
        if (existing != null) map.remove(existing)
        if (owner == Owner.NONE) return
        val e = FileEntry()
        e.owner = owner.id
        e.since = Instant.now().toString()
        e.by = by
        if (override) e.override = true
        map[key] = e
    }

    private fun collectRels(file: VirtualFile, out: MutableCollection<String>) {
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

    fun isManifestPath(path: String): Boolean {
        val rel = relOf(path) ?: return false
        return rel.startsWith(".turf/")
    }

    /**
     * Sciezki, ktorych nikt nie oznacza recznie i ktore nie maja trafiac do manifestu.
     * Bez tego samo otwarcie projektu zaklepywaloby polowe `.idea` jako Twoja wlasnosc.
     */
    fun isIgnored(rel: String): Boolean {
        val r = rel.lowercase()
        return IGNORED.any { r.startsWith(it) || r.contains("/$it") }
    }

    private companion object {
        const val FOLD_STYLE_KEY = "turf.ai.foldStyle"

        val IGNORED = listOf(
            ".turf/", ".git/", ".idea/", ".gradle/",
            "build/", "out/", "target/", "node_modules/",
        )
    }
}
