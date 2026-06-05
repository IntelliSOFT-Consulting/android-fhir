package com.icl.surveillance.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ItemNotificationBinding
import com.icl.surveillance.models.Notification
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class NotificationMonthSection(
    val title: String,
    val notifications: List<Notification>
)

class NotificationAdapter(
    private val context: Context,
    notifications: List<Notification> = emptyList()
) : RecyclerView.Adapter<NotificationAdapter.NotificationViewHolder>() {

    private var items: List<Notification> = notifications

    class NotificationViewHolder(val binding: ItemNotificationBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val binding = ItemNotificationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return NotificationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        val notification = items[position]
        val relativeTime = notification.toTimeAgo()
        holder.binding.apply {
            tvTitle.text =
                notification.title.ifBlank {
                    context.getString(R.string.notification_default_title)
                }
            tvMessage.text = notification.body
            tvTime.text = relativeTime
        }
    }

    fun String.toTimeAgo(): String {
        return try {
            parseToInstant()?.toTimeAgo().orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    private fun Notification.toTimeAgo(): String {
        return primaryInstant()?.toTimeAgo().orEmpty()
    }

    override fun getItemCount() = items.size

    fun updateData(newItems: List<Notification>) {
        items = newItems
        notifyDataSetChanged()
    }

    companion object {
        fun buildMonthSections(
            context: Context,
            notifications: List<Notification>
        ): List<NotificationMonthSection> {
            if (notifications.isEmpty()) return emptyList()

            val formatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
            return sortNotifications(notifications)
                .groupBy { notification ->
                    notification.primaryInstant()
                        ?.atZone(ZoneId.systemDefault())
                        ?.format(formatter)
                        ?: context.getString(R.string.notifications_unknown_month)
                }
                .map { (title, groupedNotifications) ->
                    NotificationMonthSection(title, groupedNotifications)
                }
        }
    }
}

private fun sortNotifications(notifications: List<Notification>): List<Notification> {
    return notifications.sortedByDescending { notification ->
        notification.primaryInstant() ?: Instant.MIN
    }
}

private fun Notification.primaryInstant(): Instant? {
    return createdAt.parseToInstant() ?: updatedAt.parseToInstant()
}

private fun String.parseToInstant(): Instant? {
    if (isBlank()) return null

    return runCatching { Instant.parse(this) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(this).toInstant() }.getOrNull()
        ?: runCatching {
            LocalDateTime.parse(this).atZone(ZoneId.systemDefault()).toInstant()
        }.getOrNull()
}

private fun Instant.toTimeAgo(): String {
    val now = Instant.now()
    val duration = Duration.between(this, now)

    val seconds = duration.seconds.coerceAtLeast(0)
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        seconds < 60 -> "just now"
        minutes < 60 -> "$minutes minute${if (minutes > 1) "s" else ""} ago"
        hours < 24 -> "$hours hour${if (hours > 1) "s" else ""} ago"
        days < 7 -> "$days day${if (days > 1) "s" else ""} ago"
        else -> {
            val date = atZone(ZoneId.systemDefault()).toLocalDate()
            "on $date"
        }
    }
}
