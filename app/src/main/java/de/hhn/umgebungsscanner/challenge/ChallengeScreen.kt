package de.hhn.umgebungsscanner.challenge

import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SensorsOff
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.hhn.umgebungsscanner.ui.components.IconBubble
import de.hhn.umgebungsscanner.ui.components.SoftCard
import de.hhn.umgebungsscanner.ui.theme.AppTheme
import de.hhn.umgebungsscanner.ui.theme.ValueTextStyle
import kotlinx.coroutines.isActive

private const val BALL_RADIUS_DP = 21f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChallengeScreen(
    ui: ChallengeUiState,
    bestTime: Float?,
    matrikel: String,
    viewModel: ChallengeViewModel,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val playing = ui.state == BalanceState.Playing

    LaunchedEffect(Unit) { viewModel.ensureStarted() }

    // Hochformat festhalten: sonst vertauschen sich beim Drehen die Achsen des Gyroskops
    DisposableEffect(activity) {
        val host = activity ?: return@DisposableEffect onDispose { }
        val previous = host.requestedOrientation
        host.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { host.requestedOrientation = previous }
    }

    // Gyroskop: anmelden, wenn sichtbar und Spiel läuft; abmelden, wenn weg (sonst leert es den Akku)
    val sensorManager = remember { context.getSystemService(SensorManager::class.java) }
    val gyro: Sensor? = remember(ui.state) { viewModel.findGyroscope() }
    DisposableEffect(gyro, playing) {
        if (gyro == null || !playing || sensorManager == null) return@DisposableEffect onDispose { }
        var lastTimestamp = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // dt aus den Zeitstempeln (Nanosekunden) in Sekunden
                val dt = if (lastTimestamp == 0L) 0f else (e.timestamp - lastTimestamp) / 1_000_000_000f
                lastTimestamp = e.timestamp
                viewModel.onGyroscope(omegaX = e.values[0], omegaY = e.values[1], dt = dt)
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    // Spielschleife: ein Schritt pro Frame (Wind, Reibung, Position, Timer)
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                viewModel.step((now - last) / 1_000_000_000f)
                last = now
            }
        }
    }

    val won = ui.state as? BalanceState.Won

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.stop(); onExit() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                title = {
                    Column {
                        Text("Sensor Challenge", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Matr.-Nr. $matrikel",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (ui.state) {
                BalanceState.NoSensor -> NoSensorContent(
                    simulated = viewModel.settings.value.simulateMissingGyro,
                    onBack = { viewModel.stop(); onExit() },
                )

                else -> {
                    val successBg by animateFloatAsState(if (won != null) 1f else 0f, label = "successBg")
                    SoftCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = androidx.compose.ui.graphics.lerp(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            AppTheme.extraColors.successContainer,
                            successBg,
                        ),
                    ) {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AnimatedContent(
                                targetState = won != null,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "header",
                            ) { isWon ->
                                if (isWon && won != null) {
                                    WonHeader(won, ui.config.holdSeconds, bestTime)
                                } else {
                                    PlayingHeader(ui, bestTime)
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                            Arena(
                                snapshot = ui.snapshot,
                                config = ui.config,
                                won = won != null,
                                onArenaMeasured = { radiusDp -> viewModel.setArenaRadius(radiusDp, BALL_RADIUS_DP) },
                            )
                            Spacer(Modifier.height(20.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TiltCard(Icons.Outlined.OpenWith, "Neigung X", ui.snapshot.tiltXDeg, Modifier.weight(1f))
                                TiltCard(Icons.Outlined.UnfoldMore, "Neigung Y", ui.snapshot.tiltYDeg, Modifier.weight(1f))
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    if (won != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.restart() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp),
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null)
                                Spacer(Modifier.size(10.dp))
                                Text("Erneut spielen")
                            }
                            Button(
                                onClick = { viewModel.stop(); onExit() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp),
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                                Spacer(Modifier.size(10.dp))
                                Text("Fertig")
                            }
                        }
                    } else {
                        Button(
                            onClick = { viewModel.stop(); onExit() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Icon(Icons.Filled.Pause, contentDescription = null)
                            Spacer(Modifier.size(12.dp))
                            Text("Challenge beenden")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun PlayingHeader(ui: ChallengeUiState, bestTime: Float?) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            IconBubble(Icons.Outlined.EmojiEvents, size = 50)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Balance Challenge", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Neige dein Gerät und bringe den Ball in den Zielkreis. Halte ihn dort ${ui.config.holdSeconds} Sekunden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        val progress = (ui.snapshot.holdTime / ui.config.holdSeconds).coerceIn(0f, 1f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${ui.snapshot.holdTime.formatDe()} s von ${ui.config.holdSeconds} s",
                style = MaterialTheme.typography.titleSmall,
                color = if (ui.snapshot.inside) AppTheme.extraColors.success else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Bestzeit: ${bestTime?.let { "${it.formatDe()} s" } ?: "–"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = AppTheme.extraColors.success,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Windstufe ${ui.config.windLevel} · Zeit ${ui.snapshot.elapsed.formatDe()} s",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WonHeader(won: BalanceState.Won, holdSeconds: Int, bestTime: Float?) {
    val extra = AppTheme.extraColors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 150.dp, height = 70.dp), contentAlignment = Alignment.Center) {
            // kleine "Jubel"-Striche links und rechts vom Haken
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                val stroke = 3.dp.toPx()
                listOf(-1f, 1f).forEach { side ->
                    drawLine(extra.success, c + Offset(side * 46.dp.toPx(), -18.dp.toPx()), c + Offset(side * 38.dp.toPx(), -8.dp.toPx()), stroke, StrokeCap.Round)
                    drawLine(extra.success, c + Offset(side * 58.dp.toPx(), 2.dp.toPx()), c + Offset(side * 46.dp.toPx(), 6.dp.toPx()), stroke, StrokeCap.Round)
                }
            }
            IconBubble(
                icon = Icons.Filled.Check,
                size = 58,
                background = extra.success,
                tint = extra.onSuccess,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Geschafft!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = extra.onSuccessContainer,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Du hast den Ball $holdSeconds Sekunden im Ziel gehalten.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append("Zeit: ${won.seconds.formatDe()} s")
                if (won.newBest) append(" · Neue Bestzeit!") else bestTime?.let { append(" · Bestzeit: ${it.formatDe()} s") }
            },
            style = MaterialTheme.typography.labelLarge,
            color = extra.success,
        )
    }
}

@Composable
private fun Arena(
    snapshot: BalanceSnapshot,
    config: ChallengeConfig,
    won: Boolean,
    onArenaMeasured: (Float) -> Unit,
) {
    val extra = AppTheme.extraColors
    val outline = MaterialTheme.colorScheme.outline
    val ballColor = MaterialTheme.colorScheme.primary
    // Bei "Geschafft" gleitet der Ball in die Mitte des Ziels
    val pullToCenter by animateFloatAsState(
        targetValue = if (won) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 120f),
        label = "pullToCenter",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 380.dp)
            .aspectRatio(1f),
    ) {
        val radiusDp = maxWidth.value / 2f
        LaunchedEffect(radiusDp) { onArenaMeasured(radiusDp) }

        Canvas(Modifier.fillMaxSize()) {
            val arenaRadius = size.minDimension / 2f
            val targetRadius = config.targetRadiusDp.dp.toPx()
            val ballRadius = BALL_RADIUS_DP.dp.toPx()

            // Spielfläche
            drawCircle(extra.arenaFill, radius = arenaRadius)
            drawCircle(outline, radius = arenaRadius - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))

            // Zielkreis (gestrichelt), voller Rand + Fortschrittsring, solange der Ball drin ist
            drawCircle(extra.targetFill.copy(alpha = if (won) 0f else 0.9f), radius = targetRadius)
            drawCircle(
                color = extra.success,
                radius = targetRadius,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 6.dp.toPx())),
                ),
            )
            if (snapshot.inside && !won) {
                val progress = (snapshot.holdTime / config.holdSeconds).coerceIn(0f, 1f)
                val ringRadius = targetRadius + 6.dp.toPx()
                drawArc(
                    color = extra.success,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = center - Offset(ringRadius, ringRadius),
                    size = androidx.compose.ui.geometry.Size(ringRadius * 2, ringRadius * 2),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // Ball mit weichem Schatten
            val factor = 1f - pullToCenter
            val ballCenter = center + Offset(snapshot.x.dp.toPx() * factor, snapshot.y.dp.toPx() * factor)
            drawCircle(Color.Black.copy(alpha = 0.10f), radius = ballRadius * 1.05f, center = ballCenter + Offset(0f, 3.dp.toPx()))
            drawCircle(ballColor, radius = ballRadius, center = ballCenter)
            drawCircle(Color.White.copy(alpha = 0.18f), radius = ballRadius * 0.45f, center = ballCenter - Offset(ballRadius * 0.3f, ballRadius * 0.3f))
        }
    }
}

@Composable
private fun TiltCard(icon: ImageVector, label: String, degrees: Float, modifier: Modifier = Modifier) {
    SoftCard(modifier = modifier, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBubble(icon, size = 42, background = MaterialTheme.colorScheme.secondaryContainer)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${degrees.formatDe().replace('-', '−')}°", style = ValueTextStyle)
            }
        }
    }
}

@Composable
private fun NoSensorContent(simulated: Boolean, onBack: () -> Unit) {
    SoftCard(modifier = Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.errorContainer) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconBubble(
                Icons.Outlined.SensorsOff,
                size = 64,
                background = MaterialTheme.colorScheme.surfaceContainerLowest,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Kein Gyroskop gefunden",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Die Balance-Challenge braucht ein Gyroskop. Dieses Gerät hat keins, deshalb kann das Spiel nicht starten. " +
                    "Im Emulator lässt es sich unter Extended Controls → Virtual sensors testen.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (simulated) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Simuliert über den Debug-Schalter in den Einstellungen.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = onBack, shape = RoundedCornerShape(50)) { Text("Zurück zur Übersicht") }
        }
    }
}
