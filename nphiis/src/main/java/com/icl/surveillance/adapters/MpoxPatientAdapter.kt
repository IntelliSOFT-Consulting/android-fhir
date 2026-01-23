package com.icl.surveillance.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.icl.surveillance.R
import com.icl.surveillance.databinding.PatientListItemViewBinding
import com.icl.surveillance.ui.patients.PatientListViewModel


class MpoxPatientAdapter(
    private val items: MutableList<PatientListViewModel.PatientItem>,
    private val onItemClicked: (PatientListViewModel.PatientItem) -> Unit,
    private val listingTitle: String,
    private val context: Context
) : RecyclerView.Adapter<MpoxPatientAdapter.PatientViewHolder>() {

    inner class PatientViewHolder(binding: PatientListItemViewBinding) :
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
        fun bind(
            patientItem: PatientListViewModel.PatientItem,
            onItemClicked: (PatientListViewModel.PatientItem) -> Unit,
            listingTitle: String,
            context: Context
        ) {
            this.itemView.setOnClickListener { onItemClicked(patientItem) }
            this.nameView.text = patientItem.name
            this.epid.text = patientItem.epid
            this.county.text = patientItem.county
            this.subCounty.text = patientItem.subCounty
            this.dateReported.text = patientItem.caseOnsetDate
            this.labResults.text = patientItem.labResults
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatientViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.patient_list_item_view, parent, false)
        val binding = PatientListItemViewBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PatientViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PatientViewHolder, position: Int) {
        holder.bind(items[position], onItemClicked, listingTitle, context)
    }

    override fun getItemCount() = items.size

    fun addPatients(newItems: List<PatientListViewModel.PatientItem>) {
        val start = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
    }
}
