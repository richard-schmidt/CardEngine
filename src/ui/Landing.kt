package ccgui

// The Games landing's decisions.

/** A carousel page: each game, then one new-game page, always last. */
sealed interface LandingPage {
    data class Game(val id: String) : LandingPage
    data object New : LandingPage
}

fun landingPages(gameIds: List<String>): List<LandingPage> =
    gameIds.map { LandingPage.Game(it) } + LandingPage.New

/** Opens on the game open in the Creator. */
fun landingStartPage(gameIds: List<String>, openId: String?): Int =
    gameIds.indexOf(openId).coerceAtLeast(0)

sealed interface LandingIntent {
    data class Center(val page: Int) : LandingIntent
    data class Edit(val id: String) : LandingIntent
    data object None : LandingIntent
}

/** An off-centre page is centred first; the centred game opens; the centred New page's body does nothing. */
fun landingBodyTap(pages: List<LandingPage>, page: Int, current: Int): LandingIntent {
    val p = pages.getOrNull(page) ?: return LandingIntent.None
    if (page != current) return LandingIntent.Center(page)
    return when (p) {
        is LandingPage.Game -> LandingIntent.Edit(p.id)
        LandingPage.New -> LandingIntent.None
    }
}

/** The first game not present before, or null. */
fun landingPageAfterImport(before: List<String>, after: List<String>): Int? {
    val known = before.toSet()
    val i = after.indexOfFirst { it !in known }
    return if (i < 0) null else i
}

enum class BundleState { MISSING, STALE, CURRENT }

fun bundleState(savedVersion: Int?, bundledVersion: Int): BundleState = when {
    savedVersion == null -> BundleState.MISSING
    savedVersion < bundledVersion -> BundleState.STALE
    else -> BundleState.CURRENT
}
