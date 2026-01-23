package com.icl.surveillance.holders

import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.icl.surveillance.databinding.ContactItemBinding
import com.icl.surveillance.ui.patients.PatientListViewModel.ContactResults

class ContactHolder(binding: ContactItemBinding) :
    RecyclerView.ViewHolder(binding.root) {
    private val nameView: TextView = binding.valueName
    private val epid: TextView = binding.valueEdip

    fun bindTo(
        patientItem: ContactResults,
        onItemClicked: (ContactResults) -> Unit,
    ) {
        this.nameView.text = patientItem.name
        this.epid.text = patientItem.epid

        this.itemView.setOnClickListener { onItemClicked(patientItem) }
    }
}