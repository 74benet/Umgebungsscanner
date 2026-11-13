package de.hhn.umgebungsscanner.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Nach einer Ablehnung im Callback auswerten:
 * shouldShowRequestPermissionRationale() == true  -> einfach verweigert, erneut fragen ist möglich.
 * shouldShowRequestPermissionRationale() == false -> dauerhaft verweigert, nur noch über die Einstellungen.
 */
fun Activity?.isPermanentlyDenied(vararg permissions: String): Boolean {
    if (this == null) return false
    return permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
}

fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

fun Context.openLocationSettings() {
    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }.onFailure { openAppSettings() }
}

fun Context.hasCameraHardware(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
