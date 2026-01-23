package com.icl.surveillance.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.icl.surveillance.R
import com.icl.surveillance.databinding.LandingPageItemBinding
import com.icl.surveillance.ui.home.HomeViewModel


class HomeRecyclerViewAdapter(
    private val onItemClick: (HomeViewModel.Layout) -> Unit,
    private val showIcon: Boolean = false
) :
    ListAdapter<HomeViewModel.Layout, LayoutViewHolder>(LayoutDiffUtil()) {
    private var lastPosition = -1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LayoutViewHolder {
        return LayoutViewHolder(
            LandingPageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onItemClick, showIcon
        )
    }

    override fun onBindViewHolder(holder: LayoutViewHolder, position: Int) {
        holder.bind(getItem(position))
        val adapterPos = holder.adapterPosition
        if (adapterPos != RecyclerView.NO_POSITION && adapterPos > lastPosition) {
            val animation = AnimationUtils.loadAnimation(
                holder.itemView.context,
                R.anim.item_animation_fall_down
            )
            holder.itemView.startAnimation(animation)
            lastPosition = adapterPos
        }
    }
}

class LayoutViewHolder(
    val binding: LandingPageItemBinding,
    private val onItemClick: (HomeViewModel.Layout) -> Unit,
    private val showIcon: Boolean,
) : RecyclerView.ViewHolder(binding.root) {
    fun bind(layout: HomeViewModel.Layout) {
        if (!showIcon) {
            binding.iconView.visibility = View.GONE
        }
        binding.iconView.setImageResource(layout.iconId)
        binding.textView.text = binding.textView.context.getString(layout.textId)
        binding.root.setOnClickListener { onItemClick(layout) }
    }
}

class LayoutDiffUtil : DiffUtil.ItemCallback<HomeViewModel.Layout>() {
    override fun areItemsTheSame(
        oldLayout: HomeViewModel.Layout,
        newLayout: HomeViewModel.Layout,
    ) = oldLayout === newLayout

    override fun areContentsTheSame(
        oldLayout: HomeViewModel.Layout,
        newLayout: HomeViewModel.Layout,
    ) = oldLayout == newLayout
}

