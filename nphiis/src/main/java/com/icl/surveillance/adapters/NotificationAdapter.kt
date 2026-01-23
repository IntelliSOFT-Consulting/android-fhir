package com.icl.surveillance.adapters

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ItemNotificationBinding
import com.icl.surveillance.models.Notification
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone


class NotificationAdapter(private var items: List<Notification>) :
    RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemNotificationBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNotificationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val notification = items[position]
        holder.binding.tvMessage.text = notification.body
        val relativeTime = notification.createdAt.toTimeAgo()
        holder.binding.apply {
            tvTitle.text=notification.title
            tvTime.text = relativeTime
        }
    }

    fun String.toTimeAgo(): String {
        return try {
            val createdTime = Instant.parse(this)
            val now = Instant.now()
            val duration = Duration.between(createdTime, now)

            val seconds = duration.seconds
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24

            when {
                seconds < 60 -> "just now"
                minutes < 60 -> "$minutes minute${if (minutes > 1) "s" else ""} ago"
                hours < 24 -> "$hours hour${if (hours > 1) "s" else ""} ago"
                days < 7 -> "$days day${if (days > 1) "s" else ""} ago"
                else -> {
                    val date = createdTime.atZone(ZoneId.systemDefault()).toLocalDate()
                    "on $date"
                }
            }
        } catch (e: Exception) {
            // Log the error if needed: Log.e("TimeAgo", "Failed to parse date: $this", e)
            ""
        }
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<Notification>) {
        items = newItems
        notifyDataSetChanged()
    }
}