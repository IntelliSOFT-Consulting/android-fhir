package com.icl.surveillance.ui.patients.data

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.fhir.FhirEngine
import com.icl.surveillance.databinding.FragmentCaseInformationBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.ui.patients.PatientListViewModel
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.viewmodels.ClientDetailsViewModel
import com.icl.surveillance.viewmodels.factories.PatientDetailsViewModelFactory

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass. Use the [CaseInformationFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class CaseInformationFragment : Fragment() {
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

    private lateinit var fhirEngine: FhirEngine
    private lateinit var patientDetailsViewModel: ClientDetailsViewModel
    private var _binding: FragmentCaseInformationBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding
        get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        _binding = FragmentCaseInformationBinding.inflate(inflater, container, false)
        val root: View = binding.root

        return root
    }

    fun String.toSlug(): String {
        return this
            .trim()
            .lowercase()
            .replace("[^a-z0-9\\s-]".toRegex(), "")
            .replace("\\s+".toRegex(), "-")
            .replace("-+".toRegex(), "-")
    }

    override fun onResume() {
        super.onResume()
        try {
            val encounterId = FormatterClass().getSharedPref("encounterId", requireContext())
            patientDetailsViewModel.getPatientDiseaseData("Measles Case", "$encounterId", true)
        } catch (e: Exception) {
            println(e.message)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val patientId = FormatterClass().getSharedPref("resourceId", requireContext())
        val encounterId = FormatterClass().getSharedPref("encounterId", requireContext())

        fhirEngine = FhirApplication.fhirEngine(requireContext())
        patientDetailsViewModel =
            ViewModelProvider(
                this,
                PatientDetailsViewModelFactory(
                    requireActivity().application, fhirEngine, "$patientId"
                ),
            )
                .get(ClientDetailsViewModel::class.java)
        val currentCase = FormatterClass().getSharedPref("currentCase", requireContext())
        if (currentCase != null) {
            val slug = currentCase.toSlug()
            patientDetailsViewModel.getClinicalInfo(slug)
        }
        patientDetailsViewModel.liveClinicalData.observe(viewLifecycleOwner) {
            val symptoms = it.symptoms.distinct().joinToString(", ")

            binding.apply {
                tvOnset.text = it.onset
                tvClinicalSymptoms.text = symptoms
                tvOnsetRashDate.text = it.rashDate
                tvRashType.text = it.rashType
                tvVaccinated.text = it.vaccinated
                tvDoses.text = it.doses
                tvVaccination30Days.text = it.thirtyDays
                tvLastVaccinationDate.text = it.lastVaccination
                tvHomeVisit.text = it.homeVisit
                tvVisitDate.text = it.homeDateVisit
                tvEpiLink.text = it.caseEpilinked

                if (it.symptoms.contains("Rash")) {
                    lnRashSection.visibility = View.VISIBLE
                }
            }

            //    patientDetailsViewModel.getPatientDiseaseData("Measles Case", "$encounterId", true)

        }
    }

    private fun onItemClicked(encounterItem: PatientListViewModel.CaseDiseaseData) {}

    companion object {
        /**
         * Use this factory method to create a new instance of this fragment using the provided
         * parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment CaseInformationFragment.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            CaseInformationFragment().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_PARAM1, param1)
                        putString(ARG_PARAM2, param2)
                    }
            }
    }
}
