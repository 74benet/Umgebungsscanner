package de.hhn.umgebungsscanner.scanner

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.hhn.umgebungsscanner.data.LocationRepository
import de.hhn.umgebungsscanner.data.LocationResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LocationUiState {
    data object Idle : LocationUiState
    data object Loading : LocationUiState
    data class Success(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val approximate: Boolean,
    ) : LocationUiState

    /** Kurzer Text für die Datenzeile, die ausführliche Erklärung steht im Notice. */
    data class Failed(val shortText: String) : LocationUiState
}

enum class NoticeAction {
    REQUEST_LOCATION_PERMISSION,
    RETRY_LOCATION,
    OPEN_LOCATION_SETTINGS,
    REQUEST_CAMERA_PERMISSION,
    RETRY_CAMERA,
    OPEN_APP_SETTINGS,
}

enum class NoticeKind { LOCATION_PERMISSION, LOCATION_SERVICES, LOCATION_OTHER, CAMERA_PERMISSION, CAMERA_OTHER }

/** Verständliche Meldung statt Absturz, optional mit einer Aktion, die das Problem löst. */
data class Notice(
    val kind: NoticeKind,
    val title: String,
    val message: String,
    val action: NoticeAction? = null,
    val actionLabel: String? = null,
    val isError: Boolean = true,
)

data class ScannerUiState(
    val location: LocationUiState = LocationUiState.Idle,
    val photo: Bitmap? = null,
    val photoTakenAt: Long? = null,
    val locationNotice: Notice? = null,
    val cameraNotice: Notice? = null,
    /** Zählt abgeschlossene Standort-Versuche (Erfolg oder Fehler). Für "Scan starten". */
    val locationAttempts: Int = 0,
)

/**
 * Hält den Zustand des Umgebungsscanners. Liegt im ViewModel, damit Foto und Standort
 * das Drehen des Bildschirms überleben (remember würde beides verlieren).
 */
class ScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val locationRepository = LocationRepository(application)

    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    private var locationJob: Job? = null

    fun fetchLocation() {
        if (locationJob?.isActive == true) return
        _state.update { it.copy(location = LocationUiState.Loading, locationNotice = null) }
        locationJob = viewModelScope.launch {
            val result = locationRepository.currentLocation()
            _state.update { current ->
                when (result) {
                    is LocationResult.Success -> current.copy(
                        location = LocationUiState.Success(
                            latitude = result.latitude,
                            longitude = result.longitude,
                            accuracyMeters = result.accuracyMeters,
                            approximate = result.approximate,
                        ),
                        locationAttempts = current.locationAttempts + 1,
                        locationNotice = if (result.approximate) {
                            Notice(
                                kind = NoticeKind.LOCATION_PERMISSION,
                                title = "Nur ungefährer Standort",
                                message = "Du hast nur den ungefähren Standort freigegeben. Für eine genaue Position kannst du den genauen Standort erlauben.",
                                action = NoticeAction.REQUEST_LOCATION_PERMISSION,
                                actionLabel = "Genauen Standort erlauben",
                                isError = false,
                            )
                        } else {
                            null
                        },
                    )

                    LocationResult.NoPermission -> current.withLocationError(
                        shortText = "Keine Berechtigung",
                        notice = deniedLocationNotice(),
                    )

                    LocationResult.ServicesDisabled -> current.withLocationError(
                        shortText = "Standortdienste aus",
                        notice = Notice(
                            kind = NoticeKind.LOCATION_SERVICES,
                            title = "Standort ist ausgeschaltet",
                            message = "Die Standortdienste deines Geräts sind deaktiviert. Schalte den Standort ein und versuche es erneut.",
                            action = NoticeAction.OPEN_LOCATION_SETTINGS,
                            actionLabel = "Standort einschalten",
                        ),
                    )

                    LocationResult.NoFix -> current.withLocationError(
                        shortText = "Kein Standort ermittelbar",
                        notice = Notice(
                            kind = NoticeKind.LOCATION_OTHER,
                            title = "Kein Standort gefunden",
                            message = "Innerhalb von 10 Sekunden kam keine Position. Geh ins Freie oder setze im Emulator unter Extended Controls → Location eine Position.",
                            action = NoticeAction.RETRY_LOCATION,
                            actionLabel = "Erneut versuchen",
                        ),
                    )

                    is LocationResult.Failure -> current.withLocationError(
                        shortText = "Fehler beim Abrufen",
                        notice = Notice(
                            kind = NoticeKind.LOCATION_OTHER,
                            title = "Standort nicht verfügbar",
                            message = "Der Standortdienst hat einen Fehler gemeldet: ${result.reason}",
                            action = NoticeAction.RETRY_LOCATION,
                            actionLabel = "Erneut versuchen",
                        ),
                    )
                }
            }
        }
    }

    /** Wird im Callback des Permission-Dialogs aufgerufen, wenn kein Standortrecht erteilt wurde. */
    fun onLocationPermissionDenied(permanently: Boolean) {
        _state.update {
            it.withLocationError(
                shortText = if (permanently) "Dauerhaft verweigert" else "Berechtigung verweigert",
                notice = if (permanently) permanentlyDeniedLocationNotice() else deniedLocationNotice(),
            )
        }
    }

    fun onCameraPermissionDenied(permanently: Boolean) {
        val notice = if (permanently) {
            Notice(
                kind = NoticeKind.CAMERA_PERMISSION,
                title = "Kamera dauerhaft verweigert",
                message = "Du hast den Kamerazugriff dauerhaft abgelehnt. Android fragt nicht mehr nach. Du kannst ihn in den App-Einstellungen unter Berechtigungen erlauben.",
                action = NoticeAction.OPEN_APP_SETTINGS,
                actionLabel = "Einstellungen öffnen",
            )
        } else {
            Notice(
                kind = NoticeKind.CAMERA_PERMISSION,
                title = "Kamerazugriff verweigert",
                message = "Ohne Kamerarecht kann die App kein Foto aufnehmen. Das Foto bleibt auf deinem Gerät.",
                action = NoticeAction.REQUEST_CAMERA_PERMISSION,
                actionLabel = "Erneut fragen",
            )
        }
        _state.update { it.copy(cameraNotice = notice) }
    }

    /** Ergebnis von TakePicturePreview: null bei Abbruch, dann bleibt das alte Foto erhalten. */
    fun onPhotoResult(bitmap: Bitmap?) {
        if (bitmap != null) {
            _state.update {
                it.copy(photo = bitmap, photoTakenAt = System.currentTimeMillis(), cameraNotice = null)
            }
        } else {
            _state.update {
                it.copy(
                    cameraNotice = Notice(
                        kind = NoticeKind.CAMERA_OTHER,
                        title = "Aufnahme abgebrochen",
                        message = if (it.photo != null) {
                            "Es wurde kein neues Foto aufgenommen. Dein vorheriges Foto bleibt erhalten."
                        } else {
                            "Es wurde kein Foto aufgenommen."
                        },
                        action = NoticeAction.RETRY_CAMERA,
                        actionLabel = "Erneut aufnehmen",
                        isError = false,
                    ),
                )
            }
        }
    }

    fun onCameraUnavailable(reason: String) {
        _state.update {
            it.copy(
                cameraNotice = Notice(
                    kind = NoticeKind.CAMERA_OTHER,
                    title = "Keine Kamera verfügbar",
                    message = reason,
                ),
            )
        }
    }

    /**
     * Nach der Rückkehr in die App (z. B. aus den Einstellungen) prüfen, ob sich etwas geändert hat,
     * und veraltete Meldungen entfernen.
     */
    fun onResume(hasLocationPermission: Boolean, hasFineLocation: Boolean, hasCameraPermission: Boolean) {
        val current = _state.value
        val locationNotice = current.locationNotice
        when (locationNotice?.kind) {
            NoticeKind.LOCATION_PERMISSION -> {
                val deniedButNowGranted = locationNotice.isError && hasLocationPermission
                val approximateButNowFine = !locationNotice.isError && hasFineLocation
                if (deniedButNowGranted || approximateButNowFine) fetchLocation()
            }

            NoticeKind.LOCATION_SERVICES -> if (locationRepository.isLocationEnabled()) fetchLocation()
            else -> Unit
        }
        if (current.cameraNotice?.kind == NoticeKind.CAMERA_PERMISSION && hasCameraPermission) {
            _state.update { it.copy(cameraNotice = null) }
        }
    }

    fun dismissLocationNotice() = _state.update { it.copy(locationNotice = null) }
    fun dismissCameraNotice() = _state.update { it.copy(cameraNotice = null) }

    private fun ScannerUiState.withLocationError(shortText: String, notice: Notice) = copy(
        location = LocationUiState.Failed(shortText),
        locationNotice = notice,
        locationAttempts = locationAttempts + 1,
    )

    private fun deniedLocationNotice() = Notice(
        kind = NoticeKind.LOCATION_PERMISSION,
        title = "Standortzugriff verweigert",
        message = "Ohne Standortrecht kann die App deine Position nicht anzeigen. Der Standort wird nur auf deinem Gerät angezeigt und nicht gespeichert.",
        action = NoticeAction.REQUEST_LOCATION_PERMISSION,
        actionLabel = "Erneut fragen",
    )

    private fun permanentlyDeniedLocationNotice() = Notice(
        kind = NoticeKind.LOCATION_PERMISSION,
        title = "Standort dauerhaft verweigert",
        message = "Du hast den Standortzugriff dauerhaft abgelehnt. Android zeigt keinen Dialog mehr. Du kannst ihn in den App-Einstellungen unter Berechtigungen erlauben.",
        action = NoticeAction.OPEN_APP_SETTINGS,
        actionLabel = "Einstellungen öffnen",
    )
}
