package com.icl.surveillance.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.icl.surveillance.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.getValue


class CurrentLocationDialogFragment : DialogFragment(R.layout.fragment_current_location) {
    private val locationServicesClient by lazy {
        LocationServices.getFusedLocationProviderClient(requireContext())
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocationHighAccuracy() {
        val result =
            locationServicesClient
                .getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    CancellationTokenSource().token
                )
                .await()
        result?.let {
            setFragmentResult(
                LATITUDE_REQUEST_RESULT_KEY,
                bundleOf(
                    LATITUDE_REQUEST_RESULT_KEY to it.latitude,
                ),
            )
            setFragmentResult(
                LONGITUDE_REQUEST_RESULT_KEY,
                bundleOf(
                    LONGITUDE_REQUEST_RESULT_KEY to it.longitude,
                ),
            )
            setFragmentResult(
                ALTITUDE_REQUEST_RESULT_KEY,
                bundleOf(
                    ALTITUDE_REQUEST_RESULT_KEY to it.altitude,
                ),
            )
        }
        dismiss()
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocation() {
        val result =
            locationServicesClient
                .getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    CancellationTokenSource().token,
                )
                .await()
        result?.let {
            setFragmentResult(
                LATITUDE_REQUEST_RESULT_KEY,
                bundleOf(
                    LATITUDE_REQUEST_RESULT_KEY to it.latitude,
                ),
            )
            setFragmentResult(
                LONGITUDE_REQUEST_RESULT_KEY,
                bundleOf(
                    LONGITUDE_REQUEST_RESULT_KEY to it.longitude,
                ),
            )
            setFragmentResult(
                ALTITUDE_REQUEST_RESULT_KEY,
                bundleOf(
                    ALTITUDE_REQUEST_RESULT_KEY to it.altitude,
                ),
            )
        }
        dismiss()
    }

    private val locationPermissionRequest =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            when {
                permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                    lifecycleScope.launch { getCurrentLocationHighAccuracy() }
                }

                permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                    lifecycleScope.launch { getCurrentLocation() }
                }

                else -> {
                    // No location access granted.
                    Toast.makeText(
                        requireContext(),
                        "Location Access Permission not granted",
                        Toast.LENGTH_LONG,
                    )
                        .show()
                    dismiss()
                }
            }
        }

    override fun onResume() {
        super.onResume()

        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
                -> {
                lifecycleScope.launch { getCurrentLocationHighAccuracy() }
            }

            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
                -> {
                lifecycleScope.launch { getCurrentLocation() }
            }

            else -> {
                locationPermissionRequest.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }
    }


    companion object {
        const val LATITUDE_REQUEST_RESULT_KEY = "location-latitude-result"
        const val LONGITUDE_REQUEST_RESULT_KEY = "location-longitude-result"
        const val ALTITUDE_REQUEST_RESULT_KEY = "location-altitude-result"
    }
}
