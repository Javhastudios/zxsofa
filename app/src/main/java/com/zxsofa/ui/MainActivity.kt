package com.zxsofa.ui

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.zxsofa.data.Game
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZXSofaTheme {
                MainContent(vm, onPlay = { game, resume -> play(game, resume) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver de una partida: "jugado por última vez", partida guardada, etc.
        lifecycleScope.launch { vm.repo.refresh() }
    }

    private fun play(game: Game, resume: Boolean) {
        startActivity(EmulatorActivity.intent(this, game.id, resume))
    }
}

@Composable
private fun MainContent(vm: LibraryViewModel, onPlay: (Game, Boolean) -> Unit) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    val pickGames = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importUris(uris)
    }
    val openPicker: () -> Unit = {
        try {
            pickGames.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            // Muchas Android TV no tienen selector de archivos: ofrecemos la vía Wi-Fi
            vm.message("Este dispositivo no tiene selector de archivos. Usa «Desde otro dispositivo».")
            vm.navigate(Screen.WifiImport)
        }
    }

    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = vm.current) {
                Screen.Catalog -> CatalogScreen(vm, onAddFile = openPicker, onOpen = { vm.navigate(Screen.Detail(it.id)) })
                is Screen.Detail -> DetailScreen(vm, s.gameId, onPlay = onPlay)
                is Screen.Edit -> EditScreen(vm, s.gameId)
                is Screen.CoverSearch -> CoverSearchScreen(vm, s.gameId)
                Screen.Settings -> SettingsScreen(vm)
                Screen.WifiImport -> WifiImportScreen(vm)
            }
        }
    }
}
