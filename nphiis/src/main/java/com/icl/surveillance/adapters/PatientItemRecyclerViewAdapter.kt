package com.icl.surveillance.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.icl.surveillance.databinding.PatientListItemViewBinding
import com.icl.surveillance.holders.PatientItemViewHolder
import com.icl.surveillance.ui.patients.PatientListViewModel

class PatientItemRecyclerViewAdapter(
    private val onItemClicked: (PatientListViewModel.PatientItem) -> Unit,
    private val listingTitle: String,
    private val context: Context
) : ListAdapter<PatientListViewModel.PatientItem, PatientItemViewHolder>(
    PatientItemDiffCallback()
) {
    // Keep a full copy of the unfiltered list
    private var fullList: List<PatientListViewModel.PatientItem> = emptyList()

    class PatientItemDiffCallback : DiffUtil.ItemCallback<PatientListViewModel.PatientItem>() {
        override fun areItemsTheSame(
            oldItem: PatientListViewModel.PatientItem,
            newItem: PatientListViewModel.PatientItem
        ): Boolean = oldItem.resourceId == newItem.resourceId

        override fun areContentsTheSame(
            oldItem: PatientListViewModel.PatientItem,
            newItem: PatientListViewModel.PatientItem
        ): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PatientItemViewHolder {
        return PatientItemViewHolder(
            PatientListItemViewBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun onBindViewHolder(holder: PatientItemViewHolder, position: Int) {
        val item = currentList[position]
        holder.bindTo(item, onItemClicked, listingTitle, context)
    }

    /**
     * Store full list and display it
     */
    fun setData(list: List<PatientListViewModel.PatientItem>) {
        fullList = list
        submitList(list)
    }

    /**
     * Filter patients by query text
     */
    fun filter(query: String) {
        val filteredList = if (query.isBlank()) {
            fullList
        } else {
            fullList.filter { patient ->
                patient.epid.contains(query, ignoreCase = true) ||
                        patient.name.contains(query, ignoreCase = true)
            }
        }
        submitList(filteredList)
    }
}

