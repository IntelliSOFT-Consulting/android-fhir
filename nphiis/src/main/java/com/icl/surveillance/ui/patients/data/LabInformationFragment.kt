package com.icl.surveillance.ui.patients.data

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.fhir.FhirEngine
import com.icl.surveillance.databinding.FragmentLabInformationBinding
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
 * A simple [Fragment] subclass. Use the [LabInformationFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class LabInformationFragment : Fragment() {
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
    private var _binding: FragmentLabInformationBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding
        get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        _binding = FragmentLabInformationBinding.inflate(inflater, container, false)
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
            val currentCase = FormatterClass().getSharedPref("currentCase", requireContext())
            if (currentCase != null) {
                val slug = currentCase.toSlug()
                patientDetailsViewModel.getPatientInfo(slug)
            }
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
            patientDetailsViewModel.getPatientInfo(slug)
        }
        patientDetailsViewModel.livecaseData.observe(viewLifecycleOwner) {
            binding.apply {
                tvBloodSpecimenCollected.text = it.bloodSpecimenCollected
                tvNoWhyBlood.text = it.noWhyBlood
                tvDateBloodSpecimen.text = it.dateBloodSpecimen
                tvUrineSpecimenCollected.text = it.urineSpecimenCollected
                tvNoWhyUrine.text = it.noWhyUrine
                tvDateUrineSpecimen.text = it.dateUrineSpecimen
                tvRespiratorySampleCollected.text = it.respiratorySampleCollected
                tvDateRespiratorySample.text = it.dateRespiratorySample
                tvNoWhyRespiratory.text = it.noWhyRespiratory
                tvOtherSpecimenCollected.text = it.otherSpecimenCollected
                tvSpecifyOtherSpecimen.text = it.specifyOtherSpecimen
                tvDateOtherSpecimen.text = it.dateOtherSpecimen
                tvDateSpecimenSentToLab.text = it.dateSpecimenSentToLab

                if (it.bloodSpecimenCollected.trim() == "Yes") {
                    lnBloodNo.visibility = View.GONE
                    lnBloodYes.visibility = View.VISIBLE
                } else if (it.bloodSpecimenCollected.trim() == "No") {
                    lnBloodNo.visibility = View.VISIBLE
                    lnBloodYes.visibility = View.GONE
                }
                if (it.urineSpecimenCollected.trim() == "Yes") {
                    lnUrineNo.visibility = View.GONE
                    lnUrineYes.visibility = View.VISIBLE
                } else if (it.urineSpecimenCollected.trim() == "No") {
                    lnUrineNo.visibility = View.VISIBLE
                    lnUrineYes.visibility = View.GONE
                }

                if (it.respiratorySampleCollected.trim() == "Yes") {
                    resYes.visibility = View.VISIBLE
                    resNo.visibility = View.GONE
                } else if (it.respiratorySampleCollected.trim() == "No") {
                    resYes.visibility = View.GONE
                    resNo.visibility = View.VISIBLE
                }
                if (it.otherSpecimenCollected.trim() == "Yes") {
                    otherYes.visibility = View.VISIBLE

                }
            }
        }

        //    val adapter = LabRecyclerViewAdapter(this::onItemClicked)
        //    binding.patientList.adapter = adapter

        //    patientDetailsViewModel.liveLabData.observe(viewLifecycleOwner) {
        //      if (it.isEmpty()) {
        //        binding.tvNoCase.visibility = View.VISIBLE
        //      } else {
        //        binding.tvNoCase.visibility = View.GONE
        //        binding.fab.visibility = View.GONE
        //        adapter.submitList(it)
        //      }
        //    }
        //    patientDetailsViewModel.getPatientDiseaseData("Measles Lab Information", "$encounterId",
        // false)

        binding.apply {
            //      fab.setOnClickListener {
            //        FormatterClass()
            //            .saveSharedPref("questionnaire", "measles-lab-results.json", requireContext())
            //        FormatterClass().saveSharedPref("title", "Lab Information", requireContext())
            //        val intent = Intent(requireContext(), AddCaseActivity::class.java)
            //        intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, "measles-lab-results.json")
            //        startActivity(intent)
            //      }
        }
    }

    private fun onItemClicked(encounterItem: PatientListViewModel.CaseLabResultsData) {}

    companion object {
        /**
         * Use this factory method to create a new instance of this fragment using the provided
         * parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment LabInformationFragment.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            LabInformationFragment().apply {
                arguments =
                    Bundle().apply {
                        putString(ARG_PARAM1, param1)
                        putString(ARG_PARAM2, param2)
                    }
            }
    }
}
