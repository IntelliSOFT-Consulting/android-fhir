package com.icl.surveillance.clients

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.icl.surveillance.BuildConfig
import com.icl.surveillance.R
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.databinding.ActivityResidencePickerBinding
import com.icl.surveillance.databinding.ItemPlaceSuggestionBinding
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.FormPrefill
import com.icl.surveillance.fhir.forms.ResidenceLocation
import com.icl.surveillance.network.PlacesSearch
import com.icl.surveillance.network.PlacesSearch.Suggestion
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Picks where a person lives, ride-hailing style: search a place (Google Places autocomplete) or
 * move the map so the centre pin sits on the home, or jump to the current location. The name and
 * coordinates are staged ([ResidenceLocation.stage]) and appended to the form when it is saved.
 *
 * With [EXTRA_OPEN_FORM] the case form opens next (the result is forwarded to the caller);
 * otherwise the location is returned in [EXTRA_RESULT] (JSON, see [ResidenceLocation.fromJson]).
 */
class ResidencePickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResidencePickerBinding
    private var map: GoogleMap? = null
    private var places: PlacesSearch? = null

    private var searchJob: Job? = null
    private var resolveJob: Job? = null

    /** The location shown in the bottom card; null until the map has settled somewhere. */
    private var selected: ResidenceLocation? = null

    /** A search result moves the camera: keep its name instead of reverse geocoding the pin. */
    private var pendingSearchResult: ResidenceLocation? = null

    /** Next camera stop came from the location button. */
    private var movingToGps = false

    private val adapter = SuggestionAdapter { prediction -> pickPrediction(prediction) }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.any { it }) goToCurrentLocation(animate = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityResidencePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.residence_screen_title)
            setDisplayHomeAsUpEnabled(true)
        }

        if (BuildConfig.MAPS_API_KEY.isBlank()) {
            Toast.makeText(this, R.string.residence_no_key, Toast.LENGTH_LONG).show()
        } else {
            places = PlacesSearch(applicationContext)
        }

        binding.suggestions.layoutManager = LinearLayoutManager(this)
        binding.suggestions.adapter = adapter
        binding.search.doAfterTextChanged { text -> onQueryChanged(text?.toString().orEmpty()) }
        binding.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                adapter.items.firstOrNull()?.let { pickPrediction(it) }
                true
            } else false
        }
        binding.myLocation.setOnClickListener { requestCurrentLocation() }
        binding.confirmButton.setOnClickListener { selected?.let { finishWith(it) } }
        binding.skipButton.setOnClickListener { finishWith(null) }

        (supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment).getMapAsync { googleMap ->
            onMapReady(googleMap, restored = savedInstanceState)
        }
    }

    @SuppressLint("MissingPermission")
    private fun onMapReady(googleMap: GoogleMap, restored: Bundle?) {
        map = googleMap
        googleMap.uiSettings.isMyLocationButtonEnabled = false
        googleMap.uiSettings.isMapToolbarEnabled = false
        if (hasLocationPermission()) googleMap.isMyLocationEnabled = true

        googleMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                pendingSearchResult = null
                movingToGps = false
                hideSuggestions()
            }
            binding.centerPin.animate().translationY(-dp(10)).setDuration(120).start()
        }
        googleMap.setOnCameraIdleListener {
            binding.centerPin.animate().translationY(0f).setDuration(120).start()
            onPinSettled(googleMap.cameraPosition.target)
        }

        val start = restored?.let { ResidenceLocation.fromJson(it.getString(STATE_SELECTED)) }
            ?: ResidenceLocation.peek(this)
        if (start != null) {
            pendingSearchResult = start
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(start.latitude, start.longitude), 17f))
        } else {
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(KENYA, 6f))
            if (hasLocationPermission()) goToCurrentLocation(animate = false) else requestCurrentLocation()
        }
    }

    // ---- Pin and reverse geocoding

    private fun onPinSettled(target: LatLng) {
        val fromSearch = pendingSearchResult
        if (fromSearch != null) {
            pendingSearchResult = null
            show(fromSearch.copy(latitude = target.latitude, longitude = target.longitude))
            return
        }
        val source = if (movingToGps) "gps" else "map"
        movingToGps = false
        resolveJob?.cancel()
        binding.resolving.visibility = View.VISIBLE
        showCoordinates(target)
        resolveJob = lifecycleScope.launch {
            delay(250) // the map may move again straight away
            val address = withContext(Dispatchers.IO) { reverseGeocode(target) }
            binding.resolving.visibility = View.GONE
            show(
                ResidenceLocation(
                    name = address?.let(::shortName) ?: getString(R.string.residence_dropped_pin),
                    address = address?.let(::longAddress).orEmpty(),
                    latitude = target.latitude,
                    longitude = target.longitude,
                    source = source,
                )
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(target: LatLng): Address? = runCatching {
        if (!Geocoder.isPresent()) return null
        Geocoder(this, Locale.getDefault()).getFromLocation(target.latitude, target.longitude, 1)?.firstOrNull()
    }.onFailure { Timber.tag(TAG).w(it, "Reverse geocoding failed") }.getOrNull()

    /** Most specific readable part: estate / street / village rather than the plus code. */
    private fun shortName(address: Address): String =
        listOfNotNull(
            address.featureName?.takeUnless { it.contains('+') || it.all { c -> c.isDigit() } },
            address.subLocality,
            address.thoroughfare,
            address.locality,
            address.subAdminArea,
        ).firstOrNull { it.isNotBlank() } ?: getString(R.string.residence_dropped_pin)

    private fun longAddress(address: Address): String =
        (0..address.maxAddressLineIndex).mapNotNull { address.getAddressLine(it) }.joinToString(", ")
            .ifBlank {
                listOfNotNull(address.subLocality, address.locality, address.adminArea, address.countryName)
                    .joinToString(", ")
            }

    private fun show(location: ResidenceLocation) {
        selected = location
        binding.placeName.text = location.name
        binding.placeAddress.text = location.address
        binding.placeAddress.visibility = if (location.address.isBlank()) View.GONE else View.VISIBLE
        showCoordinates(LatLng(location.latitude, location.longitude))
        binding.confirmButton.isEnabled = true
    }

    private fun showCoordinates(target: LatLng) {
        binding.coordinates.text = String.format(Locale.US, "%.6f, %.6f", target.latitude, target.longitude)
    }

    // ---- Search (Places autocomplete)

    private fun onQueryChanged(query: String) {
        searchJob?.cancel()
        val client = places
        if (query.trim().length < 2 || client == null) {
            hideSuggestions()
            return
        }
        searchJob = lifecycleScope.launch {
            delay(300) // debounce typing
            val suggestions = client.autocomplete(query.trim(), searchBounds())
            adapter.submit(suggestions)
            binding.suggestionsCard.visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /** Bias results to what is on screen (about a county when zoomed out). */
    private fun searchBounds(): PlacesSearch.Bounds? {
        val bounds = map?.projection?.visibleRegion?.latLngBounds ?: return null
        return PlacesSearch.Bounds(
            south = bounds.southwest.latitude, west = bounds.southwest.longitude,
            north = bounds.northeast.latitude, east = bounds.northeast.longitude,
        )
    }

    private fun pickPrediction(prediction: Suggestion) {
        val client = places ?: return
        hideSuggestions()
        hideKeyboard()
        binding.search.setText(prediction.primary)
        binding.search.clearFocus()
        lifecycleScope.launch {
            val place = client.details(prediction.placeId)
            if (place == null) {
                Toast.makeText(this@ResidencePickerActivity, R.string.residence_search_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val latLng = LatLng(place.latitude, place.longitude)
            val location = ResidenceLocation(
                name = place.name.ifBlank { prediction.primary },
                address = place.address.ifBlank { prediction.secondary },
                latitude = place.latitude,
                longitude = place.longitude,
                source = "search",
            )
            pendingSearchResult = location
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f))
                ?: show(location)
        }
    }

    private fun hideSuggestions() {
        binding.suggestionsCard.visibility = View.GONE
        adapter.submit(emptyList())
    }

    // ---- Current location

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestCurrentLocation() {
        if (hasLocationPermission()) goToCurrentLocation(animate = true)
        else locationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    @SuppressLint("MissingPermission")
    private fun goToCurrentLocation(animate: Boolean) {
        if (!hasLocationPermission()) return
        map?.isMyLocationEnabled = true
        lifecycleScope.launch {
            val client = LocationServices.getFusedLocationProviderClient(this@ResidencePickerActivity)
            val location = runCatching {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
                    ?: client.lastLocation.await()
            }.onFailure { Timber.tag(TAG).w(it, "Current location failed") }.getOrNull()
            if (location == null) {
                if (animate) {
                    Toast.makeText(this@ResidencePickerActivity, R.string.residence_location_off, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            pendingSearchResult = null
            movingToGps = true
            val update = CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 17f)
            if (animate) map?.animateCamera(update) else map?.moveCamera(update)
        }
    }

    // ---- Done

    private fun finishWith(location: ResidenceLocation?) {
        if (location != null) {
            ResidenceLocation.stage(this, location)
            Timber.tag(TAG).d("Residence picked: %s", location.label)
        } else {
            ResidenceLocation.clear(this)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_FORM, false)) {
            val form = intent.getStringExtra(QUESTIONNAIRE_FILE_PATH_KEY) ?: FormFields.Vhf.CASE_FORM
            // Pass the pick to the form: "Current residence" (visible, editable) plus the hidden
            // residence-map-* answers (name, address, latitude, longitude).
            location?.let {
                FormPrefill.add(
                    this, form,
                    mapOf(CURRENT_RESIDENCE to it.name.ifBlank { it.address }) + it.formAnswers(),
                    overwrite = true,
                )
            }
            startActivity(
                Intent(this, AddParentCaseActivity::class.java).apply {
                    putExtras(intent)
                    removeExtra(EXTRA_OPEN_FORM)
                    addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
                }
            )
        } else {
            setResult(
                if (location != null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
                Intent().putExtra(EXTRA_RESULT, location?.toJson())
            )
        }
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selected?.let { outState.putString(STATE_SELECTED, it.toJson()) }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.search.windowToken, 0)
    }

    private fun dp(value: Int) = value * resources.displayMetrics.density

    /** Play-services Task -> coroutine. */
    private suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
        addOnFailureListener { error ->
            Timber.tag(TAG).w(error, "Task failed")
            if (cont.isActive) cont.resume(null)
        }
        addOnCanceledListener { if (cont.isActive) cont.resume(null) }
    }

    private class SuggestionAdapter(
        private val onPick: (Suggestion) -> Unit,
    ) : RecyclerView.Adapter<SuggestionAdapter.Holder>() {

        var items: List<Suggestion> = emptyList()
            private set

        fun submit(newItems: List<Suggestion>) {
            items = newItems
            notifyDataSetChanged()
        }

        class Holder(val binding: ItemPlaceSuggestionBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemPlaceSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val prediction = items[position]
            holder.binding.primary.text = prediction.primary
            holder.binding.secondary.text = prediction.secondary
            holder.binding.root.setOnClickListener { onPick(prediction) }
        }
    }

    companion object {
        private const val TAG = "RESIDENCE_PICKER"

        /** true: open the case form after picking (the result goes to the original caller). */
        const val EXTRA_OPEN_FORM = "residenceOpenForm"

        /** The picked location as JSON, when not opening the form. */
        const val EXTRA_RESULT = "residenceLocation"

        private const val STATE_SELECTED = "residenceSelected"

        /** "Current residence" on the VHF case form. */
        private const val CURRENT_RESIDENCE = "vhf-current-residence"

        private val KENYA = LatLng(0.0236, 37.9062)
    }
}
