package com.ccg

import android.content.ContentResolver
import android.net.Uri
import ccg.GameDoc
import ccg.withDeclaredCounters
import ccg.gameDocFromJson
import ccg.gameDocToJson
import ccgui.ParkedGame
import ccgui.Scenario
import ccgui.decodeScenario
import ccgui.scenarioFileName
import ccgui.encode
import ccgui.decodeParkedGame
import ccgui.encode
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ---------------------------------------------------------------------------
// Storage. Each game is one file in `games/`, keyed by a STABLE id
// (`<id>.json`), so a rename never orphans a file. Only `save()` writes it; the
// working copy lives in the ViewModel until the user presses Save.
//
// The one automatic write is a debounced CRASH SCRATCH (`games/.autosave/
// <id>.json`), offered for restore after a crash and never loaded silently.
//
// Migration folds in the old single `bundle.json` (kept) and name-keyed files:
// mint an id, rewrite as `<id>.json`, delete the old file.
// ---------------------------------------------------------------------------

/** A game as it appears in the list: its stable id, name, and a cheap
 *  readiness snapshot (card / deck counts, problem count) for the row. */
data class GameMeta(
    val id: String,
    val name: String,
    val cards: Int = 0,
    val decks: Int = 0,
    val sets: Int = 0,
    val problems: Int = 0,
    /** The game's colour, `#rrggbb` (`ccgui.accentOf`). */
    val accent: String = ccgui.DEFAULT_ACCENT,
    /** The card on its box (`ccgui.coverCard`): its name, and its picture
     *  and crop when it has one. */
    val coverName: String? = null,
    val coverArt: String? = null,
    val coverRect: ccg.ArtRect? = null,
)

class GameStore(private val dir: File, private val legacyFile: File? = null) {

    companion object {
        /** Version snapshots kept per game before the oldest are pruned. */
        const val MAX_VERSIONS = 20
    }

    private val scratchDir = File(dir, ".autosave")

    init {
        dir.mkdirs()
        scratchDir.mkdirs()
        migrateLegacyBundle()
        migrateNameKeyedFiles()
    }

    // -- the game files ---------------------------------------------------

    private fun fileFor(id: String): File = File(dir, "$id.json")

    /** Every game, id + name, by name. Unreadable files are skipped, not fatal.
     *  The `.autosave` dir is not a game. */
    fun list(): List<GameMeta> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { f ->
                runCatching { gameDocFromJson(f.readText()) }.getOrNull()?.let { g ->
                    GameMeta(
                        id = g.id.ifEmpty { f.nameWithoutExtension },
                        name = g.name,
                        cards = g.cards.size,
                        decks = g.decks.size,
                        sets = g.sets.size,
                        problems = runCatching { g.problems().size }.getOrDefault(0),
                        accent = ccgui.accentOf(g.copy(id = g.id.ifEmpty { f.nameWithoutExtension })),
                    ).let { m ->
                        val face = ccgui.coverCard(g)?.faces?.firstOrNull()
                        m.copy(coverName = face?.name, coverArt = face?.art, coverRect = face?.artMiniRect ?: face?.artFieldRect)
                    }
                }
            }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun load(id: String): GameDoc? =
        runCatching { gameDocFromJson(fileFor(id).readText()) }.getOrNull()
            ?.let { if (it.id.isEmpty()) it.copy(id = id) else it }
            ?.withCardIds(::newId)?.withDeclaredCounters()

    /** Persist the named file. The caller has already minted `game.id` (none is
     *  a bug -- guard, don't guess). Snapshots the previous on-disk content as a
     *  version, mints missing card ids, declares counter kinds for a game that
     *  never declared any, and clears the crash scratch.
     *
     *  Returns the migrated doc; the caller must adopt it as the working copy,
     *  or ids are re-minted on every Save. */
    fun save(game: GameDoc): GameDoc {
        val g = (if (game.id.isEmpty()) game.copy(id = newId()) else game).withCardIds(::newId).withDeclaredCounters()
        val f = fileFor(g.id)
        if (f.exists()) {
            runCatching {
                // A fresh, not-yet-used timestamp -- File.lastModified()'s
                // resolution (1s on many filesystems) can otherwise collide
                // between two saves close together and silently clobber a
                // distinct prior version.
                var ts = f.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                while (versionFile(g.id, ts).exists()) ts++
                versionFile(g.id, ts).writeText(f.readText())
            }
        }
        // Only prune old versions once the new content is actually down --
        // a failed write (disk full, I/O error) must not still cost a kept
        // version for nothing.
        if (runCatching { f.writeText(gameDocToJson(g)) }.isSuccess) pruneVersions(g.id)
        clearScratch(g.id)
        return g
    }

    fun delete(id: String) {
        runCatching { fileFor(id).delete() }
        runCatching { File(dir, id).deleteRecursively() }   // the game's art + versions dirs
        clearScratch(id)
        unpark(id)
    }

    // -- version history: every Save snapshots the PREVIOUS on-disk content to
    // games/<id>/versions/<savedAt millis>.json, capped at MAX_VERSIONS (oldest
    // pruned). A restore loads into the WORKING copy only, so it is an ordinary
    // edit and itself reversible. ------------------------------------------

