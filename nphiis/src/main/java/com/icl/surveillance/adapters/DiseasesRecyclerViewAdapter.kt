package com.icl.surveillance.adapters

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.core.content.res.ResourcesCompat
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.icl.surveillance.R
import com.icl.surveillance.databinding.LandingPageItemBinding
import com.icl.surveillance.ui.home.HomeViewModel
import com.icl.surveillance.utils.setSingleClickListener


class DiseasesRecyclerViewAdapter(
    private val onItemClick: (HomeViewModel.Diseases) -> Unit,

    ) :
    ListAdapter<HomeViewModel.Diseases, DiseaseViewHolder>(DiseaseDiffUtil()) {
    private var lastPosition = -1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiseaseViewHolder {
        return DiseaseViewHolder(
            LandingPageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onItemClick,
        )
    }

    override fun onBindViewHolder(holder: DiseaseViewHolder, position: Int) {
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

class DiseaseViewHolder(
    val binding: LandingPageItemBinding,
    private val onItemClick: (HomeViewModel.Diseases) -> Unit,
) : RecyclerView.ViewHolder(binding.root) {
    fun bind(layout: HomeViewModel.Diseases) {
        binding.iconContainer.visibility = View.GONE
        binding.iconView.visibility = View.GONE
        binding.subtitleView.visibility = View.GONE
        binding.chevronView.visibility = View.GONE

        val textLayoutParams = binding.textView.layoutParams as ConstraintLayout.LayoutParams
        textLayoutParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        textLayoutParams.topToBottom = ConstraintLayout.LayoutParams.UNSET
        textLayoutParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        textLayoutParams.bottomToTop = ConstraintLayout.LayoutParams.UNSET
        textLayoutParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        textLayoutParams.endToStart = ConstraintLayout.LayoutParams.UNSET
        textLayoutParams.topMargin = 0
        textLayoutParams.bottomMargin = 0
        textLayoutParams.marginEnd = 0
        binding.textView.layoutParams = textLayoutParams
        binding.textView.setAutoSizeTextTypeUniformWithConfiguration(11, 17, 1, TypedValue.COMPLEX_UNIT_SP)
        binding.textView.maxLines = 4
        binding.textView.setLineSpacing(0f, 1.12f)
        binding.textView.letterSpacing = 0.01f
        binding.textView.typeface = ResourcesCompat.getFont(binding.root.context, R.font.montserratsemi)
        binding.textView.text = binding.textView.context.getString(layout.textId)
        binding.root.isEnabled = true
        binding.root.setSingleClickListener { onItemClick(layout) }
    }
}

class DiseaseDiffUtil : DiffUtil.ItemCallback<HomeViewModel.Diseases>() {
    override fun areItemsTheSame(
        oldLayout: HomeViewModel.Diseases,
        newLayout: HomeViewModel.Diseases,
    ) = oldLayout === newLayout

    override fun areContentsTheSame(
        oldLayout: HomeViewModel.Diseases,
        newLayout: HomeViewModel.Diseases,
    ) = oldLayout == newLayout
}
