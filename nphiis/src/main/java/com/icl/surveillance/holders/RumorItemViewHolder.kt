package com.icl.surveillance.holders

import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.icl.surveillance.databinding.RumorItemViewBinding
import com.icl.surveillance.ui.patients.PatientListViewModel.RumorItem


class RumorItemViewHolder(binding: RumorItemViewBinding) : RecyclerView.ViewHolder(binding.root) {
    private val nameView: TextView = binding.name
    private val epid: TextView = binding.epid
    private val county: TextView = binding.county
    private val subCounty: TextView = binding.subCounty
    private val dateReported: TextView = binding.dateReported
    private val labResults: TextView = binding.labResults

    fun bindTo(
        patientItem: RumorItem,
        onItemClicked: (RumorItem) -> Unit,
    ) {
        this.nameView.text = patientItem.mohName
        this.epid.text = patientItem.directorate
        this.county.text = patientItem.county
        this.subCounty.text = patientItem.subCounty
        this.dateReported.text = patientItem.division
        this.labResults.text = patientItem.village

        this.itemView.setOnClickListener { onItemClicked(patientItem) }
    }


}