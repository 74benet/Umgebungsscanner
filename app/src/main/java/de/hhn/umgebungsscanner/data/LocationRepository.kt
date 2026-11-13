package de.hhn.umgebungsscanner.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Jede Antwort des Systems kann "nein" oder "nichts" sein. Deshalb ein Zustand pro Fall. */
sealed interface LocationResult {
    data class Success(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val approximate: Boolean,
    ) : LocationResult

    /** Kein Standortrecht (z. B. nachträglich in den Einstellungen entzogen). */
    data object NoPermission : LocationResult

    /** Recht da, aber die Standortdienste (GPS) sind ausgeschaltet. */
    data object ServicesDisabled : LocationResult

    /** Innerhalb des Timeouts kam keine Position (z. B. Emulator ohne gesetzte Position). */
    data object NoFix : LocationResult

    data class Failure(val reason: String) : LocationResult
}

class LocationRepository(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val fused = LocationServices.getFusedLocationProviderClient(appContext)

    fun hasFinePermission() = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasCoarsePermission() = isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
    fun hasAnyPermission() = hasFinePermission() || hasCoarsePermission()

    fun isLocationEnabled(): Boolean =
        locationManager != null && LocationManagerCompat.isLocationEnabled(locationManager)

    /** Fragt eine frische Position ab: mit Rechte-Prüfung, GPS-Prüfung und Timeout, ohne `!!`. */
    @SuppressLint("MissingPermission") // Recht wird direkt davor geprüft
    suspend fun currentLocation(timeoutMillis: Long = TIMEOUT_MILLIS): LocationResult {
        if (!hasAnyPermission()) return LocationResult.NoPermission
        if (!isLocationEnabled()) return LocationResult.ServicesDisabled
        val approximate = !hasFinePermission()

        return try {
            val location = withTimeoutOrNull(timeoutMillis) {
                if (playServicesAvailable()) fusedLocation(approximate) else platformLocation(approximate)
            }
            location?.let {
                LocationResult.Success(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracyMeters = if (it.hasAccuracy()) it.accuracy else null,
                    approximate = approximate,
                )
            } ?: LocationResult.NoFix
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            LocationResult.NoPermission
        } catch (e: Exception) {
            LocationResult.Failure(e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class) // await(CancellationTokenSource) bricht die Anfrage bei Timeout ab
    @SuppressLint("MissingPermission")
    private suspend fun fusedLocation(approximate: Boolean): Location? {
        val priority = if (approximate) Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_HIGH_ACCURACY
        val cancellation = CancellationTokenSource()
        return try {
            fused.getCurrentLocation(priority, cancellation.token).await(cancellation)
        } finally {
            cancellation.cancel()
        }
    }

    /** Fallback für Geräte ohne Google Play Services (LocationManager statt Fused). */
    @SuppressLint("MissingPermission")
    private suspend fun platformLocation(approximate: Boolean): Location? {
        val manager = locationManager ?: return null
        val provider = when {
            !approximate && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return null
        }
        return suspendCancellableCoroutine { cont ->
            val signal = android.os.CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { location ->
                if (cont.isActive) cont.resume(location)
            }
        }
    }

    private fun playServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        val executor = Executors.newSingleThreadExecutor()
    }
}
