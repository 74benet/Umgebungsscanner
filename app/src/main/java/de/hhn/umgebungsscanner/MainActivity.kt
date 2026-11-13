package de.hhn.umgebungsscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.hhn.umgebungsscanner.challenge.ChallengeScreen
import de.hhn.umgebungsscanner.challenge.ChallengeViewModel
import de.hhn.umgebungsscanner.scanner.HomeScreen
import de.hhn.umgebungsscanner.scanner.ScannerViewModel
import de.hhn.umgebungsscanner.settings.SettingsDialog
import de.hhn.umgebungsscanner.ui.theme.UmgebungsscannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UmgebungsscannerTheme {
                App()
            }
        }
    }
}

private enum class Screen { HOME, CHALLENGE }

@Composable
private fun App(
    scannerViewModel: ScannerViewModel = viewModel(),
    challengeViewModel: ChallengeViewModel = viewModel(),
) {
    // rememberSaveable: der aktuelle Screen übersteht das Drehen
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val scannerState by scannerViewModel.state.collectAsStateWithLifecycle()
    val challengeState by challengeViewModel.ui.collectAsStateWithLifecycle()
    val settings by challengeViewModel.settings.collectAsStateWithLifecycle()
    val bestTime by challengeViewModel.bestTime.collectAsStateWithLifecycle()

    when (screen) {
        Screen.HOME -> HomeScreen(
            state = scannerState,
            viewModel = scannerViewModel,
            matrikel = settings.matrikel,
            sensorStatus = challengeState.lastResult ?: "Nicht gestartet",
            onOpenChallenge = { screen = Screen.CHALLENGE },
            onOpenSettings = { showSettings = true },
        )

        Screen.CHALLENGE -> {
            BackHandler {
                challengeViewModel.stop()
                screen = Screen.HOME
            }
            ChallengeScreen(
                ui = challengeState,
                bestTime = bestTime,
                matrikel = settings.matrikel,
                viewModel = challengeViewModel,
                onExit = { screen = Screen.HOME },
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            bestTime = bestTime,
            onSave = challengeViewModel::saveSettings,
            onResetBestTime = challengeViewModel::resetBestTime,
            onDismiss = { showSettings = false },
        )
    }
}
