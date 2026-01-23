package com.icl.surveillance.adapters

import android.content.Context
import android.util.TypedValue
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
        try {
            val desiredHeightInDp = 140f
            // Use your helper function
            val desiredHeightInPixels = dpToPx(desiredHeightInDp, binding.root.context)

            val layoutParams = binding.cardHolder.layoutParams
            layoutParams.height = desiredHeightInPixels // Your dpToPx already returns Int
            binding.cardHolder.layoutParams = layoutParams
            // I also need to align the textview to center

        } catch (e: Exception) {
            e.printStackTrace()
        }
        binding.iconView.apply {
            setImageResource(layout.iconId)
            visibility = View.GONE
        }

        binding.textView.text =
            binding.textView.context.getString(layout.textId)
        binding.root.setOnClickListener { onItemClick(layout) }
    }

    private fun dpToPx(dp: Float, context: Context): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
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