    private fun versionsDir(id: String): File = File(File(dir, id), "versions").apply { mkdirs() }
    private fun versionFile(id: String, savedAt: Long): File = File(versionsDir(id), "$savedAt.json")

    /** One entry in a game's version list: when it was current, and its name
     *  at that point (a rename since then is exactly the kind of thing a
     *  version list should let you see). */
    data class GameVersion(val savedAt: Long, val name: String)

    fun versions(id: String): List<GameVersion> =
        versionsDir(id).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { f ->
                f.nameWithoutExtension.toLongOrNull()?.let { ts ->
                    GameVersion(ts, runCatching { gameDocFromJson(f.readText()).name }.getOrDefault("(unreadable)"))
                }
            }
            ?.sortedByDescending { it.savedAt } ?: emptyList()

    fun loadVersion(id: String, savedAt: Long): GameDoc? =
        runCatching { gameDocFromJson(versionFile(id, savedAt).readText()) }.getOrNull()
            // Migrated like every other GameDoc reader (a no-op for snapshots
            // `save()` already migrated).
            ?.withCardIds(::newId)?.withDeclaredCounters()

    private fun pruneVersions(id: String, keep: Int = MAX_VERSIONS) {
        versionsDir(id).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedByDescending { it.nameWithoutExtension.toLongOrNull() ?: 0L }
            ?.drop(keep)
            ?.forEach { runCatching { it.delete() } }
    }

    // -- card art (5.2): sidecar files under games/<id>/art/ ------------

    fun artDir(gameId: String): File = File(File(dir, gameId), "art").apply { mkdirs() }
    fun artFile(gameId: String, name: String): File = File(artDir(gameId), name)

