package com.icl.surveillance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import com.google.android.play.core.appupdate.AppUpdateManager
import com.icl.surveillance.databinding.ActivityMainBinding
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.viewmodels.SyncFragmentViewModel


import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.firebase.messaging.FirebaseMessaging

import com.icl.surveillance.auth.LoginActivity
import com.icl.surveillance.auth.tokenizer.NPHIISTokenWorker
import com.icl.surveillance.fhir.DemoDataStore
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.monitor.FhirBundleService
import com.icl.surveillance.monitor.FhirPaginatedRepository
import com.icl.surveillance.monitor.PaginatedViewModel
import com.icl.surveillance.network.RetrofitCallsAuthentication
import com.icl.surveillance.network.TokenRefreshWorker
import com.icl.surveillance.utils.NetworkUtils.isInternetAvailable
import com.icl.surveillance.viewmodels.AddClientViewModel
import com.icl.surveillance.viewmodels.PeriodicSyncViewModel
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.*
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.getValue
import kotlin.jvm.java

class MainActivity : AppCompatActivity() {
    private lateinit var backupSyncViewModel: PaginatedViewModel
    private var retrofitCallsAuthentication = RetrofitCallsAuthentication()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private lateinit var appUpdateManager: AppUpdateManager
    private val UPDATE_REQUEST_CODE = 123
    private lateinit var binding: ActivityMainBinding

    private val periodicViewModel: PeriodicSyncViewModel by viewModels()
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (!granted) {
                showPermissionDialog()
            } else {
                checkGpsEnabled()
            }
        }
    private val viewModel: SyncFragmentViewModel by viewModels()


    override fun onStart() {
        super.onStart()
        val isLoggedIn = FormatterClass().getSharedPref("isLoggedIn", this)
        if (isLoggedIn == null || isLoggedIn != "true" || !FhirApplication.hasAccessToken()) {
            FormatterClass().deleteSharedPref("isLoggedIn", this)
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

            )
            finish()
        }

    }

    private val addClientViewModel: AddClientViewModel by viewModels()
    private lateinit var fhirEngine: FhirEngine
    private lateinit var fhirBundleService: FhirBundleService
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarTheme()
        fhirEngine = FhirApplication.fhirEngine(this@MainActivity)

        backupSyncViewModel = PaginatedViewModel(fhirEngine)
        fhirBundleService = FhirBundleService(fhirEngine)

        val rootView: View = findViewById(R.id.container) // your root view ID
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply only top (status bar) and side padding
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                view.paddingBottom // keep existing bottom padding for BottomNavigationView
            )

            insets
        }
        handleTokenRefresh()
        getUserProfile()

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {

                return@addOnCompleteListener
            }
            val token = task.result

            // Optionally save it or send to your server
            FormatterClass().saveSharedPref("fcmToken", token, this)
            retrofitCallsAuthentication.updateOrCreateToken(this, token)
        }

        viewModel.triggerOneTimeSync()
