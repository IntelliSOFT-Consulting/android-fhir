package com.icl.surveillance.clients

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.fasterxml.jackson.databind.type.ReferenceType
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.QuestionnaireFragment
import com.google.android.fhir.datacapture.mapping.ResourceMapper
import com.google.android.fhir.search.StringFilterModifier
import com.google.android.fhir.search.search
import com.google.android.material.button.MaterialButton
import com.icl.surveillance.R
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FRAGMENT_TAG
import com.icl.surveillance.databinding.ActivityAddParentCaseBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.models.FacilityInfo
import com.icl.surveillance.models.QuestionnaireAnswer
import com.icl.surveillance.models.UserRole
import com.icl.surveillance.utils.ContribQuestionnaireItemViewHolderFactoryMatchersProviderFactory
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.LocationUtils
import com.icl.surveillance.utils.ProgressDialogManager
import com.icl.surveillance.viewmodels.AddClientViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.Location
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.StringType
import org.json.JSONObject

class AddParentCaseActivity : AppCompatActivity() {
    private val LOCATION_PERMISSION_REQUEST_CODE = 100
    private val viewModel: AddClientViewModel by viewModels()
    private lateinit var binding:
            ActivityAddParentCaseBinding // Binding class name is based on layout file name

    private fun getStringFromAssets(fileName: String): String {
        return assets.open(fileName).bufferedReader().use { it.readText() }
    }

    private lateinit var fhirEngine: FhirEngine
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAddParentCaseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        fhirEngine = FhirApplication.fhirEngine(this@AddParentCaseActivity)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val titleName = FormatterClass().getSharedPref("AddParentTitle", this@AddParentCaseActivity)
        supportActionBar.apply { title = titleName }

//        checkAndRequestLocationPermission()
        updateArguments()
        if (savedInstanceState == null) {
            addQuestionnaireFragment()
        }
        observePatientSaveAction()

        supportFragmentManager.setFragmentResultListener(
            QuestionnaireFragment.SUBMIT_REQUEST_KEY,
            this@AddParentCaseActivity,
        ) { _, _ ->
            onSubmitAction()
        }
        supportFragmentManager.setFragmentResultListener(
            QuestionnaireFragment.CANCEL_REQUEST_KEY,
            this@AddParentCaseActivity,
        ) { _, _ ->
            onBackPressed()
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun checkAndRequestLocationPermission() {
        val fineLocationPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarseLocationPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (fineLocationPermission != PackageManager.PERMISSION_GRANTED ||
            coarseLocationPermission != PackageManager.PERMISSION_GRANTED
        ) {
            AlertDialog.Builder(this@AddParentCaseActivity)
                .setTitle("Location Permission Needed")
                .setMessage("We need your location to provide better services. Please allow location access.")
                .setPositiveButton("Allow") { dialog, _ ->
                    dialog.dismiss()
                    if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        ActivityCompat.requestPermissions(
                            this@AddParentCaseActivity,
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ),
                            LOCATION_PERMISSION_REQUEST_CODE
                        )
                    } else {
                        openAppSettings()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            // Permission already granted
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startLocationUpdates()
            }
        }
    }


    private fun startLocationUpdates() {
        LocationUtils.requestCurrentLocation(
            this,
            onLocationReceived = { lat, lon ->

                val latitude = lat.toString()
                val longitude = lon.toString()
                FormatterClass().saveSharedPref("latitude", latitude, this)
                FormatterClass().saveSharedPref("longitude", longitude, this)
            },
            onError = { error ->
                println("Error: $error")

            }
        )
    }


