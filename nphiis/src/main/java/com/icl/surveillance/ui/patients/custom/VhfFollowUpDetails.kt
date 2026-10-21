package com.icl.surveillance.ui.patients.custom

import com.icl.surveillance.fhir.forms.answerText
import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.datacapture.extensions.asStringValue
import com.google.android.fhir.search.search
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.icl.surveillance.R
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.FormFields
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.Observation
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only view of a recorded contact follow up: every answered question of the follow-up
 * form, in the form's order and sections, from the answers saved on its Encounter.
 */
object VhfFollowUpDetails {

    private const val HIDDEN = "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden"

    private sealed class Line {
        data class Header(val text: String) : Line()
        data class Answer(val question: String, val value: String) : Line()
    }

    fun show(context: Context, owner: LifecycleOwner, encounterId: String, title: String) {
        owner.lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) {
                runCatching { load(context, encounterId) }.getOrDefault(emptyList())
            }
            if (lines.none { it is Line.Answer }) {
                Toast.makeText(context, "No details were found for this follow up.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            present(context, title, lines)
        }
    }

    private suspend fun load(context: Context, encounterId: String): List<Line> {
        val answers: Map<String, List<String>> = FhirApplication.fhirEngine(context)
            .search<Observation> { filter(Observation.ENCOUNTER, { value = "Encounter/$encounterId" }) }
            .map { it.resource }
            .filter { it.status != Observation.ObservationStatus.ENTEREDINERROR }
            .groupBy { it.code.codingFirstRep.code.orEmpty() }
            .mapValues { (_, list) ->
                list.mapNotNull { it.value?.answerText()?.trim()?.takeIf(String::isNotBlank) }.distinct()
            }
            .filterValues { it.isNotEmpty() }

        val form = context.assets.open(FormFields.Vhf.CONTACT_FOLLOW_UP_FORM)
            .bufferedReader().use { JSONObject(it.readText()) }
        val lines = ArrayList<Line>()
        fun isHidden(item: JSONObject): Boolean {
            val ext = item.optJSONArray("extension") ?: return false
            return (0 until ext.length()).any { i ->
                ext.optJSONObject(i)?.let { it.optString("url") == HIDDEN && it.optBoolean("valueBoolean") } == true
            }
        }
        fun visit(items: JSONArray?) {
            if (items == null) return
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val type = item.optString("type")
                val text = item.optString("text").trim()
                when (type) {
                    "group" -> {
                        val before = lines.size
                        lines.add(Line.Header(text))
                        visit(item.optJSONArray("item"))
                        // A section with no answers is left out.
                        if (lines.size == before + 1) lines.removeAt(before)
                    }
                    "display" -> Unit
                    else -> if (!isHidden(item)) {
                        answers[item.optString("linkId")]?.let { values ->
                            lines.add(Line.Answer(text, values.joinToString(", ")))
                        }
                        visit(item.optJSONArray("item"))
                    }
                }
            }
        }
        visit(form.optJSONArray("item"))
        return lines
    }

    private fun present(context: Context, title: String, lines: List<Line>) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val primary = ContextCompat.getColor(context, R.color.case_item_primary_text)
        val secondary = ContextCompat.getColor(context, R.color.case_item_secondary_text)
        val heading = ContextCompat.getColor(context, R.color.home_card_title)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        column.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(heading)
        })
        lines.forEach { line ->
            when (line) {
                is Line.Header -> column.addView(TextView(context).apply {
                    text = line.text
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(heading)
                    setPadding(0, dp(18), 0, dp(4))
                })
                is Line.Answer -> {
                    column.addView(TextView(context).apply {
                        text = line.question
                        textSize = 12f
                        setTextColor(secondary)
                        setPadding(0, dp(8), 0, 0)
                    })
                    column.addView(TextView(context).apply {
                        text = line.value
                        textSize = 15f
                        setTextColor(primary)
                    })
                }
            }
        }
        val scroll = NestedScrollView(context).apply {
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        BottomSheetDialog(context).apply {
            setContentView(scroll)
            show()
        }
    }
}
