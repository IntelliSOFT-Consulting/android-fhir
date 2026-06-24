package com.icl.surveillance.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.icl.surveillance.databinding.SocialFormItemViewBinding
import com.icl.surveillance.holders.SocialFormItemViewHolder
import com.icl.surveillance.ui.patients.PatientListViewModel

class SocialFormItemRecyclerViewAdapter(
    private val onItemClicked: (PatientListViewModel.PatientItem) -> Unit,
) : ListAdapter<PatientListViewModel.PatientItem, SocialFormItemViewHolder>(
    SocialFormDiffCallback()
), CaseListDataAdapter {

    class SocialFormDiffCallback : DiffUtil.ItemCallback<PatientListViewModel.PatientItem>() {
        override fun areItemsTheSame(
            oldItem: PatientListViewModel.PatientItem,
            newItem: PatientListViewModel.PatientItem
        ): Boolean = oldItem.resourceId == newItem.resourceId

        override fun areContentsTheSame(
            oldItem: PatientListViewModel.PatientItem,
            newItem: PatientListViewModel.PatientItem
        ): Boolean = oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SocialFormItemViewHolder {
        return SocialFormItemViewHolder(
            SocialFormItemViewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: SocialFormItemViewHolder, position: Int) {
        holder.bindTo(currentList[position], onItemClicked)
    }

    override fun setData(list: List<PatientListViewModel.PatientItem>) {
        submitList(list)
    }
}