    private fun onSubmitAction() {
        ProgressDialogManager.show(this, "Please Wait.....")
        lifecycleScope.launch {
            val questionnaireFragment =
                supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG)
                        as QuestionnaireFragment


            saveCase(questionnaireFragment.getQuestionnaireResponse())
        }
    }


    private fun showCancelScreenerQuestionnaireAlertDialog() {
        val builder = AlertDialog.Builder(this)
        builder.apply {
            setMessage(getString(R.string.cancel_questionnaire_message))
            setPositiveButton(getString(android.R.string.yes)) { _, _ ->
                this@AddParentCaseActivity.finish()
            }
            setNegativeButton(getString(android.R.string.no)) { _, _ -> }
        }
        val alertDialog = builder.create()
        alertDialog.show()
    }

    private fun saveCase(
        questionnaireResponse: QuestionnaireResponse
    ) {
        val case = FormatterClass().getSharedPref("currentCase", this@AddParentCaseActivity)

        when (case) {
            "Mpox - Supervisor Checklist" -> {
                viewModel.saveUserResponse(questionnaireResponse, case, this@AddParentCaseActivity)

            }

            else -> {
                    viewModel.savePatientData(
                        questionnaireResponse,
                        this@AddParentCaseActivity
                    )
            }
        }

    }

    override fun onBackPressed() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Exit")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Yes") { _, _ ->
                super.onBackPressed() // Exit the activity
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss() // Dismiss the dialog
            }
            .create()

        dialog.show()
    }

    private fun addUserCountyResponse(
        userCounty: String,
        county: String
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent {
        val formatter = FormatterClass()
        val county = formatter.getSharedPref(county, this@AddParentCaseActivity)
        val item =
            QuestionnaireResponse.QuestionnaireResponseItemComponent()

        item.linkId = userCounty
        item.answerFirstRep.value = StringType(county)
        return item

    }

    fun createCountyAnswer(
        ref: String,
        dis: String,
        id: String,
        label: String
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent {

        val reference = Reference().apply {
            reference = "Location/$ref"
            display = dis
        }

        return QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            linkId = id
            text = label
            answerFirstRep.value = reference
        }
    }

    private fun addQuestionnaireFragment() {
        lifecycleScope.launch(Dispatchers.Default) {

            // Prepare FHIR context ONCE in background
            val fhirContext = FhirContext.forR4Cached()
            val jsonParser = fhirContext.newJsonParser()

            // 1. Build QuestionnaireResponse object
            val resource = QuestionnaireResponse()
            val formatter = FormatterClass()
            val storedRole = formatter.getSharedPref("practitionerRole", this@AddParentCaseActivity)
            val userRole = UserRole.fromAny(storedRole ?: "")

            when (userRole) {

                UserRole.ADMINISTRATOR -> {
                    val childGroup =
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "151479012557"
                            text = "Reporting Site"
                        }
                    childGroup.addItem(
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "user_role"
                            text = "User Role"
                            answerFirstRep.value = StringType("ADMINISTRATOR")
                        }
                    )
                    resource.addItem(childGroup)
                }

                UserRole.COUNTY_DISEASE_SURVEILLANCE_OFFICER -> {
                    val childGroup =
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "151479012557"
                            text = "Reporting Site"
                        }
                    childGroup.addItem(
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "user_role"
                            text = "User Role"
                            answerFirstRep.value = StringType("COUNTY_DISEASE_SURVEILLANCE_OFFICER")
                        }
                    )

                    val userCounty = addUserCountyResponse("user_county", "county")

                    val county = createCountyAnswer(
                        getAssignedLocation("county"),
                        getAssignedLocation("countyName"),
                        "294367770999_county", "County"
                    )
                    val countyLevelGroup =
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "county_level"
                            text = " "
                        }


                    countyLevelGroup.addItem(county)
                    childGroup.addItem(countyLevelGroup)
                    childGroup.addItem(userCounty)
                    resource.addItem(childGroup)
                }

                UserRole.SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER -> {
                    val childGroup =
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "151479012557"
                            text = "Reporting Site"
                        }
                    childGroup.addItem(
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "user_role"
                            text = "User Role"
                            answerFirstRep.value =
                                StringType("SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER")
                        }
                    )

                    childGroup.addItem(addUserCountyResponse("user_county", "county"))
                    childGroup.addItem(addUserCountyResponse("user_sub_county", "subCounty"))

                    val subCountyLevelGroup =
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "sub_county_level"
                            text = " "
                        }

                    val county = createCountyAnswer(
                        getAssignedLocation("county"),
                        getAssignedLocation("countyName"),
                        "294367770999_sub_county", "County"
                    )

                    val subCounty = createCountyAnswer(
                        getAssignedLocation("subCounty"),
                        getAssignedLocation("subCountyName"),
                        "819946803642_sub_county", "Sub County"
                    )
                    subCountyLevelGroup.addItem(county)
                    subCountyLevelGroup.addItem(subCounty)
                    childGroup.addItem(subCountyLevelGroup)
                    resource.addItem(childGroup)
                }

                UserRole.FACILITY_SURVEILLANCE_FOCAL_PERSON,
                UserRole.SUPERVISOR,
                UserRole.VACCINATOR -> {

                    val childGroup =
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "151479012557"
                            text = "Reporting Site"
                        }

                    childGroup.addItem(
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "user_role"
                            text = "User Role"
                            answerFirstRep.value = StringType("VACCINATOR")
                        }
                    )

                    childGroup.addItem(
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "user_facility"
                            text = "User Facility"
                            answerFirstRep.value = StringType(getAssignedLocation("facility"))
                        }
                    )

                    childGroup.addItem(
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "user_ward"
                            text = "User Ward"
                            answerFirstRep.value = StringType(getAssignedLocation("ward"))
                        }
                    )

                    childGroup.addItem(
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "user_sub_county"
                            text = "User Sub County"
                            answerFirstRep.value = StringType(getAssignedLocation("subCounty"))
                        }
                    )

                    childGroup.addItem(
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "user_county"
                            text = "User County"
                            answerFirstRep.value = StringType(getAssignedLocation("county"))
                        }
                    )

                    val facilityLevelGroup =
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "facility_level"
                            text = " "
                        }

                    val county = createCountyAnswer(
                        getAssignedLocation("county"),
                        getAssignedLocation("countyName"),
                        "294367770999", "County"
                    )

                    val subCounty = createCountyAnswer(
                        getAssignedLocation("subCounty"),
                        getAssignedLocation("subCountyName"),
                        "819946803642", "Sub County"
                    )

                    val ward = createCountyAnswer(
                        getAssignedLocation("ward"),
                        getAssignedLocation("wardName"),
                        "819943434", "Ward"
                    )

                    val facility = createCountyAnswer(
                        getAssignedLocation("facility"),
                        getAssignedLocation("facilityName"),
                        "819946803677", "Health Facility"
                    )

                    facilityLevelGroup.addItem(county)
                    facilityLevelGroup.addItem(subCounty)
                    facilityLevelGroup.addItem(ward)
                    facilityLevelGroup.addItem(facility)
                    childGroup.addItem(facilityLevelGroup)

                    resource.addItem(childGroup)
                }

                else -> { /* No-op */
                }
            }

            // 2. Serialize resource INTO JSON (expensive → done on background thread)
            val questionnaireResponseJson = jsonParser.encodeResourceToString(resource)

            // 3. Prepare questionnaire JSON (if it’s very large)
            val questionnaireJson = viewModel.questionnaireJson

            // 4. Return to main thread for fragment transaction
            withContext(Dispatchers.Main) {

                println("Starter Response: $questionnaireResponseJson")

                if (supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG) == null) {
                    supportFragmentManager.commit {
                        setReorderingAllowed(true)

                        val fragmentBuilder = QuestionnaireFragment.builder().apply {
                            setShowSubmitAnywayButton(false)
                            setQuestionnaireResponse(questionnaireResponseJson)
                            setCustomQuestionnaireItemViewHolderFactoryMatchersProvider(
                                ContribQuestionnaireItemViewHolderFactoryMatchersProviderFactory
                                    .LOCATION_WIDGET_PROVIDER
                            )
                            setQuestionnaire(questionnaireJson)
                        }

                        add(
                            R.id.add_patient_container,
                            fragmentBuilder.build(),
                            QUESTIONNAIRE_FRAGMENT_TAG
                        )
                    }
                }
            }
        }
    }


    fun getAssignedLocation(type: String): String {
        var value = ""
        val source = FormatterClass().getSharedPref(type, this@AddParentCaseActivity)
        if (source != null) {
            value = source
        }

        return value
    }

    private fun observePatientSaveAction() {
        viewModel.isPatientSaved.observe(this) {
            ProgressDialogManager.dismiss()

            if (!it) {
                Toast.makeText(this, "Please Enter all Required Fields.", Toast.LENGTH_SHORT).show()
                return@observe
            }
            showSuccessDialog(this@AddParentCaseActivity)

        }
    }

    fun showSuccessDialog(context: Context) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.success_dialog, null)
        val alertDialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
            alertDialog.dismiss()
            this@AddParentCaseActivity.finish()
        }

        dialogView.findViewById<MaterialButton>(R.id.btn_finish).setOnClickListener {
            // handle finish action
            this@AddParentCaseActivity.finish()
            alertDialog.dismiss()
        }

        alertDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        alertDialog.show()
    }

    private fun updateArguments() {
        val json = FormatterClass().getSharedPref("questionnaire", this@AddParentCaseActivity)
        intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, json)
    }

    override fun onSupportNavigateUp(): Boolean {
        showCancelScreenerQuestionnaireAlertDialog()
        return true
    }
}



