package com.icl.surveillance.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

object LocationUtils {

    private const val LOCATION_PERMISSION_REQUEST_CODE = 1001

    fun requestCurrentLocation(
        activity: Activity,
        onLocationReceived: (latitude: Double, longitude: Double) -> Unit,
        onError: (String) -> Unit
    ) {
        // Check permission
        if (ActivityCompat.checkSelfPermission(
                activity, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            onError("Permission not granted")
            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(activity)

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            2000 // Update interval
        ).setMinUpdateIntervalMillis(1000).build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null) {
                    onLocationReceived(location.latitude, location.longitude)
                    fusedLocationClient.removeLocationUpdates(this) // Stop after one reading
                } else {
                    onError("Location not found")
                }
            }
        }

        // Try last known location first
        getLastKnownLocation(fusedLocationClient, locationCallback, activity, locationRequest, onLocationReceived, onError)
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(
        fusedLocationClient: FusedLocationProviderClient,
        locationCallback: LocationCallback,
        activity: Activity,
        locationRequest: LocationRequest,
        onLocationReceived: (latitude: Double, longitude: Double) -> Unit,
        onError: (String) -> Unit
    ) {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                onLocationReceived(location.latitude, location.longitude)
            } else {
                // Request fresh location if no cached location
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    activity.mainLooper
                )
            }
        }.addOnFailureListener {
            onError("Failed to get location: ${it.message}")
        }
    }
}