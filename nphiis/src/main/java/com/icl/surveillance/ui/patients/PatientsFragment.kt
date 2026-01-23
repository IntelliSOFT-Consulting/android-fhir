package com.icl.surveillance.ui.patients

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.google.android.fhir.FhirEngine
import com.icl.surveillance.R
import com.icl.surveillance.adapters.PatientItemRecyclerViewAdapter
import com.icl.surveillance.databinding.FragmentPatientListBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.utils.FormatterClass

class PatientsFragment : Fragment() {
    private lateinit var fhirEngine: FhirEngine
    private lateinit var patientListViewModel: PatientListViewModel
    private lateinit var searchView: SearchView

    private var _binding: FragmentPatientListBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding
        get() = _binding!!


    override fun onResume() {
        super.onResume()
        try {
            patientListViewModel.searchPatientsByName("")
        } catch (e: Exception) {

        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentPatientListBinding.inflate(inflater, container, false)
        val root: View = binding.root
        val imm =
            requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        binding.tvEpidNo.apply {
            addTextChangedListener(
                onTextChanged = { text, _, _, _ ->
                    patientListViewModel.setPatientGivenName(text.toString())
                },
            )
            setOnFocusChangeListener { view, hasFocus ->
                if (!hasFocus) {
                    imm.hideSoftInputFromWindow(view.windowToken, 0)
                }
            }
        }
        binding.familyNameEditText.apply {
            addTextChangedListener(
                onTextChanged = { text, _, _, _ ->
                    patientListViewModel.setPatientFamilyName(text.toString())
                },
            )
            setOnFocusChangeListener { view, hasFocus ->
                if (!hasFocus) {
                    imm.hideSoftInputFromWindow(view.windowToken, 0)
                }
            }
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        val titleName = FormatterClass().getSharedPref("listingTitle", requireContext())
        fhirEngine = FhirApplication.fhirEngine(requireContext())
        patientListViewModel =
            ViewModelProvider(
                this,
                PatientListViewModel.PatientListViewModelFactory(
                    requireActivity().application, fhirEngine
                ),
            )
                .get(PatientListViewModel::class.java)
        val recyclerView: RecyclerView = binding.patientListContainer.patientList
        val adapter = PatientItemRecyclerViewAdapter(
            this::onPatientItemClicked,
            "$titleName",
            requireContext()
        )
        recyclerView.adapter = adapter
        recyclerView.addItemDecoration(
            DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL).apply {
                setDrawable(ColorDrawable(Color.LTGRAY))
            },
        )

        patientListViewModel.patientCount.observe(viewLifecycleOwner) {
            binding.patientListContainer.caseCount.text = "$it Case(s) Found"
        }
        requireActivity()
            .onBackPressedDispatcher
            .addCallback(
                viewLifecycleOwner,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
//                        if (searchView.query.isNotEmpty()) {
//                            searchView.setQuery("", true)
//                        } else {
//                            isEnabled = false
//                            activity?.onBackPressed()
//                        }
                    }
                },
            )
        setHasOptionsMenu(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun onPatientItemClicked(patientItem: PatientListViewModel.PatientItem) {
        println("Going to client details activity with the id as ${patientItem.resourceId} and Encounter ${patientItem.encounterId}")
        FormatterClass().saveSharedPref("resourceId", patientItem.resourceId, requireContext())
        FormatterClass().saveSharedPref("encounterId", patientItem.encounterId, requireContext())
        findNavController().navigate(R.id.action_navigation_dashboard_to_fullCaseDetailsActivity)
    }
}