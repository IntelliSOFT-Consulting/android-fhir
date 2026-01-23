package com.icl.surveillance.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.icl.surveillance.databinding.LandingPageItemBinding
import com.icl.surveillance.ui.home.config.ConfigItem

class ConfigHomeAdapter(
	private val onItemClick: (ConfigItem) -> Unit,
	private val iconResolver: (ConfigItem) -> Int
) : ListAdapter<ConfigItem, ConfigHomeViewHolder>(Diff()) {

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfigHomeViewHolder {
		return ConfigHomeViewHolder(
			LandingPageItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
			onItemClick
		)
	}

	override fun onBindViewHolder(holder: ConfigHomeViewHolder, position: Int) {
		val item = getItem(position)
		holder.bind(item, iconResolver(item))
	}

	private class Diff : DiffUtil.ItemCallback<ConfigItem>() {
		override fun areItemsTheSame(oldItem: ConfigItem, newItem: ConfigItem): Boolean = oldItem.id == newItem.id
		override fun areContentsTheSame(oldItem: ConfigItem, newItem: ConfigItem): Boolean = oldItem == newItem
	}
}

class ConfigHomeViewHolder(
	private val binding: LandingPageItemBinding,
	private val onItemClick: (ConfigItem) -> Unit
) : RecyclerView.ViewHolder(binding.root) {
	fun bind(item: ConfigItem, iconRes: Int) {
		binding.textView.text = item.label
		if (iconRes != 0) {
			binding.iconView.visibility = View.VISIBLE
			binding.iconView.setImageResource(iconRes)
		} else {
			binding.iconView.visibility = View.GONE
		}
		binding.root.setOnClickListener { onItemClick(item) }
	}
}


