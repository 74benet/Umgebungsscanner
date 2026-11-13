package de.hhn.umgebungsscanner.scanner

import android.Manifest
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.hhn.umgebungsscanner.ui.components.IconBubble
import de.hhn.umgebungsscanner.ui.components.FeatureCard
import de.hhn.umgebungsscanner.ui.components.NoticeCard
import de.hhn.umgebungsscanner.ui.components.SoftCard
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: ScannerUiState,
    viewModel: ScannerViewModel,
    matrikel: String,
    sensorStatus: String,
    onOpenChallenge: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current

    // --- Kamera: erst Recht, dann Kamera ------------------------------------------------------
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        viewModel.onPhotoResult(bitmap) // null bei Abbruch, wird im ViewModel behandelt
    }

    fun launchCamera() {
        try {
            cameraLauncher.launch(null)
        } catch (e: ActivityNotFoundException) {
            viewModel.onCameraUnavailable("Auf diesem Gerät ist keine Kamera-App installiert.")
        } catch (e: SecurityException) {
            // Tritt auf, wenn CAMERA im Manifest steht, aber nicht erteilt ist. Wird vorher geprüft.
            viewModel.onCameraPermissionDenied(permanently = false)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera()
        } else {
            viewModel.onCameraPermissionDenied(permanently = activity.isPermanentlyDenied(Manifest.permission.CAMERA))
        }
    }

    fun startCameraFlow() {
        when {
            !context.hasCameraHardware() ->
                viewModel.onCameraUnavailable("Dieses Gerät hat keine Kamera.")

            context.isGranted(Manifest.permission.CAMERA) -> launchCamera()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // --- Standort: FINE und COARSE gemeinsam anfragen (ab Android 12 kann man "ungefähr" wählen) --
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            viewModel.fetchLocation()
        } else {
            viewModel.onLocationPermissionDenied(permanently = activity.isPermanentlyDenied(*LOCATION_PERMISSIONS))
        }
    }

    fun startLocationFlow() {
        if (context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            context.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            viewModel.fetchLocation()
        } else {
            locationPermissionLauncher.launch(LOCATION_PERMISSIONS)
        }
    }

    // --- "Scan starten": erst Standort, danach Foto ------------------------------------------
    var scanAfterAttempt by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.locationAttempts, scanAfterAttempt) {
        val start = scanAfterAttempt
        if (start != null && state.locationAttempts > start) {
            scanAfterAttempt = null
            startCameraFlow()
        }
    }

    // Nach Rückkehr aus den Einstellungen: Rechte neu prüfen, veraltete Meldungen entfernen
    LifecycleResumeEffect(Unit) {
        viewModel.onResume(
            hasLocationPermission = LOCATION_PERMISSIONS.any { context.isGranted(it) },
            hasFineLocation = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION),
            hasCameraPermission = context.isGranted(Manifest.permission.CAMERA),
        )
        onPauseOrDispose { }
    }

    fun handleAction(action: NoticeAction?) {
        when (action) {
            NoticeAction.REQUEST_LOCATION_PERMISSION -> locationPermissionLauncher.launch(LOCATION_PERMISSIONS)
            NoticeAction.RETRY_LOCATION -> startLocationFlow()
            NoticeAction.OPEN_LOCATION_SETTINGS -> context.openLocationSettings()
            NoticeAction.REQUEST_CAMERA_PERMISSION -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            NoticeAction.RETRY_CAMERA -> startCameraFlow()
            NoticeAction.OPEN_APP_SETTINGS -> context.openAppSettings()
            null -> Unit
        }
    }

    val scanning = scanAfterAttempt != null || state.location == LocationUiState.Loading

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconBubble(
                        icon = Icons.Outlined.ViewInAr,
                        size = 46,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                    )
                },
                title = {
                    Column {
                        Text("Umgebungsscanner", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Matr.-Nr. $matrikel",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Einstellungen", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (!scanning) {
                        scanAfterAttempt = state.locationAttempts
                        startLocationFlow()
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    }
                },
                text = { Text(if (scanning) "Scan läuft …" else "Scan starten") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Erfasse Informationen über deine aktuelle Umgebung.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp, end = 24.dp),
            )

            FeatureCard(
                icon = Icons.Outlined.LocationOn,
                title = "Standort",
                subtitle = "Aktuellen Standort abrufen",
                loading = state.location == LocationUiState.Loading,
                onClick = { startLocationFlow() },
            )
            state.locationNotice?.let { notice ->
                NoticeCard(notice, onAction = { handleAction(notice.action) }, onDismiss = viewModel::dismissLocationNotice)
            }

            FeatureCard(
                icon = Icons.Outlined.PhotoCamera,
                title = "Kamera",
                subtitle = "Foto aufnehmen",
                onClick = { startCameraFlow() },
            )
            state.cameraNotice?.let { notice ->
                NoticeCard(notice, onAction = { handleAction(notice.action) }, onDismiss = viewModel::dismissCameraNotice)
            }

            FeatureCard(
                icon = Icons.Outlined.SportsEsports,
                title = "Sensor Challenge",
                subtitle = "Balance Challenge mit dem Gyroskop",
                highlighted = true,
                onClick = onOpenChallenge,
            )

            CurrentDataCard(state = state, sensorStatus = sensorStatus, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun CurrentDataCard(state: ScannerUiState, sensorStatus: String, modifier: Modifier = Modifier) {
    SoftCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BarChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text("Aktuelle Daten", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.size(14.dp))

            DataRow(Icons.Outlined.LocationOn, "Standort") {
                when (val location = state.location) {
                    LocationUiState.Idle -> DataValue("Noch nicht erfasst")
                    LocationUiState.Loading -> DataValue("Wird ermittelt …")
                    is LocationUiState.Failed -> DataValue(location.shortText, isError = true)
                    is LocationUiState.Success -> Column {
                        DataValue(formatCoordinates(location.latitude, location.longitude), strong = true)
                        val accuracy = location.accuracyMeters?.let { "Genauigkeit ±${it.toInt()} m" } ?: "Genauigkeit unbekannt"
                        DataValue(if (location.approximate) "$accuracy · ungefähr" else accuracy)
                    }
                }
            }
            DataRow(Icons.Outlined.PhotoCamera, "Foto") {
                val takenAt = state.photoTakenAt
                if (state.photo != null && takenAt != null) {
                    DataValue("Aufgenommen um ${DateFormat.getTimeInstance(DateFormat.SHORT, Locale.GERMANY).format(Date(takenAt))}", strong = true)
                } else {
                    DataValue("Kein Foto")
                }
            }
            DataRow(Icons.Outlined.MonitorHeart, "Sensor") { DataValue(sensorStatus) }

            state.photo?.let { photo ->
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = "Aufgenommenes Foto",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
            }
        }
    }
}

@Composable
private fun DataRow(icon: ImageVector, label: String, value: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(110.dp),
        )
        Column(Modifier.weight(1f)) { value() }
    }
}

@Composable
private fun DataValue(text: String, isError: Boolean = false, strong: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal,
        color = when {
            isError -> MaterialTheme.colorScheme.error
            strong -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(top = 2.dp),
    )
}

private fun formatCoordinates(latitude: Double, longitude: Double): String {
    val lat = String.format(Locale.GERMANY, "%.5f° %s", abs(latitude), if (latitude >= 0) "N" else "S")
    val lon = String.format(Locale.GERMANY, "%.5f° %s", abs(longitude), if (longitude >= 0) "O" else "W")
    return "$lat, $lon"
}
