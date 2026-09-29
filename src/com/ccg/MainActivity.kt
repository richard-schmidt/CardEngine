package com.ccg

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import ccg.GameDoc
import ccgui.Nav
import ccgui.decodeNav
import ccgui.encode
import kotlinx.coroutines.delay
import java.io.File

// ---------------------------------------------------------------------------
// The app shell. The Creator never persists on navigation: the working copy
// lives in the ViewModel until Save.
//
// The one automatic write is a CRASH SCRATCH (games/.autosave/<id>.json), never
// the named file, never loaded silently, written on onStop() (leaving the
// foreground) and on a short debounce while editing. A force-stop inside the
// debounce window is the one case nothing catches. On launch, a scratch newer
// than the saved file is offered for restore.
// ---------------------------------------------------------------------------

class MainActivity : ComponentActivity() {

    private lateinit var store: GameStore
    private val vm: CreatorViewModel by lazy {
        ViewModelProvider(this)[CreatorViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GameStore(
            dir = File(filesDir, "games"),
            legacyFile = File(filesDir, "bundle.json"),
        )
        setContent {
            CgTheme {
                // ONE shell with one root, the Shelf.
                // Its navigation stack is saved as a string, so a process
                // death comes back to the same screen.
                var navText by rememberSaveable { mutableStateOf(Nav.SHELF.encode()) }
                App(vm, store, decodeNav(navText)) { navText = it.encode() }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the foreground: persist the working copy to the crash scratch
        // (never the named file) so it survives a later process kill.
        if (vm.dirty) store.saveScratch(vm.game)
    }
}

@Composable
private fun App(vm: CreatorViewModel, store: GameStore, nav: Nav, onNav: (Nav) -> Unit) {
    var restore by remember { mutableStateOf<Pair<String, GameDoc>?>(null) }

    LaunchedEffect(Unit) {
        // The game the restored stack is in, else the first. A stack naming a
        // game that is gone falls back to the Shelf.
        val games = store.list()
        val target = nav.gameId?.let { id -> games.firstOrNull { it.id == id } }
            ?: run { if (nav.gameId != null) onNav(Nav.SHELF); games.firstOrNull() }
        if (target == null) {
            store.save(vm.game)          // first run: persist the seed
            return@LaunchedEffect
        }
        val fileDoc = store.load(target.id) ?: run { store.save(vm.game); return@LaunchedEffect }
        if (store.hasUnsavedScratch(target.id)) restore = target.id to fileDoc
        else vm.loadGame(fileDoc)
        store.loadParked(target.id)?.let(vm::adoptParked)
    }

    // Debounced crash scratch: ~0.7s after edits settle, only while dirty.
    // Backs up the onStop() write for an in-foreground crash.
    LaunchedEffect(vm.game) {
        delay(700L)
        if (vm.dirty) store.saveScratch(vm.game)
    }

    CreatorScreen(vm = vm, store = store, nav = nav, onNav = onNav)

    restore?.let { (id, fileDoc) ->
        val scratch = store.loadScratch(id)
        fun discard() { store.clearScratch(id); vm.loadGame(fileDoc); store.loadParked(id)?.let(vm::adoptParked); restore = null }
        AlertDialog(
            onDismissRequest = { discard() },
            title = { Text("Restore unsaved work in “${scratch?.name ?: fileDoc.name}”?", color = Cg.ink) },
            text = { Text("The app closed before the last changes were saved.", color = Cg.muted) },
            confirmButton = {
                TextButton(onClick = {
                    if (scratch != null) vm.restoreWorking(working = scratch, baseline = fileDoc)
                    else vm.loadGame(fileDoc)
                    store.loadParked(id)?.let(vm::adoptParked)
                    restore = null
                }) { Text("Restore", color = Cg.accentLight) }
            },
            dismissButton = {
                TextButton(onClick = { discard() }) { Text("Discard", color = Cg.danger) }
            },
            containerColor = Cg.surface,
        )
    }
}
