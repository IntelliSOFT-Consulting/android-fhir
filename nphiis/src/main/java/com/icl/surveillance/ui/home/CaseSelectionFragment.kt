package com.icl.surveillance.ui.home

import com.icl.surveillance.clients.UnfinishedDraftPrompt
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.fhir.FhirEngine
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.icl.surveillance.R
import com.icl.surveillance.adapters.CaseOptionsAdapter
import com.icl.surveillance.cases.CaseListingActivity
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.clients.AddParentCaseActivity
import com.icl.surveillance.clients.ClientLookupActivity
import com.icl.surveillance.clients.ScreenedCasesActivity
import com.icl.surveillance.databinding.FragmentCaseSelectionBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.CaseSlugs
import com.icl.surveillance.fhir.forms.CaseTypes
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.VhfScreened
import com.icl.surveillance.fhir.forms.VhfCaseConfirmation
import com.icl.surveillance.fhir.forms.VhfListMode
import com.icl.surveillance.fhir.forms.VhfTraining
import com.icl.surveillance.models.CaseOption
import com.icl.surveillance.models.LocationLevel
import com.icl.surveillance.models.UserRole
import com.icl.surveillance.ui.home.sheet.SelectionBottomSheet
import com.icl.surveillance.ui.patients.PatientListViewModel
import com.icl.surveillance.utils.FormatterClass
import kotlin.collections.filter
import kotlin.getValue
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import timber.log.Timber

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass. Use the [CaseSelectionFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class CaseSelectionFragment : Fragment() {
    // TODO: Rename and change types of parameters
    private var param1: String? = null
    private var param2: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }
    }

    private var _binding: FragmentCaseSelectionBinding? = null
    private val binding
        get() = _binding!!

    private val addParentCaseLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && isAdded) {
                setupRecyclerView()
            }
        }

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var fhirEngine: FhirEngine
    private lateinit var patientListViewModel: PatientListViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        // Inflate the layout for this fragment
        _binding = FragmentCaseSelectionBinding.inflate(inflater, container, false)
        val root: View = binding.root

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val titleName = FormatterClass().getSharedPref("grandTitle", requireContext())

        val activity = requireActivity() as AppCompatActivity
        activity.supportActionBar?.apply {
            title = ""
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        // Let the Fragment receive menu callbacks
        setHasOptionsMenu(true)

        fhirEngine = FhirApplication.fhirEngine(requireContext())
        patientListViewModel =
            ViewModelProvider(
                this,
                PatientListViewModel.PatientListViewModelFactory(
                    requireActivity().application,
                    fhirEngine,
                ),
            )
                .get(PatientListViewModel::class.java)

        binding.apply { greeting.text = titleName }
        if (titleName != null) {
            setupRecyclerView()
        }

        childFragmentManager.setFragmentResultListener(
            SelectionBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val option = FormatterClass().getSharedPref("selected_option", requireContext())
            when (bundle.getInt(SelectionBottomSheet.ARG_CHOICE)) {
                SelectionBottomSheet.CHOICE_COUNTY -> {
                    val isMpox = option == "Add New Mpox Case"

                    val currentCase =
                        if (isMpox) CaseTypes.MPOX_TALLY_SHEET else CaseTypes.RCCE_COUNTY
                    val addParentTitle =
                        if (isMpox) "Mpox - Tally Sheet" else "County/Subcounty Interface"
                    val questionnaireFile =
                        if (isMpox) "mpox-tally-sheet.json" else "social-county.json"

                    with(FormatterClass()) {
                        saveSharedPref("currentCase", currentCase, requireContext())
                        saveSharedPref("AddParentTitle", addParentTitle, requireContext())
                        saveSharedPref("questionnaire", questionnaireFile, requireContext())
                    }
                    launchCaseFlow(
                        requireContext(),
                        " $titleName",
                        currentCase,
                        addParentTitle,
                        questionnaireFile,
                    )
                }

                SelectionBottomSheet.CHOICE_COMMUNITY -> {
                    val isMpox = option == "Add New Mpox Case"

                    val currentCase =
                        if (isMpox) CaseTypes.MPOX_SUPERVISOR_CHECKLIST else CaseTypes.RCCE_COMMUNITY
                    val addParentTitle =
                        if (isMpox) "Mpox - Supervisor Checklist" else "Community Questionnaire"
                    val questionnaireFile =
                        if (isMpox) "mpox-supervisor-checklist.json" else "social-community.json"

                    with(FormatterClass()) {
                        saveSharedPref("currentCase", currentCase, requireContext())
                        saveSharedPref("AddParentTitle", addParentTitle, requireContext())
                        saveSharedPref("questionnaire", questionnaireFile, requireContext())
                    }
                    launchCaseFlow(
                        requireContext(),
                        " $titleName",
                        currentCase,
                        addParentTitle,
                        questionnaireFile,
                    )
                }
            }
        }
    }

    /**
     * Launches the case flow for adding a new parent case.
     *
     * @param context The context.
     * @param titleName The title of the case.
     * @param currentCase The type of the current case.
     * @param addParentTitle The title for the add parent case activity.
     * @param questionnaireFile The questionnaire file to be used.
     */
    private fun launchCaseFlow(
        context: Context,
        titleName: String,
        currentCase: String,
        addParentTitle: String,
        questionnaireFile: String,
    ) {
        with(FormatterClass()) {
            saveSharedPref("currentCase", currentCase, context)
            saveSharedPref("AddParentTitle", addParentTitle, context)
            saveSharedPref("questionnaire", questionnaireFile, context)
        }

        val intent =
            Intent(context, AddParentCaseActivity::class.java).apply {
                putExtra("AddParentTitle", " $titleName")
                putExtra(QUESTIONNAIRE_FILE_PATH_KEY, questionnaireFile)
            }
        addParentCaseLauncher.launch(intent)
    }

    /** Sets up the RecyclerView with the case options. */
    private fun setupRecyclerView() {
        val titleName = FormatterClass().getSharedPref("grandTitle", requireContext())

        val title =
            when (titleName) {
                "Visceral Leishmaniasis (Kala-azar) Case Management Form" -> "VL"
                "Visceral Leishmaniasis Case Management Form" -> "VL"
                getString(R.string.vhf_form) -> "VHF"
                getString(R.string.trainings) -> "VHF Training"
                getString(R.string.alert_log) -> ALERT_LOG_TITLE
                "Social Listening and Rumor Tracking Tool" -> "SLR"
                "RCCE Tools" -> "RCCE"
                else -> titleName
            }
        val add =
            when (title) {
                "SLR" -> "Add New Report"
                "MOH 505" -> "Add New Record"
                "Social Investigation Form" -> "Add New Social Investigation Form"
                "Summary Sheet" -> "Add New Team Record"
                "Mpox Register" -> "Add New Mpox Register"
                ALERT_LOG_TITLE -> ADD_ALERT
                "VHF" -> VHF_ADD
                else -> "Add New $title Case"
            }
        val view =
            when (title) {
                "SLR" -> "View Reported Cases"
                "Social Investigation Form" -> "Social Investigation Reports"
                "Mpox Register" -> "Mpox Register List"
                ALERT_LOG_TITLE -> ALERT_LOG_LIST
                "VHF" -> VHF_LIST
                else -> "$title Case List"
            }
        val caseOptions =
            mutableListOf(CaseOption(add), CaseOption(view, showCount = true, count = 0))
        // VHF: a 2-column grid of the lists by Type of case: Suspected, Confirmed, Contacts and
        // Flagged (screened people awaiting investigation). Adding a suspected case is in the
        // ⋮ menu (hidden here until confirmed).
        val isVhf = title == "VHF"
        if (isVhf) {
            caseOptions.clear()
            caseOptions += CaseOption(VHF_LIST, showCount = true, icon = R.drawable.ic_vhf_warning)
            caseOptions += CaseOption(VHF_CONFIRMED, showCount = true, icon = R.drawable.ic_vhf_check_circle)
            caseOptions += CaseOption(VHF_CONTACTS, showCount = true, icon = R.drawable.people)
            caseOptions += CaseOption(SCREENED_CASES, showCount = true, icon = R.drawable.ic_vhf_info)
        }
        val viewIndex = caseOptions.indexOfFirst { it.title == (if (isVhf) VHF_LIST else view) }
        val confirmedIndex = caseOptions.indexOfFirst { it.title == VHF_CONFIRMED }
        val contactsIndex = caseOptions.indexOfFirst { it.title == VHF_CONTACTS }
        val screenedIndex = caseOptions.indexOfFirst { it.title == SCREENED_CASES }

        // VHF and VHF Training share the VHF workflow; the mode decides which records are listed
        // and whether new ones are marked as training (test_xxxx).
        if (title == "VHF" || title == "VHF Training") VhfTraining.setOn(requireContext(), title == "VHF Training")

        val recyclerView = requireView().findViewById<RecyclerView>(R.id.sdcLayoutsRecyclerView)
        recyclerView.layoutManager =
            if (isVhf) androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
            else LinearLayoutManager(requireContext())
        FormatterClass().deleteSharedPref("selected_option", requireContext())
        recyclerView.adapter =
            CaseOptionsAdapter(caseOptions, asTiles = isVhf) { option ->
                when (option.title) {
                    "Add New Team Record" -> {
                        val currentCase = "Mpox - Tally Sheet"
                        val addParentTitle = "Add New Team Record"
                        val questionnaireFile = "mpox-tally-sheet.json"

                        with(FormatterClass()) {
                            saveSharedPref("currentCase", currentCase, requireContext())
                            saveSharedPref("AddParentTitle", addParentTitle, requireContext())
                            saveSharedPref("questionnaire", questionnaireFile, requireContext())
                        }
                        val canPerformAction = checkIfUserIsAllowedToAction()
                        if (!canPerformAction) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        launchCaseFlow(
                            requireContext(),
                            " $titleName",
                            currentCase,
                            addParentTitle,
                            questionnaireFile,
                        )
                    }

                    "Add New Supervisor Checklist Case" -> {
                        val currentCase = CaseTypes.MPOX_SUPERVISOR_CHECKLIST
                        val addParentTitle = "Add New Supervisor Checklist"
                        val questionnaireFile = "mpox-supervisor-checklist.json"

                        with(FormatterClass()) {
                            saveSharedPref("currentCase", currentCase, requireContext())
                            saveSharedPref("AddParentTitle", addParentTitle, requireContext())
                            saveSharedPref("questionnaire", questionnaireFile, requireContext())
                        }

                        val canPerformAction = checkIfUserIsAllowedToAction()
                        if (!canPerformAction) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        launchCaseFlow(
                            requireContext(),
                            " $titleName",
                            currentCase,
                            addParentTitle,
                            questionnaireFile,
                        )
                    }

                    "Add New Mpox Case" -> {
                        FormatterClass()
                            .saveSharedPref(
                                "selected_option",
                                "Add New Mpox Case",
                                requireContext()
                            )

                        val canPerformAction = checkIfUserIsAllowedToAction()
                        if (!canPerformAction) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        val sheet =
                            SelectionBottomSheet.newInstance("Tally Sheet", "Supervisor Checklist")
                        sheet.show(childFragmentManager, "SelectionBottomSheet")
                    }

                    "Add New Social Investigation Form" -> {

                        val canPerformAction = checkIfUserIsAllowedToAction()
                        if (!canPerformAction) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        SelectionBottomSheet.show(childFragmentManager)
                    }

                    "Social Investigation Reports" -> {
                        FormatterClass()
                            .saveSharedPref(
                                "listingTitle",
                                "Social Investigation Form",
                                requireContext()
                            )
                        FormatterClass().saveSharedPref("currentCase", "RCCE", requireContext())

                        val canPerformAction = checkIfUserIsAllowedToAction()
                        if (!canPerformAction) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        val intent = Intent(requireContext(), CaseListingActivity::class.java)
                        startActivity(intent)
                    }

                    "Add New Recordxx" -> { /* Remember to bring it down*/
                        FormatterClass()
                            .saveSharedPref(
                                "currentCase",
                                CaseTypes.MOH_505,
                                requireContext()
                            )
                        FormatterClass()
                            .saveSharedPref(
                                "AddParentTitle",
                                "MOH 505 Reporting Form",
                                requireContext()
                            )
                        FormatterClass().saveSharedPref(
                            "questionnaire",
                            "moh505.json",
                            requireContext()
                        )

                        val canPerformAction = checkIfUserIsAllowedToAction()
                        if (!canPerformAction) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        val intent = Intent(requireContext(), AddParentCaseActivity::class.java)
                        intent.putExtra("AddParentTitle", " $titleName")
                        intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, "moh505.json")
                        addParentCaseLauncher.launch(intent)
                    }

                    "Add New Mpox Register" -> {
                        FormatterClass().saveSharedPref(
                            "currentCase",
                            CaseTypes.MPOX_REGISTER,
                            requireContext()
                        )
                        FormatterClass().saveSharedPref(
                            "AddParentTitle",
                            "Mpox Register",
                            requireContext()
                        )
                        FormatterClass()
                            .saveSharedPref("questionnaire", "mpox-register.json", requireContext())

                        val canPerformAction = checkIfUserIsAllowedToAction()
                        if (!canPerformAction) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        val intent = Intent(requireContext(), AddParentCaseActivity::class.java)
                        intent.putExtra("AddParentTitle", " $titleName")
                        intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, "mpox-register.json")
                        addParentCaseLauncher.launch(intent)
                    }

                    "MOH 505 Case List" -> {
                        FormatterClass()
                            .saveSharedPref(
                                "listingTitle",
                                "MOH 505 Reporting Form",
                                requireContext()
                            )
                        FormatterClass()
                            .saveSharedPref(
                                "currentCase",
                                CaseTypes.MOH_505,
                                requireContext()
                            )
                        val intent = Intent(requireContext(), CaseListingActivity::class.java)
                        startActivity(intent)
                    }

                    "Summary Sheet Case List" -> {
                        FormatterClass()
                            .saveSharedPref(
                                "listingTitle",
                                "Mpox - Summary Sheet",
                                requireContext()
                            )
                        FormatterClass().saveSharedPref(
                            "currentCase",
                            CaseTypes.MPOX_TALLY_SHEET,
                            requireContext()
                        )
                        val intent = Intent(requireContext(), CaseListingActivity::class.java)
                        startActivity(intent)
                    }

                    "Supervisor Checklist Case List" -> {
                        FormatterClass()
                            .saveSharedPref(
                                "listingTitle",
                                "Mpox - Supervisor Checklist",
                                requireContext()
                            )
                        FormatterClass()
                            .saveSharedPref(
                                "currentCase",
                                CaseTypes.MPOX_SUPERVISOR_CHECKLIST,
                                requireContext()
                            )
                        val intent = Intent(requireContext(), CaseListingActivity::class.java)
                        startActivity(intent)
                    }

                    "Add New Report" -> {
                        FormatterClass()
                            .saveSharedPref(
                                "currentCase",
                                CaseTypes.RUMOR,
                                requireContext(),
                            )
                        FormatterClass()
                            .saveSharedPref(
                                "AddParentTitle",
                                "Social Listening and Rumor Tracking Tool",
                                requireContext(),
                            )
                        FormatterClass()
                            .saveSharedPref(
                                "questionnaire",
                                "rumor-tracking-case.json",
                                requireContext()
                            )

                        val canPerformAction = checkIfUserIsAllowedToAction()
                        if (!canPerformAction) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        val intent = Intent(requireContext(), AddParentCaseActivity::class.java)
                        intent.putExtra("AddParentTitle", " $titleName")
                        intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, "rumor-tracking-case.json")
                        addParentCaseLauncher.launch(intent)
                    }

                    "Add New VL Case" -> {
                        FormatterClass()
                            .saveSharedPref("currentCase", CaseTypes.VL, requireContext())
                        FormatterClass()
                            .saveSharedPref(
                                "AddParentTitle",
                                "Visceral Leishmaniasis Case Management Form",
                                requireContext(),
                            )
                        FormatterClass().saveSharedPref(
                            "questionnaire",
                            "vl-case.json",
                            requireContext()
                        )

                        val canPerformAction = checkIfUserIsAllowedToAction()
                        if (!canPerformAction) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        val intent = Intent(requireContext(), AddParentCaseActivity::class.java)
                        intent.putExtra("AddParentTitle", " $titleName")
                        intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, "vl-case.json")
                        addParentCaseLauncher.launch(intent)
                    }

                    VHF_ADD -> {
                        VhfTraining.setOn(requireContext(), false)
                        FormatterClass().saveSharedPref("currentCase", CaseTypes.VHF, requireContext())
                        FormatterClass().saveSharedPref(
                            "AddParentTitle",
                            getString(R.string.vhf_form),
                            requireContext(),
                        )
                        FormatterClass().saveSharedPref("questionnaire", VHF_QUESTIONNAIRE, requireContext())

                        if (!checkIfUserIsAllowedToAction()) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        openNewVhfCase(" $titleName")
                    }

                    "Add New VHF Training Case" -> {
                        VhfTraining.setOn(requireContext(), true)
                        FormatterClass().saveSharedPref("currentCase", CaseTypes.VHF, requireContext())
                        FormatterClass().saveSharedPref(
                            "AddParentTitle",
                            getString(R.string.vhf_form) + " (Training)",
                            requireContext(),
                        )
                        FormatterClass().saveSharedPref("questionnaire", VHF_QUESTIONNAIRE, requireContext())

                        if (!checkIfUserIsAllowedToAction()) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        openNewVhfCase(" $titleName")
                    }

                    "VHF Training Case List" -> {
                        VhfTraining.setOn(requireContext(), true)
                        FormatterClass().saveSharedPref("listingTitle", " ${option.title}", requireContext())
                        FormatterClass().saveSharedPref("currentCase", CaseTypes.VHF, requireContext())
                        startActivity(Intent(requireContext(), CaseListingActivity::class.java))
                    }

                    ADD_ALERT -> {
                        FormatterClass().saveSharedPref("currentCase", CaseTypes.ALERT_LOG, requireContext())
                        FormatterClass().saveSharedPref(
                            "AddParentTitle",
                            getString(R.string.alert_log),
                            requireContext(),
                        )
                        FormatterClass().saveSharedPref(
                            "questionnaire",
                            FormFields.AlertLog.FORM,
                            requireContext()
                        )
                        if (!checkIfUserIsAllowedToAction()) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        // The date the alert was received starts as today; it can be changed.
                        com.icl.surveillance.fhir.forms.FormPrefill.stage(
                            requireContext(), FormFields.AlertLog.FORM,
                            mapOf(FormFields.AlertLog.DATE to java.time.LocalDate.now().toString()),
                        )
                        val intent = Intent(requireContext(), AddParentCaseActivity::class.java)
                        intent.putExtra("AddParentTitle", " $titleName")
                        intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, FormFields.AlertLog.FORM)
                        addParentCaseLauncher.launch(intent)
                    }

                    ALERT_LOG_LIST -> {
                        FormatterClass().saveSharedPref("listingTitle", " ${option.title}", requireContext())
                        FormatterClass().saveSharedPref("currentCase", CaseTypes.ALERT_LOG, requireContext())
                        startActivity(Intent(requireContext(), CaseListingActivity::class.java))
                    }

                    SCREENED_CASES -> {
                        VhfTraining.setOn(requireContext(), false)
                        addParentCaseLauncher.launch(
                            Intent(requireContext(), ScreenedCasesActivity::class.java)
                        )
                    }

                    VHF_LIST, VHF_CONFIRMED, VHF_CONTACTS -> {
                        VhfTraining.setOn(requireContext(), false)
                        VhfListMode.set(
                            requireContext(),
                            when (option.title) {
                                VHF_CONTACTS -> VhfListMode.CONTACTS
                                VHF_CONFIRMED -> VhfListMode.CONFIRMED
                                else -> VhfListMode.CASES
                            },
                        )
                        // The list screens keep their full titles (the Contact List columns
                        // depend on "Contact List").
                        val listingTitle = when (option.title) {
                            VHF_CONTACTS -> "Contact List"
                            VHF_CONFIRMED -> "Confirmed Cases"
                            else -> "Suspected Cases"
                        }
                        FormatterClass().saveSharedPref("listingTitle", " $listingTitle", requireContext())
                        FormatterClass().saveSharedPref("currentCase", CaseTypes.VHF, requireContext())
                        startActivity(Intent(requireContext(), CaseListingActivity::class.java))
                    }

                    "Mpox Case List" -> {
                        FormatterClass().saveSharedPref(
                            "listingTitle",
                            " ${option.title}",
                            requireContext()
                        )
                        FormatterClass().saveSharedPref(
                            "currentCase",
                            CaseTypes.MPOX_INFORMATION,
                            requireContext()
                        )
                        val intent = Intent(requireContext(), CaseListingActivity::class.java)
                        startActivity(intent)
                    }

                    "VL Case List" -> {
                        FormatterClass().saveSharedPref(
                            "listingTitle",
                            " ${option.title}",
                            requireContext()
                        )
                        FormatterClass()
                            .saveSharedPref("currentCase", CaseTypes.VL, requireContext())
                        val intent = Intent(requireContext(), CaseListingActivity::class.java)
                        startActivity(intent)
                    }

                    "View Reported Cases" -> {
                        FormatterClass()
                            .saveSharedPref(
                                "listingTitle",
                                "Social Listening and Rumor Tracking Tool",
                                requireContext(),
                            )
                        FormatterClass()
                            .saveSharedPref(
                                "currentCase",
                                CaseTypes.RUMOR,
                                requireContext(),
                            )
                        val intent = Intent(requireContext(), CaseListingActivity::class.java)
                        startActivity(intent)
                    }

                    "Add New AFP Case" -> {

                        FormatterClass()
                            .saveSharedPref("currentCase", CaseTypes.AFP, requireContext())
                        FormatterClass()
                            .saveSharedPref(
                                "AddParentTitle",
                                "Add $titleName Case",
                                requireContext()
                            )
                        FormatterClass().saveSharedPref(
                            "questionnaire",
                            "afp-case.json",
                            requireContext()
                        )

                        val canPerformAction = checkIfUserIsAllowedToAction()
                        if (!canPerformAction) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        val intent = Intent(requireContext(), AddParentCaseActivity::class.java)
                        intent.putExtra("AddParentTitle", "Add $titleName Case")
                        intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, "afp-case.json")
                        addParentCaseLauncher.launch(intent)
                    }

                    "Add New Measles Case" -> {

                        FormatterClass()
                            .saveSharedPref(
                                "currentCase",
                                CaseTypes.MEASLES,
                                requireContext()
                            )
                        FormatterClass()
                            .saveSharedPref(
                                "AddParentTitle",
                                "Add $titleName Case",
                                requireContext()
                            )

                        val questionnaire = assignRespectiveQuestionnaire()
                        FormatterClass().saveSharedPref(
                            "questionnaire",
                            questionnaire,
                            requireContext()
                        )

                        val canPerformAction = checkIfUserIsAllowedToAction()
                        if (!canPerformAction) {
                            showPermissionErrorDialog(requireContext())
                            return@CaseOptionsAdapter
                        }
                        val intent = Intent(requireContext(), AddParentCaseActivity::class.java)
                        intent.putExtra("AddParentTitle", "Add $titleName Case")
                        intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, questionnaire)
                        addParentCaseLauncher.launch(intent)
                    }

                    "Measles Case List" -> {
                        FormatterClass().saveSharedPref(
                            "listingTitle",
                            " ${option.title}",
                            requireContext()
                        )
                        FormatterClass()
                            .saveSharedPref(
                                "currentCase",
                                CaseTypes.MEASLES,
                                requireContext()
                            )

                        val intent = Intent(requireContext(), CaseListingActivity::class.java)
                        startActivity(intent)
                    }

                    "AFP Case List" -> {
                        FormatterClass().saveSharedPref(
                            "listingTitle",
                            " ${option.title}",
                            requireContext()
                        )
                        FormatterClass()
                            .saveSharedPref("currentCase", CaseTypes.AFP, requireContext())
                        val intent = Intent(requireContext(), CaseListingActivity::class.java)
                        startActivity(intent)
                    }

                    "Mpox Register List" -> {
                        FormatterClass().saveSharedPref(
                            "listingTitle",
                            " ${option.title}",
                            requireContext()
                        )
                        FormatterClass().saveSharedPref(
                            "currentCase",
                            CaseTypes.MPOX_REGISTER,
                            requireContext()
                        )
                        val intent = Intent(requireContext(), CaseListingActivity::class.java)
                        startActivity(intent)
                    }

                    else -> {

                        FormatterClass().showComingSoon(requireContext())
                    }
                }
            }
        val units = FormatterClass().getFacilityIdsForWard(requireContext(), "units")

        val caseType =
            when (title?.trim()) {
                "Measles" -> CaseSlugs.MEASLES
                "AFP" -> CaseSlugs.AFP
                "VL" -> CaseSlugs.VL
                "VHF" -> CaseSlugs.VHF
                "VHF Training" -> CaseSlugs.VHF
                ALERT_LOG_TITLE -> CaseSlugs.ALERT_LOG
                "SLR" -> CaseSlugs.RUMOR
                "Social Investigation Form" -> CaseSlugs.RCCE
                "MOH 505" -> CaseSlugs.MOH_505
                "RCCE" -> CaseSlugs.RCCE
                "Mpox" -> CaseSlugs.MPOX_INFORMATION
                "Summary Sheet" -> CaseSlugs.MPOX_TALLY_SHEET
                "Supervisor Checklist" -> CaseSlugs.MPOX_SUPERVISOR_CHECKLIST
                "Mpox Register" -> CaseSlugs.MPOX_REGISTER
                else -> null
            }
        val formatter = FormatterClass()
        val storedRole = formatter.getSharedPref("practitionerRole", requireContext())
        val storedCounty = formatter.getSharedPref("countyName", requireContext())
        val storedSubCounty = formatter.getSharedPref("subCountyName", requireContext())
        val userRole = UserRole.fromAny(storedRole ?: "")
        val roleScope = userRole?.scope
        if (screenedIndex >= 0) {
            viewLifecycleOwner.lifecycleScope.launch {
                val screened = runCatching { VhfScreened.count(fhirEngine) }.getOrDefault(0)
                if (screenedIndex < caseOptions.size) {
                    caseOptions[screenedIndex] = caseOptions[screenedIndex].copy(count = screened)
                    recyclerView.adapter?.notifyDataSetChanged()
                }
            }
        }
        caseType?.let {
            try {
                when (it) {
                    CaseSlugs.MPOX_REGISTER -> {
                        patientListViewModel.simulateScrollUntilEnd(
                            it,
                            units,
                            userRole
                        ) { allPatients ->
                            val scopedCount = when (roleScope) {
                                LocationLevel.NATIONAL -> allPatients.size
                                LocationLevel.COUNTY -> {
                                    allPatients.count { case ->
                                        case.county.contains("$storedCounty", ignoreCase = true)
                                    }
                                }

                                LocationLevel.SUB_COUNTY -> {
                                    allPatients.count { case ->
                                        case.subCounty.contains(
                                            "$storedSubCounty",
                                            ignoreCase = true
                                        )
                                    }
                                }

                                else -> allPatients.size
                            }
                            caseOptions[viewIndex] = caseOptions[viewIndex].copy(count = scopedCount)
                            recyclerView.adapter?.notifyDataSetChanged()
                        }
                    }

                    CaseSlugs.RUMOR -> {
                        patientListViewModel.handleCurrentRumorCaseListing(it, units, userRole)
                        patientListViewModel.liveRumorCases.removeObservers(viewLifecycleOwner)
                        patientListViewModel.liveRumorCases.observe(viewLifecycleOwner) { cases ->
                            /**
                             * Let's update based on roles
                             * */

                            // Same scoping as the case list (CaseListingActivity.applyRoleScope).
                            val scopedCount = countInScope(
                                cases.map { case -> case.county to case.subCounty },
                                roleScope, storedCounty, storedSubCounty
                            )
                            caseOptions[viewIndex] = caseOptions[viewIndex].copy(count = scopedCount)

                            recyclerView.adapter?.notifyDataSetChanged()

                        }
                    }

                    else -> {
                        val loadAndCount = load@{
                            // Runs after a database read: the view may be gone by then.
                            val owner = viewLifecycleOwnerLiveData.value ?: return@load
                            patientListViewModel.handleCurrentCaseListing(it, units, userRole)
                            patientListViewModel.liveSearchedCases.removeObservers(owner)
                            patientListViewModel.liveSearchedCases.observe(owner) { cases ->

                                /**
                                 * Let's update based on roles
                                 * */

                                // Same scoping as the case list (CaseListingActivity.applyRoleScope).
                                // VHF records are open to every user, whatever their location.
                                val listScope = if (CaseSlugs.isOpenToAllLocations(it)) null else roleScope
                                fun scoped(list: List<PatientListViewModel.PatientItem>) = countInScope(
                                    list.map { case -> case.county to case.subCounty },
                                    listScope, storedCounty, storedSubCounty
                                )
                                if (contactsIndex >= 0 && contactsIndex < caseOptions.size) {
                                    // VHF: suspected, confirmed and contacts are counted apart.
                                    fun count(mode: String) = scoped(cases.filter { case ->
                                        VhfListMode.matches(mode, case.caseType, case.contactId, case.labResults)
                                    })
                                    caseOptions[viewIndex] = caseOptions[viewIndex].copy(count = count(VhfListMode.CASES))
                                    if (confirmedIndex >= 0) {
                                        caseOptions[confirmedIndex] = caseOptions[confirmedIndex].copy(count = count(VhfListMode.CONFIRMED))
                                    }
                                    caseOptions[contactsIndex] = caseOptions[contactsIndex].copy(count = count(VhfListMode.CONTACTS))
                                } else {
                                    caseOptions[viewIndex] = caseOptions[viewIndex].copy(count = scoped(cases))
                                }

                                recyclerView.adapter?.notifyDataSetChanged()
                            }
                        }
                        if (contactsIndex >= 0) {
                            // VHF: cases the lab confirmed move from Suspected to Confirmed
                            // before the lists are counted.
                            viewLifecycleOwner.lifecycleScope.launch {
                                runCatching {
                                    VhfCaseConfirmation.confirmLabConfirmedCases(
                                        FhirApplication.fhirEngine(requireContext())
                                    )
                                }.onFailure { e -> Timber.e(e, "Confirming lab-confirmed VHF cases failed") }
                                // runCatching also catches the cancellation when the view closes.
                                ensureActive()
                                loadAndCount()
                            }
                        } else if (roleScope == LocationLevel.COUNTY || roleScope == LocationLevel.SUB_COUNTY) {
                            // County names (or, for VHF, the type of case) are needed for the
                            // counts: load the list.
                            loadAndCount()
                        } else {
                            // Everyone else sees all records of the module: count in the database.
                            viewLifecycleOwner.lifecycleScope.launch {
                                val total = patientListViewModel.countCaseRecords(it)
                                ensureActive()
                                if (total == null) {
                                    loadAndCount()
                                } else {
                                    caseOptions[viewIndex] = caseOptions[viewIndex].copy(count = total)
                                    recyclerView.adapter?.notifyDataSetChanged()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    /**
     * Number of cases the user sees in the case list. Mirrors CaseListingActivity.applyRoleScope:
     * county / sub-county users are matched by name ignoring case (location answers are stored in
     * capitals, profile names are not); national, ward and facility users see everything returned,
     * because ward/facility scoping is already applied upstream through their facility units.
     */
    private fun countInScope(
        countyAndSubCounty: List<Pair<String, String>>,
        roleScope: LocationLevel?,
        storedCounty: String?,
        storedSubCounty: String?,
    ): Int = when (roleScope) {
        LocationLevel.COUNTY ->
            if (storedCounty.isNullOrBlank()) countyAndSubCounty.size
            else countyAndSubCounty.count { it.first.contains(storedCounty.trim(), ignoreCase = true) }

        LocationLevel.SUB_COUNTY ->
            if (storedSubCounty.isNullOrBlank()) countyAndSubCounty.size
            else countyAndSubCounty.count { it.second.contains(storedSubCounty.trim(), ignoreCase = true) }

        else -> countyAndSubCounty.size
    }

    fun assignRespectiveQuestionnaire(): String {
        // Same form for every role; jurisdiction is applied via the pre-filled user_role
        return "add-case.json"
    }

    /**
     * Shows a permission error dialog.
     *
     * @param context The context.
     */
    fun showPermissionErrorDialog(context: Context) {
//    SweetAlertDialog(context, SweetAlertDialog.ERROR_TYPE).apply {
//      setTitleText("Operation Restricted")
//      setContentText("You do not have permission to perform this action.")
//      setConfirmText("Okay")
//      setConfirmClickListener { sDialog -> sDialog.dismissWithAnimation() }
//      show()
//    }
    }

    /**
     * Checks if the user is allowed to perform an action.
     *
     * @return `true` if the user is allowed to perform the action, `false` otherwise.
     */
    fun checkIfUserIsAllowedToAction(): Boolean {
        val hasFacility = FormatterClass().getSharedPref("facility", requireContext())
        Timber.d("Facility Selection: $hasFacility")
        return true // !hasFacility.isNullOrEmpty()
    }

    /**
     * A new VHF case: an unfinished draft of the form is offered first (straight to the form);
     * otherwise, or for a new case, the client is looked up (IPRS / Client Registry) first.
     */
    private fun openNewVhfCase(title: String) {
        val context = requireContext()
        UnfinishedDraftPrompt.offer(
            context, VHF_QUESTIONNAIRE,
            onContinue = {
                addParentCaseLauncher.launch(
                    Intent(context, AddParentCaseActivity::class.java)
                        .putExtra("AddParentTitle", title)
                        .putExtra(QUESTIONNAIRE_FILE_PATH_KEY, VHF_QUESTIONNAIRE)
                        .putExtra(AddParentCaseActivity.EXTRA_RESUME_DRAFT, true)
                )
            },
            onStartNew = {
                addParentCaseLauncher.launch(
                    Intent(context, ClientLookupActivity::class.java)
                        .putExtra("AddParentTitle", title)
                        .putExtra(QUESTIONNAIRE_FILE_PATH_KEY, VHF_QUESTIONNAIRE)
                        .putExtra(AddParentCaseActivity.EXTRA_START_NEW, true)
                )
            },
        )
    }

    override fun onResume() {
        super.onResume()
        try {
            setupRecyclerView()
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    /** VHF: "Add new suspected case" sits in the ⋮ menu, off the main list until confirmed. */
    @Deprecated("Fragment menu callbacks")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        // grandTitle holds the module's full name (the VHF form title), not "VHF".
        if (FormatterClass().getSharedPref("grandTitle", requireContext()) == getString(R.string.vhf_form)) {
            menu.add(Menu.NONE, MENU_ADD_VHF_CASE, Menu.NONE, R.string.add_suspected_case)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
    }

    private fun addVhfCaseFromMenu() {
        val context = requireContext()
        VhfTraining.setOn(context, false)
        FormatterClass().saveSharedPref("currentCase", CaseTypes.VHF, context)
        FormatterClass().saveSharedPref("AddParentTitle", getString(R.string.vhf_form), context)
        FormatterClass().saveSharedPref("questionnaire", VHF_QUESTIONNAIRE, context)
        if (!checkIfUserIsAllowedToAction()) {
            showPermissionErrorDialog(context)
            return
        }
        val titleName = FormatterClass().getSharedPref("grandTitle", context)
        openNewVhfCase(" $titleName")
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_ADD_VHF_CASE -> {
                addVhfCaseFromMenu()
                true
            }

            android.R.id.home -> {
                // Handle the back button in the toolbar
                requireActivity().onBackPressedDispatcher.onBackPressed()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val VHF_QUESTIONNAIRE = "vhf-case.json"
        private const val MENU_ADD_VHF_CASE = 9101
        private const val ALERT_LOG_TITLE = "Alert Log"
        private const val ADD_ALERT = "Add New Alert"
        private const val ALERT_LOG_LIST = "Alert Log List"
        private const val SCREENED_CASES = "Flagged"
        /** VHF module options (meeting 20 Oct 2026: VHF cases are listed as Suspected Cases). */
        private const val VHF_ADD = "Add New Suspected Case"
        // VHF module tiles (meeting 21 Oct 2026): Suspected, Confirmed, Contacts, Flagged.
        private const val VHF_LIST = "Suspected"
        private const val VHF_CONFIRMED = "Confirmed"
        private const val VHF_CONTACTS = "Contacts"

        /**
         * Use this factory method to create a new instance of this fragment using the provided
         * parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment CaseSelectionFragment.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            CaseSelectionFragment().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_PARAM1, param1)
                        putString(ARG_PARAM2, param2)
                    }
            }
    }
}
