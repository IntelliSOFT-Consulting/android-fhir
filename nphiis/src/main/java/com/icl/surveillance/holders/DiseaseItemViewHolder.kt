package com.icl.surveillance.holders

import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.icl.surveillance.databinding.DiseaseHolderBinding
import com.icl.surveillance.ui.patients.PatientListViewModel

class DiseaseItemViewHolder(binding: DiseaseHolderBinding) : RecyclerView.ViewHolder(binding.root) {
  private val nameView: TextView = binding.tvFever

  //  private val epid: TextView = binding.epid
  //  private val county: TextView = binding.county
  //  private val subCounty: TextView = binding.subCounty
  //  private val dateReported: TextView = binding.dateReported

  fun bindTo(
      patientItem: PatientListViewModel.CaseDiseaseData,
      onItemClicked: (PatientListViewModel.CaseDiseaseData) -> Unit,
  ) {
    this.nameView.text = patientItem.name
    //    this.epid.text = patientItem.epid
    //    this.county.text = patientItem.county
    //    this.subCounty.text = patientItem.subCounty
    //    this.dateReported.text = patientItem.caseOnsetDate
    this.itemView.setOnClickListener { onItemClicked(patientItem) }
  }
}