    /** Copy a picked image into the game's art dir. Returns the relative
     *  filename (content hash + extension) or null on failure. */
    fun importArt(gameId: String, uri: Uri, resolver: ContentResolver): String? = runCatching {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val ext = when (resolver.getType(uri)) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            else -> "img"
        }
        val sha = MessageDigest.getInstance("SHA-1").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(16)
        val name = "$sha.$ext"
        artFile(gameId, name).writeBytes(bytes)
        name
    }.getOrNull()

    /** Delete art files no face in `game` references. Called on an explicit Save. */
    fun gcArt(game: GameDoc) {
        if (game.id.isEmpty()) return
        val referenced = game.sets.flatMap { it.cards }.flatMap { it.faces }.mapNotNull { it.art }.toSet()
        artDir(game.id).listFiles()?.forEach { f ->
            if (f.isFile && f.name !in referenced) runCatching { f.delete() }
        }
    }

    // -- export / import (5.5) ----------------------------------------

    /** Write `game` as a self-contained .ceg.zip: `game.json` + `art/<files>`. */
    fun exportBundle(game: GameDoc, uri: Uri, resolver: ContentResolver): Boolean = runCatching {
        resolver.openOutputStream(uri)?.use { os ->
            ZipOutputStream(os).use { zip ->
                zip.putNextEntry(ZipEntry("game.json"))
                zip.write(gameDocToJson(game).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                artDir(game.id).listFiles()?.filter { it.isFile }?.forEach { f ->
                    zip.putNextEntry(ZipEntry("art/${f.name}"))
                    zip.write(f.readBytes())
                    zip.closeEntry()
                }
            }
        } ?: return false
        true
    }.getOrDefault(false)

    fun exportJson(game: GameDoc, uri: Uri, resolver: ContentResolver): Boolean = runCatching {
        resolver.openOutputStream(uri)?.use { it.write(gameDocToJson(game).toByteArray(Charsets.UTF_8)) } ?: return false
        true
    }.getOrDefault(false)

    /** Read a .ceg.zip into a NEW game (fresh id, art restored). Returns the id. */
    fun importBundle(uri: Uri, resolver: ContentResolver): String? = runCatching {
        var jsonText: String? = null
        val art = HashMap<String, ByteArray>()
        resolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val bytes = zip.readBytes()
                    val n = e.name
                    when {
                        n == "game.json" -> jsonText = bytes.toString(Charsets.UTF_8)
                        n.startsWith("art/") && !e.isDirectory && n.length > 4 ->
                            art[n.substring(4)] = bytes
                    }
                    e = zip.nextEntry
                }
            }
        }
        val text = jsonText ?: return null
        val newId = newId()
        fileFor(newId).writeText(gameDocToJson(gameDocFromJson(text).copy(id = newId).withCardIds(::newId).withDeclaredCounters()))
        val ad = artDir(newId)
        art.forEach { (name, bytes) -> runCatching { File(ad, name).writeBytes(bytes) } }
        newId
    }.getOrNull()

    fun importJson(uri: Uri, resolver: ContentResolver): String? = runCatching {
        val text = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        val newId = newId()
        fileFor(newId).writeText(gameDocToJson(gameDocFromJson(text).copy(id = newId).withCardIds(::newId).withDeclaredCounters()))
        newId
    }.getOrNull()

    /** File-safe stem for a suggested export name. */
    fun exportName(name: String): String =
        name.map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else '_' }
            .joinToString("").trim().ifEmpty { "game" }

    fun newId(): String = UUID.randomUUID().toString().take(12)

    /** An unused "New Game", "New Game 2", … for the DISPLAY name only. */
    fun freeName(): String {
        val taken = list().map { it.name }.toSet()
        if ("New Game" !in taken) return "New Game"
        return generateSequence(2) { it + 1 }.map { "New Game $it" }.first { it !in taken }
    }

    // -- crash scratch --------------------------------------------------

    private fun scratchFile(id: String): File = File(scratchDir, "$id.json")

    /** A debounced copy of the working state. Silent; never the real file. */
    fun saveScratch(game: GameDoc) {
        if (game.id.isEmpty()) return
        runCatching { scratchFile(game.id).writeText(gameDocToJson(game)) }
    }

    fun loadScratch(id: String): GameDoc? =
        runCatching { gameDocFromJson(scratchFile(id).readText()) }.getOrNull()

    fun clearScratch(id: String) {
        runCatching { scratchFile(id).delete() }
    }

    /** True when a scratch exists and is newer than the saved file -- i.e. there
     *  is unsaved work from a session that did not end on Save. */
    fun hasUnsavedScratch(id: String): Boolean {
        val s = scratchFile(id)
        if (!s.exists()) return false
        val f = fileFor(id)
        return !f.exists() || s.lastModified() > f.lastModified()
    }

    // -- scenarios: sandbox tables saved by name --
    // `games/<id>/scenarios/<file>.txt`, the `ccgui.Scenario` codec; they go
    // with the game's own folder when it is deleted.
    private fun scenarioDir(gameId: String): File = File(File(dir, gameId), "scenarios")

    /** Save [sc]; one of the same name is replaced. */
    fun saveScenario(gameId: String, sc: Scenario): Boolean = runCatching {
        val d = scenarioDir(gameId).apply { mkdirs() }
        File(d, scenarioFileName(sc.name) + ".txt").writeText(sc.encode())
        true
    }.getOrDefault(false)

    /** The game's scenarios that still read, by file stem, newest first. */
    fun scenarios(gameId: String): List<Pair<String, Scenario>> =
        scenarioDir(gameId).listFiles()?.filter { it.isFile && it.name.endsWith(".txt") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f -> runCatching { decodeScenario(f.readText()) }.getOrNull()?.let { f.nameWithoutExtension to it } }

    fun deleteScenario(gameId: String, stem: String) {
        runCatching { File(scenarioDir(gameId), "$stem.txt").delete() }
    }

    /** Write [text] to a picked document (a scenario's conformance case). */
    fun exportText(text: String, uri: Uri, resolver: ContentResolver): Boolean = runCatching {
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: return false
        true
    }.getOrDefault(false)

    // -- parked games: a game left on the table, per game id --
    // `games/.parked/<id>.txt`, the `ccgui.ParkedGame` codec. What Continue on
    // the Shelf reads, and what survives a process death mid-game.

    private val parkDir = File(dir, ".parked")
    private fun parkFile(id: String): File = File(parkDir, "$id.txt")

    fun park(id: String, game: ParkedGame) {
        if (id.isEmpty()) return
        runCatching { parkDir.mkdirs(); parkFile(id).writeText(game.encode()) }
    }

    fun loadParked(id: String): ParkedGame? =
        runCatching { decodeParkedGame(parkFile(id).readText()) }.getOrNull()

    fun unpark(id: String) {
        runCatching { parkFile(id).delete() }
    }

    /** Every parked game whose file still reads, by game id. */
    fun parked(): Map<String, ParkedGame> =
        parkDir.listFiles()?.filter { it.name.endsWith(".txt") }
            ?.mapNotNull { f -> loadParked(f.nameWithoutExtension)?.let { f.nameWithoutExtension to it } }
            ?.toMap() ?: emptyMap()

    // -- migration ----------------------------------------------------

    private fun migrateLegacyBundle() {
        val legacy = legacyFile ?: return
        if (!legacy.exists()) return
        runCatching {
            val game = gameDocFromJson(legacy.readText())
            val g = if (game.id.isEmpty()) game.copy(id = newId()) else game
            if (!fileFor(g.id).exists()) fileFor(g.id).writeText(gameDocToJson(g))
            legacy.renameTo(File(legacy.parentFile, "bundle.json.migrated"))
        }
    }

    /** Any `.json` whose name is not `<its own id>.json` (the pre-Phase-5
     *  name-keyed files): mint an id if missing, rewrite as `<id>.json`, remove
     *  the old file. */
    private fun migrateNameKeyedFiles() {
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.forEach { f ->
                runCatching {
                    val game = gameDocFromJson(f.readText())
                    val id = game.id.ifEmpty { newId() }
                    val target = fileFor(id)
                    if (f.name == target.name) {
                        if (game.id.isEmpty()) f.writeText(gameDocToJson(game.copy(id = id)))
                    } else if (!target.exists()) {
                        target.writeText(gameDocToJson(game.copy(id = id)))
                        f.delete()
                    }
                }
            }
    }
}
