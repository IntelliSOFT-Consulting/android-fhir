package com.icl.surveillance.ui.patients.custom

import android.graphics.Typeface
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.gson.Gson
import com.icl.surveillance.R
import com.icl.surveillance.models.OutputGroup
import com.icl.surveillance.models.OutputItem
import com.icl.surveillance.utils.Constants.COUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES
import com.icl.surveillance.utils.Constants.DEFAULT_EXCLUDES
import com.icl.surveillance.utils.Constants.SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES
import com.icl.surveillance.utils.Constants.VACCINATOR_EXCLUDES
import com.icl.surveillance.utils.Constants.VALID_ADMINISTRATOR_EXCLUDES
import com.icl.surveillance.utils.Constants.VALID_COUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES
import com.icl.surveillance.utils.Constants.VALID_SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES
import com.icl.surveillance.utils.Constants.VALID_VACCINATOR_EXCLUDES

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [GroupFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class GroupFragment : Fragment() {
    // TODO: Rename and change types of parameters
    private var param1: String? = null
    private var param2: String? = null


    private lateinit var parentLayout: LinearLayout
    private lateinit var group: OutputGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val groupJson = requireArguments().getString("group")
        group = Gson().fromJson(groupJson, OutputGroup::class.java)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_group, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        parentLayout = view.findViewById(R.id.ln_parent)

        // Now dynamically add fields
        addChildItems()
        val textView = view.findViewById<TextView>(R.id.tv_title)
        textView.text = "${group.text}"
    }

    private fun addChildItems() {
        val role = group.items.find {
            it.linkId == "user_role"
        }
        for (item in group.items) {
            val fieldView = createCustomField(item)
            val labelOnlyView = createCustomLabelField(item)
            var show = true
            if (!DEFAULT_EXCLUDES.contains(item.linkId)) {

                if (!item.enable) {
                    show = checkIfParentAnswerMatches(
                        item.parentOperator,
                        item.parentLink, item.parentResponse, group.items
                    )
                }

                if (role != null) {
                    when (role.value) {
                        "VACCINATOR" -> {
                            if (VALID_VACCINATOR_EXCLUDES.contains(item.linkId)) {
                                item.value = ""
                                item.text = ""
                            }
                        }

                        "SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER" -> {
                            if (VALID_SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES.contains(item.linkId)) {
                                item.value = ""
                                item.text = ""
                            }
                        }

                        "COUNTY_DISEASE_SURVEILLANCE_OFFICER" -> {
                            if (VALID_COUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES.contains(item.linkId)) {
                                item.value = ""
                                item.text = ""
                            }
                        }
                        "ADMINISTRATOR" -> {
                            if (VALID_ADMINISTRATOR_EXCLUDES.contains(item.linkId)) {
                                item.value = ""
                                item.text = ""
                            }
                        }
                    }
                }
                if (show) {
                    if (item.text.isEmpty() && item.value?.isEmpty() == true) {
                        // If both text and value are empty, skip this item
                        continue
                    } else if (item.text.isEmpty() && item.value != null) {
                        val answerFieldView = createAnswerCustomField(item)
                        parentLayout.addView(answerFieldView)
                    } else if (item.text.isNotEmpty() && item.value?.isEmpty() == true && item.type == "group") {
                        parentLayout.addView(labelOnlyView)
                    } else {
                        parentLayout.addView(fieldView)
                    }
                }
            }
        }
    }


    private fun checkIfParentAnswerMatches(
        operator: String?,
        parentLink: String?,
        parentResponseData: String?,
        items: List<OutputItem>
    ): Boolean {
        var response = false
        if (parentLink != null && parentResponseData != null) {
            val parentResponse = parentResponseData.lowercase()
            val parentAnswerData = items.find { it.linkId == parentLink }?.value

            if (parentAnswerData != null) {
                val parentAnswer = parentAnswerData.lowercase()
                if (operator != null) {
                    when (operator) {
                        "!=" -> {
                            if (parentAnswer.trim() != parentResponse.trim()) {
                                response = true
                            }
                        }

                        ">" -> {
                            if (parentAnswer.trim() > parentResponse.trim()) {
                                response = true
                            }
                        }

                        "exists" -> {
                            if (parentResponse == "true") {
                                response = true
                            }
                        }

                        else -> {
                            if (parentAnswer.trim() == parentResponse.trim() || parentAnswer.contains(
                                    parentResponse
                                )
                            ) {
                                response = true
                            }
                        }
                    }
                }
            }
        }
        return response
    }


    private fun createCustomField(item: OutputItem): View {
        // Create the main LinearLayout to hold the views
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        // Create the horizontal LinearLayout for the two TextViews
        val horizontalLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // First TextView (label)
        val label = TextView(requireContext()).apply {
            text = item.text
            textSize = 12f
            setTextColor(android.graphics.Color.BLACK)
            typeface = ResourcesCompat.getFont(requireContext(), R.font.inter)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        horizontalLayout.addView(label)
        val customFont = ResourcesCompat.getFont(requireContext(), R.font.inter)

        // Second TextView (dynamic content)
        val tvEpiLink = TextView(requireContext()).apply {
            id = R.id.tv_epi_link // Set ID if needed for further reference
            text = item.value // Assuming item.text is the dynamic text you want to show
            textSize = 13f
            textAlignment = TextView.TEXT_ALIGNMENT_TEXT_END
            setTextColor(android.graphics.Color.BLACK)
            setTypeface(customFont, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        horizontalLayout.addView(tvEpiLink)

        // Add the horizontal layout with two TextViews to the main layout
        layout.addView(horizontalLayout)

        // Add a separator View (divider)
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1 // Divider thickness
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
            setBackgroundColor(android.graphics.Color.parseColor("#CCCCCC"))
        }
        layout.addView(divider)

        return layout
    }

    private fun createCustomLabelField(item: OutputItem): View {
        // Create the main LinearLayout to hold the views
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        // Create the horizontal LinearLayout for the two TextViews
        val horizontalLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val customFont = ResourcesCompat.getFont(requireContext(), R.font.inter)
        // First TextView (label)
        val label = TextView(requireContext()).apply {
            text = item.text
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.blue))
            setTypeface(customFont, Typeface.BOLD)

            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        horizontalLayout.addView(label)

        // Add the horizontal layout with two TextViews to the main layout
        layout.addView(horizontalLayout)

        // Add a separator View (divider)
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1 // Divider thickness
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
            setBackgroundColor(android.graphics.Color.parseColor("#CCCCCC"))
        }
        layout.addView(divider)

        return layout
    }

    private fun createAnswerCustomField(item: OutputItem): View {
        // Create the main LinearLayout to hold the views
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }

        // Create the horizontal LinearLayout for the two TextViews
        val horizontalLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // First TextView (label)
        val label = TextView(requireContext()).apply {
            text = item.value
            textSize = 12f
            setTextColor(android.graphics.Color.BLACK)
            typeface = ResourcesCompat.getFont(requireContext(), R.font.inter)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        horizontalLayout.addView(label)
        val customFont = ResourcesCompat.getFont(requireContext(), R.font.inter)


        // Add the horizontal layout with two TextViews to the main layout
        layout.addView(horizontalLayout)

        // Add a separator View (divider)
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1 // Divider thickness
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
            setBackgroundColor(android.graphics.Color.parseColor("#CCCCCC"))
        }
        layout.addView(divider)

        return layout
    }


    companion object {
        fun newInstance(group: OutputGroup): GroupFragment {
            val fragment = GroupFragment()
            val bundle = Bundle()
            bundle.putString("group", Gson().toJson(group))
            fragment.arguments = bundle
            return fragment
        }
    }
}