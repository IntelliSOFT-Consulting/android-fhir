package com.icl.surveillance.holders

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.icl.surveillance.R
import com.icl.surveillance.databinding.PatientListItemViewBinding
import com.icl.surveillance.ui.patients.PatientListViewModel
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.toSlug
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.WeekFields
import java.util.Locale

class PatientItemViewHolder(binding: PatientListItemViewBinding) :
    RecyclerView.ViewHolder(binding.root) {
    private val nameView: TextView = binding.name
    private val parentLayout: LinearLayout = binding.lnParentHolder
    private val parentOriginal: LinearLayout = binding.lnParentOriginal
    private val epid: TextView = binding.epid
    private val county: TextView = binding.county
    private val subCounty: TextView = binding.subCounty
    private val dateReported: TextView = binding.dateReported
    private val status: TextView = binding.tvFinal
    private val labResults: TextView = binding.labResults
    private val tvLabLabel: TextView = binding.tvLabLabel
    private val tvFinalClassificationLabel: TextView = binding.tvFinalClassificationLabel
    private val tvPatientNameLabel: TextView = binding.tvPatientNameLabel
    private val tvDateLabel: TextView = binding.tvDateLabel
    private val lnNameAndEpid: LinearLayout = binding.lnNameAndEpid
    private val lnFinalClassification: LinearLayout = binding.lnFinalClassification


    private val mpoxCounty: TextView = binding.mpoxCounty
    private val mpoxSubCounty: TextView = binding.mpoxSubCounty
    private val date: TextView = binding.date
    private val type: TextView = binding.type
    private val tvType: TextView = binding.tvType
    private val teamNumber: TextView = binding.teamNumber
    private val supervisorName: TextView = binding.supervisorName
    private val tvTeamNumberLabel: TextView = binding.tvTeamNumberLabel
    private val lnDateResultsLabel: LinearLayout = binding.tvDateResultsLabel
    private val tvDateResultsValue: LinearLayout = binding.tvDateResultsValue
    private val tvEpidLabel: TextView = binding.tvEpidLabel
    private val lnCountyLabels: LinearLayout = binding.lnCountyLabels
    private val lnCountyDetails: LinearLayout = binding.lnCountyDetails


    fun bindTo(
        patientItem: PatientListViewModel.PatientItem,
        onItemClicked: (PatientListViewModel.PatientItem) -> Unit,
        listingTitle: String,
        context: Context
    ) {
        this.nameView.text = patientItem.name
        this.epid.text = patientItem.epid
        this.county.text = patientItem.county
        this.subCounty.text = patientItem.subCounty
        this.dateReported.text = patientItem.caseOnsetDate
        this.labResults.text = patientItem.labResults


        try {
            if (listingTitle.isNotEmpty()) {
                if (listingTitle.contains("VL Case List")) {
                    this.tvFinalClassificationLabel.text = "Final Diagnosis"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // what is the current case we are dealing with?
        val current = FormatterClass().getSharedPref("currentCase", context)

        if (current != null) {
            val slug = current.toSlug()
            when (slug) {
                "mpox-register" -> {
                    if (!patientItem.epid.startsWith("KEN")) {
                        this.lnCountyLabels.visibility = View.GONE
                        this.lnCountyDetails.visibility = View.GONE
                        this.labResults.text = patientItem.subCounty
                    }

                    this.lnFinalClassification.visibility = View.GONE
                    this.tvDateLabel.text = "Occupation"
                    this.tvLabLabel.text = "Vaccination Center"
                    this.labResults.text = patientItem.vaccinationCenter
                    this.dateReported.text = patientItem.occupation
                    this.tvEpidLabel.text = "Serial Number"
                }

                "mpox-supervisor-checklist", "mpox-tally-sheet" -> {
                    this.parentLayout.visibility = View.VISIBLE
                    this.parentOriginal.visibility = View.GONE
                    this.mpoxCounty.text = patientItem.county
                    this.mpoxSubCounty.text = patientItem.subCounty
                    this.date.text = patientItem.caseOnsetDate
                    if (slug == "mpox-tally-sheet") {
                        this.tvType.text = "Campaign Day"
                        this.tvTeamNumberLabel.text = "Team Number"
                    }
                    this.type.text = patientItem.campaignDate
                    this.supervisorName.text = patientItem.supervisorName
                    this.teamNumber.text = patientItem.teamNumber


                }

                "moh-505-reporting-form" -> {
                    this.lnFinalClassification.visibility = View.GONE
                    this.lnNameAndEpid.visibility = View.GONE
                    this.tvDateLabel.text = "Week Ending Date"
                    this.tvLabLabel.text = "Epi Week"

                    val weekOfYear = getWeekOfYear(patientItem.caseOnsetDate)
                    this.labResults.text = "$weekOfYear"
                }
            }
        }

        var final = patientItem.status

        if (patientItem.caseList.trim() != "Case") {
            this.tvLabLabel.visibility = View.INVISIBLE
            this.labResults.visibility = View.INVISIBLE
            final = "Confirmed by EPI Linkage"
        }
        this.status.text = final
        this.status.setTextColor(Color.BLACK)
        this.labResults.setTextColor(Color.BLACK)
        when (final.trim()) {
            "Confirmed by lab" -> {
                this.status.setTextColor(this.status.context.getColor(R.color.red))
            }

            "Discarded" -> {
                this.status.setTextColor(this.status.context.getColor(R.color.discarded))
            }

            "Compatible/Clinical/Probable" -> {
                this.status.setTextColor(this.status.context.getColor(R.color.compatible))
            }

            "Confirmed by EPI Linkage" -> {
                this.status.setTextColor(this.status.context.getColor(R.color.blue))
            }

            else -> {
                this.status.setTextColor(this.status.context.getColor(R.color.pending))
            }
        }

        if (patientItem.labResults.trim() == "Positive") {
            this.labResults.setTextColor(this.labResults.context.getColor(R.color.red))
        }

        this.itemView.setOnClickListener { onItemClicked(patientItem) }
    }

    private fun getWeekOfYear(dateString: String, pattern: String = "yyyy-MM-dd"): String {
        return try {
            val formatter = DateTimeFormatter.ofPattern(pattern)
            val date = LocalDate.parse(dateString, formatter)
            val weekFields = WeekFields.of(Locale.getDefault())
            date.get(weekFields.weekOfWeekBasedYear())
                .toString()
                .padStart(2, '0') // Ensure two digits for week number
        } catch (e: DateTimeParseException) {
            println("Invalid date format: ${e.message}")
            ""
        }
    }

    /** The new ui just shows shortened id with just last 3 characters. */
    private fun getTruncatedId(patientItem: PatientListViewModel.PatientItem): String {
        return patientItem.resourceId.takeLast(3)
    }
}