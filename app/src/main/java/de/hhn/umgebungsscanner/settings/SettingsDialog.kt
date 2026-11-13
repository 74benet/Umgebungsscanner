package de.hhn.umgebungsscanner.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.hhn.umgebungsscanner.challenge.ChallengeConfig
import de.hhn.umgebungsscanner.challenge.formatDe
import de.hhn.umgebungsscanner.data.AppSettings

@Composable
fun SettingsDialog(
    settings: AppSettings,
    bestTime: Float?,
    onSave: (matrikel: String, simulateMissingGyro: Boolean) -> Unit,
    onResetBestTime: () -> Unit,
    onDismiss: () -> Unit,
) {
    var matrikel by rememberSaveable { mutableStateOf(settings.matrikel) }
    var simulateMissingGyro by rememberSaveable { mutableStateOf(settings.simulateMissingGyro) }
    val config = ChallengeConfig.fromMatrikel(matrikel)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen") },
        text = {
            Column {
                OutlinedTextField(
                    value = matrikel,
                    onValueChange = { input -> matrikel = input.filter { it.isDigit() }.take(10) },
                    label = { Text("Matrikelnummer") },
                    singleLine = true,
                    isError = config == null,
                    supportingText = {
                        Text(if (config == null) "Mindestens 3 Ziffern" else "Bestimmt deine Spielwerte")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (config != null) {
                    ValueLine("Haltezeit", "${config.holdSeconds} s")
                    ValueLine("Zielradius", "${config.targetRadiusDp.toInt()} dp")
                    ValueLine("Windstufe", "${config.windLevel}")
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Bestzeit: ${bestTime?.let { "${it.formatDe()} s" } ?: "noch keine"}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onResetBestTime, enabled = bestTime != null) { Text("Zurücksetzen") }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Debug: Sensor fehlt", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Tut so, als hätte das Gerät kein Gyroskop.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = simulateMissingGyro, onCheckedChange = { simulateMissingGyro = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(matrikel, simulateMissingGyro)
                    onDismiss()
                },
                enabled = config != null,
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun ValueLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
