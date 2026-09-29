package ccg

// Bundled content: the shipped games and the combat preset library are
// the JSON files under `content/`, read from the classpath. There is no Kotlin
// copy of them. The APK carries `content/` at its root; test.sh and
// build-agent.sh put a copy on the JVM classpath. One loader for every
// platform, and it needs nothing but the stdlib.
//
// `content/index.json` names the games: `builtin` ones the Creator keeps up to
// date (refresh / UPDATE), and `samples` it adds once.

object Bundled {
    /** The text of `content/<path>`. Missing content is a packaging bug, not a
     *  content error, so this throws rather than reporting. */
    fun text(path: String): String =
        Bundled::class.java.getResourceAsStream("/content/$path")?.use { it.readBytes().decodeToString() }
            ?: error("content/$path is not on the classpath (build.sh, test.sh and build-agent.sh each put content/ there)")

    /** The index's lists, each a list of file names without `.json`. */
    val index: Map<String, List<String>> by lazy {
        Json.parse(text("index.json")).obj().mapValues { (_, v) -> v.arr().map { it.str() } }
    }

    private fun games(key: String): List<GameDoc> = index[key].orEmpty().map { gameDocFromJson(text("$it.json")) }

    val builtin: List<GameDoc> by lazy { games("builtin") }
    val samples: List<GameDoc> by lazy { games("samples") }
    val all: List<GameDoc> get() = builtin + samples

    /** The bundled game with this [GameDoc.id]. */
    fun game(id: String): GameDoc = all.firstOrNull { it.id == id } ?: error("no bundled game with id $id")

    /** The combat preset library, by name. */
    val combatPresets: Map<String, Combat> by lazy { combatPresetsOf(text("combat/presets.json")) }
}