//        updateSourceFacility()
        setupTokenRefresh()
        appUpdateManager = AppUpdateManagerFactory.create(this)
        checkForAppUpdate()

        val navView: BottomNavigationView = binding.navView
        setSupportActionBar(binding.toolbar)

        val navController = resolveNavController()
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration =
            AppBarConfiguration(
                setOf(
                    R.id.navigation_home,
                    R.id.nav_resources,
                    R.id.navigation_notifications
                )
            )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    // Do something when Home is clicked
                    navController.navigate(R.id.navigation_home)
                    true
                }

                R.id.nav_resources -> {
                    // Do something when Dashboard is clicked
                    navController.navigate(R.id.nav_resources)
                    true
                }

                R.id.navigation_notifications -> {
                    // Do something when Notifications is clicked
                    navController.navigate(R.id.navigation_notifications)
                    true
                }

                else -> false
            }
        }
        checkLocationPermission()
        generateAreaOfJurisdiction()
    }

    private fun handleTokenRefresh() {
        val isLoggedIn = FormatterClass().getSharedPref("isLoggedIn", this)

        if (isLoggedIn != "true") return

        val workRequest = PeriodicWorkRequestBuilder<NPHIISTokenWorker>(
            15, TimeUnit.MINUTES
        ).apply {
            setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
        }.build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "NPHIISTokenWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun resolveNavController(): NavController {
        supportFragmentManager.executePendingTransactions()

        val navHostFragment =
            (supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_main) as? NavHostFragment)
                ?: supportFragmentManager.fragments.filterIsInstance<NavHostFragment>()
                    .firstOrNull()
                ?: throw IllegalStateException("NavHostFragment was not found in activity_main")

        return navHostFragment.navController
    }

    private fun applySystemBarTheme() {
        val isDarkMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES

        window.statusBarColor = ContextCompat.getColor(this, R.color.home_header_start)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.nav_bar_background)

        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            isAppearanceLightStatusBars = !isDarkMode
            isAppearanceLightNavigationBars = !isDarkMode
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }

    private fun setupTokenRefresh() {

        if (isInternetAvailable(this@MainActivity)) {
            val workRequest =
                PeriodicWorkRequestBuilder<TokenRefreshWorker>(
                    1, TimeUnit.HOURS
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()

            WorkManager.getInstance(
                this@MainActivity
            ).enqueueUniquePeriodicWork(
                "token_refresh_work",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }

    private fun generateAreaOfJurisdiction() {
        val units = addClientViewModel.generateAreaOfJurisdiction(this@MainActivity, fhirEngine)

        FormatterClass().saveFacilityIdsForWard(this@MainActivity, "units", units)
    }

    private fun updateSourceFacility() {
        lifecycleScope.launch {
            val encounters = addClientViewModel.retrieveCaseEncounters("case-information")

            encounters.forEach { encounter ->
                val responses = addClientViewModel.retrieveResponses(encounter.idPart)
                responses.forEach { res ->
                    val flattened = res.flattenAnswers()

                    val facilityIDs = listOf(
                        "819946803677_county",
                        "819946803677_sub_county",
                        "819946803677"
                    )

                    val facilityInfo = try {
                        facilityIDs.firstNotNullOfOrNull { id ->
                            flattened[id]
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        ""
                    }

                    if (facilityInfo != null) {
                        addClientViewModel.updateObservationsTag(
                            encounter,
                            res,
                            facilityInfo,
                            encounter.idPart
                        )
                    }
                }
            }

        }
    }

    fun QuestionnaireResponse.flattenAnswers(): Map<String, String> {
        val result = mutableMapOf<String, String>()

        fun traverse(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
            items.forEach { item ->
                item.answer.forEach { answer ->
                    extractAnswerValue(answer)?.let { value ->
                        result[item.linkId] = value
                    }
                }

                if (item.item.isNotEmpty()) {
                    traverse(item.item)
                }
            }
        }

        traverse(this.item)
        return result
    }

    fun extractAnswerValue(
        answer: QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent
    ): String? {
        return when {
            answer.hasValueStringType() ->
                answer.valueStringType.value

            answer.hasValueBooleanType() ->
                answer.valueBooleanType.booleanValue().toString()

            answer.hasValueIntegerType() ->
                answer.valueIntegerType.value.toString()

            answer.hasValueDecimalType() ->
                answer.valueDecimalType.value.toString()

            answer.hasValueCoding() ->
                answer.valueCoding.code ?: answer.valueCoding.display

            answer.hasValueReference() ->
                answer.valueReference.reference
                    ?: answer.valueReference.display

            else -> null
        }
    }

    private fun getUserProfile() {
        retrofitCallsAuthentication.getUserProfile(viewModel, this)
    }

    private fun checkLocationPermission() {
        val fineGranted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            checkGpsEnabled()
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Location Permission Needed")
            .setMessage("This app requires location access to work properly. Please enable it in settings.")
            .setCancelable(false)
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
                startActivity(intent)
            }
            .setNegativeButton("Exit App") { _, _ ->
                finishAffinity()
            }
            .show()
    }

    /** 3️⃣ Check if GPS is enabled **/
    private fun checkGpsEnabled() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

        if (!gpsEnabled) {
            showGpsDialog()
        }
    }

    private fun showGpsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Enable GPS")
            .setMessage("Your location services are turned off. Please enable GPS to continue.")
            .setCancelable(false)
            .setPositiveButton("Open Location Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Exit App") { _, _ ->
                finishAffinity()
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val latitude = location.latitude
                val longitude = location.longitude

                FormatterClass().saveSharedPref("latitude", latitude.toString(), this)
                FormatterClass().saveSharedPref("longitude", longitude.toString(), this)

            } else {
                // If no last known location, request a fresh one
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    mainLooper
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            getCurrentLocation()
        } else {
//            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
            // show a confirmation alert dialog to exit app with reason permission required

        }
    }

    private fun checkForAppUpdate() {
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo

        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                when {
                    // Try flexible first
                    appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> {
                        try {
                            appUpdateManager.startUpdateFlowForResult(
                                appUpdateInfo,
                                AppUpdateType.FLEXIBLE,
                                this,
                                UPDATE_REQUEST_CODE
                            )
                        } catch (e: IntentSender.SendIntentException) {
                            Log.e("AppUpdate", "Flexible update error: ${e.message}")
                        }
                    }

                    // Fallback to immediate
                    appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> {
                        try {
                            appUpdateManager.startUpdateFlowForResult(
                                appUpdateInfo,
                                AppUpdateType.IMMEDIATE,
                                this,
                                UPDATE_REQUEST_CODE
                            )
                        } catch (e: IntentSender.SendIntentException) {
                            Log.e("AppUpdate", "Immediate update error: ${e.message}")
                        }
                    }

                    else -> {
                        Log.d("AppUpdate", "Update available but not allowed")
                    }
                }
            } else {
                Log.d("AppUpdate", "No update available")
            }
        }.addOnFailureListener {
            Log.e("AppUpdate", "Failed to check for update: ${it.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UPDATE_REQUEST_CODE) {
            if (resultCode != RESULT_OK) {
                Log.e("AppUpdate", "Update flow failed! Result code: $resultCode")
                // Handle retry logic if necessary
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Resume update if it was started before (for FLEXIBLE only)
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    AppUpdateType.IMMEDIATE,
                    this,
                    UPDATE_REQUEST_CODE
                )
            }
        }

        appUpdateManager
            .appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.installStatus() == com.google.android.play.core.install.model.InstallStatus.DOWNLOADED) {
                    // Prompt the user to restart the app
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "An update has just been downloaded.",
                        Snackbar.LENGTH_INDEFINITE
                    ).setAction("Restart") {
                        appUpdateManager.completeUpdate()
                    }.show()
                }
            }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_sync, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_restart -> {
                lifecycleScope.launch {
                    try {
                        val trackedResources = listOf(
                            ResourceType.Patient,
                            ResourceType.Observation,
                            ResourceType.Encounter,
                            ResourceType.Immunization,
                            ResourceType.QuestionnaireResponse,
                            ResourceType.Condition,
                            ResourceType.MeasureReport
                        )
                        DemoDataStore(this@MainActivity).clearAllTimestamps(trackedResources)
                        viewModel.triggerOneTimeSync()

                        Toast.makeText(this@MainActivity, "Sync Started ... ", Toast.LENGTH_SHORT)
                            .show()

                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                true
            }

            R.id.action_refresh -> {
                viewModel.triggerOneTimeSync()

                // Display toast to show sync has started
                Toast.makeText(this, "Sync Started ... ", Toast.LENGTH_SHORT).show()
//                  startActivity(Intent(this@MainActivity, SyncActivity::class.java))
//                processBackupSync()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun processBackupSync() {
        val repository = FhirPaginatedRepository(fhirEngine)
        backupSyncViewModel.loadLocalResources("Patient")
        val resources = backupSyncViewModel.resources.value
        val token = FormatterClass().getSharedPref("access_token", this@MainActivity)

        println("Resource Count: ${resources.size}")
        if (token != null) {
            lifecycleScope.launch {
                resources.forEach { resourceData ->
                    println("Resource Type: ${resourceData.resource.idElement.idPart}")
                    val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
                    val bundle = org.hl7.fhir.r4.model.Bundle().apply {
                        id = "upload-bundle-${System.currentTimeMillis()}"
                        type = org.hl7.fhir.r4.model.Bundle.BundleType.TRANSACTION
                        timestamp = Date()
                    }
                    val entry = org.hl7.fhir.r4.model.Bundle.BundleEntryComponent().apply {
                        fullUrl =
                            "${resourceData.resource.resourceType}/${resourceData.resource.logicalId}"
                        val requestPayload =
                            org.hl7.fhir.r4.model.Bundle.BundleEntryRequestComponent().apply {
                                method = org.hl7.fhir.r4.model.Bundle.HTTPVerb.PUT
                                url =
                                    "${resourceData.resource.resourceType}/${resourceData.resource.logicalId}"
                            }
                        request = requestPayload
                        resource = resourceData.resource
                    }
                    bundle.addEntry(entry)
                    val resources = repository.fetchPatientRelatedResources(
                        fhirEngine = fhirEngine,
                        patientId = resourceData.resource.idElement.idPart,
                        resourceTypes = listOf(
                            ResourceType.Encounter,
                            ResourceType.Observation,
                            ResourceType.QuestionnaireResponse,
                            ResourceType.Specimen,
                            ResourceType.MeasureReport
                        ),
                        pageSize = 200,
                        onlyMissingLastUpdated = true
                    )
                    resources.forEach { data ->
                        println("Resource Child: ${data.resourceType} -> ${data.idElement.idPart} ")
                        val entry = org.hl7.fhir.r4.model.Bundle.BundleEntryComponent().apply {
                            fullUrl = "${data.resourceType}/${data.logicalId}"
                            val requestPayload =
                                org.hl7.fhir.r4.model.Bundle.BundleEntryRequestComponent().apply {
                                    method = org.hl7.fhir.r4.model.Bundle.HTTPVerb.PUT
                                    url = "${data.resourceType}/${data.logicalId}"
                                }
                            request = requestPayload
                            resource = data
                        }
                        val questionnaireResponseString =
                            jsonParser.encodeResourceToString(data)
                        println("Resource Entry: $questionnaireResponseString ")
                        bundle.addEntry(entry)

                    }
                    val questionnaireResponseString =
                        jsonParser.encodeResourceToString(bundle)
                    println("Resource Bundle: $questionnaireResponseString ")
                    backupSyncViewModel.uploadBundle(bundle, token = token)
                }
            }
        }

    }

}
