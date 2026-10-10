package com.icl.surveillance.ui.patients.custom

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.appcompat.widget.SwitchCompat
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ItemVhfActionCardBinding
import com.icl.surveillance.databinding.ItemVhfContactCardBinding
import com.icl.surveillance.databinding.ItemVhfStatTileBinding

/** Colour pairs (accent on a light tint) used by the VHF contact cards. */
enum class CardTone(@ColorRes val accent: Int, @ColorRes val tint: Int) {
    INFO(R.color.home_icon_tint, R.color.home_icon_container_background),
    ALERT(R.color.account_danger_action, R.color.home_card_background),
    SUCCESS(R.color.selection_sheet_secondary_icon_tint, R.color.selection_sheet_secondary_icon_bg),
    WARNING(R.color.snackbar_warning, R.color.home_card_background),
    NEUTRAL(R.color.home_card_chevron_tint, R.color.home_surface_background),
}

/** Builds the cards used on the VHF Daily Follow Up and Contacts tabs. */
object VhfCards {

    /**
     * A next-step card: icon, title and a line of context, with at most one action button.
     * Without [actionText] it is an information card.
     */
    fun action(
        parent: ViewGroup,
        tone: CardTone,
        @DrawableRes icon: Int,
        title: String,
        message: String,
        actionText: String? = null,
        @DrawableRes actionIcon: Int? = null,
        onAction: (() -> Unit)? = null,
    ): View {
        val context = parent.context
        val accent = ContextCompat.getColor(context, tone.accent)
        val binding = ItemVhfActionCardBinding.inflate(LayoutInflater.from(context), parent, false)
        binding.actionCard.setCardBackgroundColor(ContextCompat.getColor(context, tone.tint))
        binding.actionCard.setStrokeColor(withAlpha(accent, 0x40))
        binding.actionIcon.setImageResource(icon)
        binding.actionIcon.imageTintList = ColorStateList.valueOf(accent)
        binding.actionTitle.text = title
        binding.actionTitle.setTextColor(accent)
        binding.actionMessage.text = message
        binding.actionMessage.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        if (actionText != null && onAction != null) {
            binding.actionButton.text = actionText
            binding.actionButton.backgroundTintList = ColorStateList.valueOf(accent)
            actionIcon?.let { binding.actionButton.setIconResource(it) }
            binding.actionButton.setOnClickListener { onAction() }
        } else {
            binding.actionButton.visibility = View.GONE
        }
        return binding.root
    }

    /** A row of summary figures: (label, value, tone). */
    fun stats(parent: ViewGroup, items: List<Triple<String, String, CardTone>>): View {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 4) }
        }
        items.forEach { (label, value, tone) ->
            val tile = ItemVhfStatTileBinding.inflate(LayoutInflater.from(context), row, false)
            tile.root.setCardBackgroundColor(ContextCompat.getColor(context, tone.tint))
            tile.statValue.text = value
            tile.statValue.setTextColor(ContextCompat.getColor(context, tone.accent))
            tile.statLabel.text = label
            row.addView(tile.root)
        }
        return row
    }

    /** A tappable contact card with initials, name, EPID and a status pill. */
    fun contact(
        parent: ViewGroup,
        name: String,
        epid: String,
        status: String,
        tone: CardTone,
        onClick: () -> Unit,
    ): View {
        val context = parent.context
        val accent = ContextCompat.getColor(context, tone.accent)
        val tint = ContextCompat.getColor(context, tone.tint)
        val binding = ItemVhfContactCardBinding.inflate(LayoutInflater.from(context), parent, false)
        binding.contactInitials.text = initials(name)
        binding.contactInitials.backgroundTintList = ColorStateList.valueOf(tint)
        binding.contactInitials.setTextColor(accent)
        binding.contactName.text = name
        binding.contactEpid.text = epid.ifBlank { "EPID pending" }
        binding.contactStatus.text = status
        binding.contactStatus.backgroundTintList = ColorStateList.valueOf(tint)
        binding.contactStatus.setTextColor(accent)
        binding.root.contentDescription = "$name, $status"
        binding.root.setOnClickListener { onClick() }
        return binding.root
    }

    /**
     * The open day in the follow-up schedule: a tinted row with the day and a "Record" button,
     * shown in its place in the day-by-day list.
     */
    fun scheduleAction(
        parent: ViewGroup,
        label: String,
        caption: String,
        actionText: String,
        onAction: () -> Unit,
    ): View {
        val context = parent.context
        val tone = CardTone.INFO
        val accent = ContextCompat.getColor(context, tone.accent)
        val density = context.resources.displayMetrics.density
        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, (6 * density).toInt(), 0, (6 * density).toInt()) }
            setRadius(12 * density)
            cardElevation = 0f
            strokeWidth = (1 * density).toInt()
            setStrokeColor(withAlpha(accent, 0x55))
            setCardBackgroundColor(ContextCompat.getColor(context, tone.tint))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(context).apply {
            text = label
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
        })
        texts.addView(TextView(context).apply {
            text = caption
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.home_card_subtitle))
        })
        row.addView(texts)
        row.addView(MaterialButton(context).apply {
            text = actionText
            isAllCaps = false
            cornerRadius = (20 * density).toInt()
            backgroundTintList = ColorStateList.valueOf(accent)
            setTextColor(ContextCompat.getColor(context, R.color.home_card_background))
            setIconResource(R.drawable.ic_vhf_edit_calendar)
            iconTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.home_card_background))
            iconSize = (18 * density).toInt()
            iconPadding = (6 * density).toInt()
            setOnClickListener { onAction() }
        })
        card.addView(row)
        card.setOnClickListener { onAction() }
        return card
    }

    /** A settings-style switch card: title and subtitle with a switch, tinted amber when on. */
    fun toggle(
        parent: ViewGroup,
        title: String,
        subtitle: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        val tone = if (checked) CardTone.WARNING else CardTone.NEUTRAL
        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt()) }
            setRadius(12 * density)
            cardElevation = 0f
            strokeWidth = (1 * density).toInt()
            setStrokeColor(ContextCompat.getColor(context, R.color.home_card_stroke))
            setCardBackgroundColor(ContextCompat.getColor(context, tone.tint))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (14 * density).toInt()
            setPadding(pad, (10 * density).toInt(), pad, (10 * density).toInt())
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, tone.accent))
        })
        texts.addView(TextView(context).apply {
            text = subtitle
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.home_card_subtitle))
        })
        row.addView(texts)
        row.addView(SwitchCompat(context).apply {
            isChecked = checked
            contentDescription = title
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
        })
        card.addView(row)
        return card
    }

    /** A section title with an optional outlined button on the right (e.g. "Register contact"). */
    fun sectionHeader(
        parent: ViewGroup,
        title: String,
        actionText: String? = null,
        @DrawableRes actionIcon: Int? = null,
        onAction: (() -> Unit)? = null,
    ): View {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        val accent = ContextCompat.getColor(context, R.color.home_icon_tint)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt(), (4 * density).toInt()) }
        }
        row.addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.home_card_title))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (actionText != null && onAction != null) {
            row.addView(
                MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = actionText
                    isAllCaps = false
                    cornerRadius = (20 * density).toInt()
                    setTextColor(accent)
                    setStrokeColor(ColorStateList.valueOf(accent))
                    actionIcon?.let {
                        setIconResource(it)
                        iconTint = ColorStateList.valueOf(accent)
                        iconSize = (18 * density).toInt()
                    }
                    setOnClickListener { onAction() }
                }
            )
        }
        return row
    }

    private fun initials(name: String): String =
        name.split(" ").filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "?" }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
