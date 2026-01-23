package com.icl.surveillance.ui.patients.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.search.search
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.Gson
import com.icl.surveillance.R
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.databinding.FragmentLabResultsBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.models.ChildItem
import com.icl.surveillance.models.OutputGroup
import com.icl.surveillance.models.OutputItem
import com.icl.surveillance.models.QuestionnaireItemChild
import com.icl.surveillance.ui.patients.AddCaseActivity
import com.icl.surveillance.ui.patients.PatientListViewModel
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.viewmodels.ClientDetailsViewModel
import com.icl.surveillance.viewmodels.factories.PatientDetailsViewModelFactory
import kotlin.String
import kotlin.jvm.java
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Resource

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass. Use the [LabResultsFragment.newInstance] factory method to create
 * an instance of this fragment.
 */
class LabResultsFragment : Fragment() {
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
  private var _binding: FragmentLabResultsBinding? = null

  private val binding
    get() = _binding!!

  private lateinit var parentLayout: LinearLayout

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View? {

    _binding = FragmentLabResultsBinding.inflate(inflater, container, false)
    val root: View = binding.root

    return root
  }

  fun String.toSlug(): String {
    return this.trim()
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
        println("Dealing with case:::: starter $encounterId")
        when (slug) {
          "measles-case-information" -> {
            patientDetailsViewModel.getPatientResultsDiseaseData(
                "Measles Lab Information",
                "$encounterId",
            )
          }

          "afp-case-information" -> {
            patientDetailsViewModel.getPatientDiseaseData(
                "AFP Lab Information",
                "$encounterId",
                false,
            )
          }
        }
      }
    } catch (e: Exception) {
      println(e.message)
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val patientId = FormatterClass().getSharedPref("patientIdParent", requireContext())
    val encounterId = FormatterClass().getSharedPref("encounterId", requireContext())
    val currentCase = FormatterClass().getSharedPref("currentCase", requireContext())

    fhirEngine = FhirApplication.fhirEngine(requireContext())
    patientDetailsViewModel =
        ViewModelProvider(
                this,
                PatientDetailsViewModelFactory(
                    requireActivity().application,
                    fhirEngine,
                    "$patientId",
                ),
            )
            .get(ClientDetailsViewModel::class.java)

    parentLayout = binding.lnParent

    val outputGroups = parseFromAssets(requireContext())
    patientDetailsViewModel.currentLiveLabData.observe(viewLifecycleOwner) {
      if (it.isEmpty()) {
        binding.tvNoCase.visibility = View.VISIBLE
        FormatterClass().saveSharedPref("labDone", "false", requireContext())
      } else {

        FormatterClass().deleteSharedPref("labDone", requireContext())
        binding.tvNoCase.visibility = View.GONE
        binding.fab.visibility = View.GONE
        parentLayout.removeAllViews()
        outputGroups.first().items.forEach { group ->
          if (group.type == "display") {
            val fieldView = createCustomLabel(group.text)
            parentLayout.addView(fieldView)
          } else {

            val item =
                OutputItem(
                    linkId = group.linkId,
                    text = group.text,
                    type = group.text,
                    parentOperator = group.parentOperator,
                    enable = if (group.linkId == "final-classification") true else group.enable,
                    parentLink = group.parentLink,
                    parentResponse = group.parentResponse,
                    value =
                        if (group.linkId == "final-classification")
                            getValueBasedOnIdAndReference(
                                item = "measles-igm",
                                it.first().observations,
                            )
                        else getValueBasedOnId(item = group.linkId, it.first().observations),
                )
            val childFieldView = createCustomField(item)
            var show = true
            if (!item.enable) {
              show = false
              show =
                  checkIfParentAnswerMatches(
                      item.parentLink,
                      item.parentResponse,
                      item.parentOperator,
                      it.first().observations,
                  )
            }
            if (show) {
              parentLayout.addView(childFieldView)
            }
          }
        }
      }
    }
    if (currentCase != null) {
      val slug = currentCase.toSlug()
      when (slug) {
        "measles-case-information" -> {
          patientDetailsViewModel.getPatientResultsDiseaseData(
              "Measles Lab Information",
              "$encounterId",
          )
        }

        "afp-case-information" -> {
          patientDetailsViewModel.getPatientDiseaseData(
              "AFP Lab Information",
              "$encounterId",
              false,
          )
        }
      }
    }

    binding.apply {
      fab.setOnClickListener {
        if (currentCase != null) {
          val slug = currentCase.toSlug()

          val patientId = FormatterClass().getSharedPref("patientId", requireContext())
          val resourceId =
              FormatterClass().getSharedPref("resourceId", requireContext()) // aka Questionnaire
          val encounterId = FormatterClass().getSharedPref("encounterId", requireContext())
          if (resourceId != null) {
            when (slug) {
              "measles-case-information" -> {
                // Let's get the specific parent QuestionnaireResponse
                lifecycleScope.launch {
                  val searchResult =
                      fhirEngine.search<QuestionnaireResponse> {
                        filter(Resource.RES_ID, { value = of(resourceId) })
                      }
                  if (searchResult.isEmpty()) {
                    Toast.makeText(requireContext(), "Please try again later", Toast.LENGTH_SHORT)
                        .show()
                    return@launch
                  }
                  searchResult.first().let {}

                  FormatterClass()
                      .saveSharedPref("questionnaire", "measles-lab-results.json", requireContext())
                  FormatterClass().saveSharedPref("title", "Measles Lab Results", requireContext())
                  val intent = Intent(requireContext(), AddCaseActivity::class.java)
                  intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, "measles-lab-results.json")
                  startActivity(intent)
                }
              }

              "afp-case-information" -> {
                FormatterClass()
                    .saveSharedPref(
                        "questionnaire",
                        "afp-case-stool-lab-results.json",
                        requireContext(),
                    )
                FormatterClass().saveSharedPref("title", "AFP Lab Results", requireContext())
                val intent = Intent(requireContext(), AddCaseActivity::class.java)
                intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, "afp-case-stool-lab-results.json")
                startActivity(intent)
              }

              else -> {
                val dialog = BottomSheetDialog(requireContext())
                val view = layoutInflater.inflate(R.layout.dialog_bottom_sheet, null)

                dialog.setContentView(view)
                dialog.setCancelable(true)

                view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
                dialog.show()
              }
            }
          }
        } else {
          Toast.makeText(requireContext(), "Please try again later", Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  private fun checkIfParentAnswerMatches(
      parentLink: String?,
      parentResponse: String?,
      operator: String?,
      items: List<PatientListViewModel.ObservationItem>,
  ): Boolean {
    var response = false

    if (parentLink != null && parentResponse != null) {
      val parentAnswer = items.find { it.code == parentLink }?.value
      if (parentAnswer != null) {

        if (operator != null) {
          when (operator) {
            "!=" -> {
              if (parentAnswer.trim() != parentResponse.trim()) {
                response = true
              }
            }

            else -> {
              if (parentAnswer.trim() == parentResponse.trim()) {
                response = true
              }
            }
          }
        }
      }
    }
    return response
  }

  private fun getValueBasedOnId(
      item: String,
      items: List<PatientListViewModel.ObservationItem>,
  ): String {
    var response = ""
    items.forEach { outputItem ->
      val matchingObservation = items.find { obs -> obs.code == item }

      if (matchingObservation != null) {
        response = matchingObservation.value
      }
    }
    return response
  }

  private fun getValueBasedOnIdAndReference(
      item: String,
      items: List<PatientListViewModel.ObservationItem>,
  ): String {
    var response = "Lab results pending"
    val isVaccinated = FormatterClass().getSharedPref("isVaccinated", requireContext())
    items.forEach { outputItem ->
      val matchingObservation = items.find { obs -> obs.code == item }
      if (matchingObservation != null) {
        response = matchingObservation.value
      }
    }
    if (response.isNotEmpty()) {
      response =
          when (response.lowercase()) {
            "positive" -> {
              when (isVaccinated?.lowercase()) {
                "yes" -> "Pending"
                else -> "Confirmed by lab"
              }
            }

            "negative" -> "Discarded"
            "indeterminate" -> "Compatible/Clinical/Probable"
            else -> "Pending Results"
          }
    }
    return response
  }

  private fun createCustomLabel(item: String): View {
    // Create the main LinearLayout to hold the views
    val layout =
        LinearLayout(requireContext()).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(8, 8, 8, 8)
        }

    // Create the horizontal LinearLayout for the two TextViews
    val horizontalLayout =
        LinearLayout(requireContext()).apply {
          orientation = LinearLayout.HORIZONTAL
          layoutParams =
              LinearLayout.LayoutParams(
                  LinearLayout.LayoutParams.MATCH_PARENT,
                  LinearLayout.LayoutParams.WRAP_CONTENT,
              )
        }

    // First TextView (label)
    val label =
        TextView(requireContext()).apply {
          text = item
          textSize = 14f
          setTextColor(android.graphics.Color.BLUE)
          layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    horizontalLayout.addView(label)

    // Add the horizontal layout with two TextViews to the main layout
    layout.addView(horizontalLayout)

    // Add a separator View (divider)
    val divider =
        View(requireContext()).apply {
          layoutParams =
              LinearLayout.LayoutParams(
                      LinearLayout.LayoutParams.MATCH_PARENT,
                      1, // Divider thickness
                  )
                  .apply {
                    topMargin = 8
                    bottomMargin = 8
                  }
          setBackgroundColor(android.graphics.Color.parseColor("#CCCCCC"))
        }
    layout.addView(divider)

    return layout
  }

  private fun createCustomField(item: OutputItem): View {
    // Create the main LinearLayout to hold the views
    val layout =
        LinearLayout(requireContext()).apply {
          orientation = LinearLayout.VERTICAL
          setPadding(8, 8, 8, 8)
        }

    // Create the horizontal LinearLayout for the two TextViews
    val horizontalLayout =
        LinearLayout(requireContext()).apply {
          orientation = LinearLayout.HORIZONTAL
          layoutParams =
              LinearLayout.LayoutParams(
                  LinearLayout.LayoutParams.MATCH_PARENT,
                  LinearLayout.LayoutParams.WRAP_CONTENT,
              )
        }

    // First TextView (label)
    val label =
        TextView(requireContext()).apply {
          text = item.text
          textSize = 12f
          setTextColor(android.graphics.Color.BLACK)
          layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    horizontalLayout.addView(label)

    // Second TextView (dynamic content)
    val tvEpiLink =
        TextView(requireContext()).apply {
          id = R.id.tv_epi_link // Set ID if needed for further reference
          text = item.value // Assuming item.text is the dynamic text you want to show
          textSize = 13f
          textAlignment = TextView.TEXT_ALIGNMENT_TEXT_END
          setTextColor(android.graphics.Color.BLACK)
          setTypeface(typeface, android.graphics.Typeface.BOLD)
          layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
    horizontalLayout.addView(tvEpiLink)

    // Add the horizontal layout with two TextViews to the main layout
    layout.addView(horizontalLayout)

    // Add a separator View (divider)
    val divider =
        View(requireContext()).apply {
          layoutParams =
              LinearLayout.LayoutParams(
                      LinearLayout.LayoutParams.MATCH_PARENT,
                      1, // Divider thickness
                  )
                  .apply {
                    topMargin = 8
                    bottomMargin = 8
                  }
          setBackgroundColor(android.graphics.Color.parseColor("#CCCCCC"))
        }
    layout.addView(divider)

    return layout
  }

  fun flattenItems(item: ChildItem): List<OutputItem> {
    val children = item.item?.flatMap { flattenItems(it) } ?: emptyList()

    var enable = true
    var parentLink: String? = null
    var parentResponse: String? = null
    var enableOperator: String? = null

    item.enableWhen?.firstOrNull()?.let { condition ->
      parentLink = condition.question
      enableOperator = condition.operator
      parentResponse =
          when {
            condition.answerCoding != null ->
                condition.answerCoding.display ?: condition.answerCoding.code

            condition.answerString != null -> condition.answerString
            condition.answerBoolean != null -> condition.answerBoolean.toString()
            condition.answerDate != null -> condition.answerDate
            else -> null
          }
      enable = false
    }

    return if (item.type != "display") {
      val current =
          OutputItem(
              linkId = item.linkId,
              text = item.text,
              type = item.type,
              enable = enable,
              parentLink = parentLink,
              parentResponse = parentResponse,
              parentOperator = enableOperator,
          )
      listOf(current) + children
    } else {
      children
    }
  }

  fun parseFromAssets(context: Context): List<OutputGroup> {
    var outputGroups: List<OutputGroup> = emptyList()

    try {
      val jsonContent =
          context.assets.open("measles-lab-results.json").bufferedReader().use { it.readText() }

      val gson = Gson()
      val questionnaire = gson.fromJson(jsonContent, QuestionnaireItemChild::class.java)

      val flatItems = questionnaire.item.flatMap { flattenItems(it) }

      outputGroups =
          listOf(
              OutputGroup(linkId = "root", text = "Top Level", type = "group", items = flatItems)
          )
    } catch (e: Exception) {
      e.printStackTrace()
      Log.e("TAG", "File Error ${e.message}")
    }

    return outputGroups
  }

  companion object {
    /**
     * Use this factory method to create a new instance of this fragment using the provided
     * parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment LabResultsFragment.
     */
    // TODO: Rename and change types and number of parameters
    @JvmStatic
    fun newInstance(param1: String, param2: String) =
        LabResultsFragment().apply {
          arguments =
              Bundle().apply {
                putString(ARG_PARAM1, param1)
                putString(ARG_PARAM2, param2)
              }
        }
  }
}
