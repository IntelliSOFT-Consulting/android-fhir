package com.icl.surveillance.ui.patients.custom

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.fhir.FhirEngine
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.icl.surveillance.R
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.databinding.FragmentLocalLabBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.models.ChildItem
import com.icl.surveillance.models.OutputGroup
import com.icl.surveillance.models.OutputItem
import com.icl.surveillance.models.QuestionnaireItem
import com.icl.surveillance.ui.patients.AddCaseActivity
import com.icl.surveillance.ui.patients.PatientListViewModel
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.viewmodels.ClientDetailsViewModel
import com.icl.surveillance.viewmodels.factories.PatientDetailsViewModelFactory

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass. Use the [LocalLabFragment.newInstance] factory method to create an
 * instance of this fragment.
 */
class LocalLabFragment : Fragment() {
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
  private var _binding: FragmentLocalLabBinding? = null
  private lateinit var parentLayout: LinearLayout
  private val binding
    get() = _binding!!

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View? {
    // Inflate the layout for this fragment
    _binding = FragmentLocalLabBinding.inflate(inflater, container, false)
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
        when (slug) {
          "measles-case-information" -> {
            patientDetailsViewModel.getPatientDiseaseData(
                "Measles Lab Information",
                "$encounterId",
                false,
            )
          }

          "afp-case-information" -> {
            patientDetailsViewModel.getPatientResultsDiseaseData(
                "AFP Stool Lab Information",
                "$encounterId",
            )
          }
        }
      }
    } catch (e: Exception) {
      println(e.message)
    }
  }

  private lateinit var groups: List<OutputGroup>

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

    groups = parseFromAssets(requireContext())
    patientDetailsViewModel.currentLiveLabData.observe(viewLifecycleOwner) {
      if (it.isEmpty()) {
        binding.lnEmpty.visibility = View.VISIBLE
      } else {
        // this = Context
        parentLayout.removeAllViews()
        for (group in groups) {
          //                    println("Manipulations: ${group.text}")
          val fieldView = createCustomLabel(group.text)
          parentLayout.addView(fieldView)
          for (item in group.items) {

            item.value = getValueBasedOnId(item, it.first().observations)
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

        binding.lnEmpty.visibility = View.GONE
        binding.fab.visibility = View.GONE
      }
    }
    if (currentCase != null) {
      val slug = currentCase.toSlug()
      when (slug) {
        "measles-case-information" -> {
          patientDetailsViewModel.getPatientDiseaseData(
              "Measles Lab Information",
              "$encounterId",
              false,
          )
        }

        "afp-case-information" -> {
          patientDetailsViewModel.getPatientResultsDiseaseData(
              "AFP Stool Lab Information",
              "$encounterId",
          )
        }
      }
    }

    binding.apply {
      getStartedButton.setOnClickListener { handleCase(currentCase) }

      fab.setOnClickListener { handleCase(currentCase) }
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

  private fun handleCase(currentCase: String?) {
    if (currentCase != null) {
      val slug = currentCase.toSlug()
      when (slug) {
        "measles-case-information" -> {

          showLocalOrRegionalLab()
        }

        "afp-case-information" -> {
          FormatterClass()
              .saveSharedPref("questionnaire", "afp-case-stool-lab-results.json", requireContext())
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
  }

  private fun getValueBasedOnId(
      item: OutputItem,
      items: List<PatientListViewModel.ObservationItem>,
  ): String {
    var response = ""
    items.forEach { outputItem ->
      val matchingObservation = items.find { obs -> obs.code == item.linkId }

      if (matchingObservation != null) {
        response = matchingObservation.value
      }
    }
    return response
  }

  fun parseFromAssets(context: Context): List<OutputGroup> {
    var outputGroups: List<OutputGroup> = emptyList()

    try {
      val jsonContent =
          context.assets.open("afp-case-stool-lab-results.json").bufferedReader().use {
            it.readText()
          }

      val gson = Gson()
      val questionnaire = gson.fromJson(jsonContent, QuestionnaireItem::class.java)

      outputGroups =
          questionnaire.item.map { group ->
            OutputGroup(
                linkId = group.linkId,
                text = group.text,
                type = group.type,
                items = group.item?.flatMap { flattenItems(it) } ?: emptyList(),
            )
          }
    } catch (e: Exception) {
      e.printStackTrace()
      Log.e("TAG", "File Error ${e.message}")
    }
    return outputGroups
  }

  fun flattenItemsOld(
      item: ChildItem,
      parentConditions: Map<String, Pair<String, Boolean>> = emptyMap(),
  ): List<OutputItem> {
    val currentConditions =
        mutableMapOf<String, Pair<String, Boolean>>().apply { putAll(parentConditions) }

    var enable = true
    var parentLink: String? = null
    var parentResponse: String? = null

    item.enableWhen?.firstOrNull()?.let { condition ->
      parentLink = condition.question
      val expectedAnswer =
          when {
            condition.answerCoding != null ->
                condition.answerCoding.display ?: condition.answerCoding.code

            condition.answerString != null -> condition.answerString
            condition.answerBoolean != null -> condition.answerBoolean.toString()
            condition.answerDate != null -> condition.answerDate
            else -> null
          }
      parentResponse = expectedAnswer
      enable = false // assume not enabled unless condition is met at runtime
    }

    val children = item.item?.flatMap { flattenItemsOld(it, currentConditions) } ?: emptyList()

    return if (item.type != "display") {
      val current =
          OutputItem(
              linkId = item.linkId,
              text = item.text,
              type = item.type,
              enable = enable,
              parentLink = parentLink,
              parentResponse = parentResponse,
          )
      listOf(current) + children
    } else {
      children
    }
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
          setTextColor(ContextCompat.getColor(requireContext(), R.color.purple_200))
          typeface = Typeface.DEFAULT_BOLD
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

  private fun showLocalOrRegionalLab() {
    val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_lab_info, null)

    val labButton = dialogView.findViewById<MaterialButton>(R.id.btnLabInformation)
    val regionalLabButton = dialogView.findViewById<MaterialButton>(R.id.btnRegionalLabInformation)

    val dialog =
        AlertDialog.Builder(requireContext()).setView(dialogView).setCancelable(true).create()

    labButton.setOnClickListener {
      dialog.dismiss()
      FormatterClass().saveSharedPref("questionnaire", "measles-lab-results.json", requireContext())
      FormatterClass().saveSharedPref("title", "Measles Lab Results", requireContext())
      val intent = Intent(requireContext(), AddCaseActivity::class.java)
      intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, "measles-lab-results.json")
      startActivity(intent)
    }

    regionalLabButton.setOnClickListener {
      dialog.dismiss()
      FormatterClass()
          .saveSharedPref("questionnaire", "measles-lab-reg-results.json", requireContext())
      FormatterClass().saveSharedPref("title", "Regional Measles Lab Results", requireContext())
      val intent = Intent(requireContext(), AddCaseActivity::class.java)
      intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, "measles-lab-reg-results.json")
      startActivity(intent)
    }

    dialog.show()
  }

  private fun onItemClicked(encounterItem: PatientListViewModel.CaseLabResultsData) {}

  companion object {
    /**
     * Use this factory method to create a new instance of this fragment using the provided
     * parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment LocalLabFragment.
     */
    // TODO: Rename and change types and number of parameters
    @JvmStatic
    fun newInstance(param1: String, param2: String) =
        LocalLabFragment().apply {
          arguments =
              Bundle().apply {
                putString(ARG_PARAM1, param1)
                putString(ARG_PARAM2, param2)
              }
        }
  }
}
