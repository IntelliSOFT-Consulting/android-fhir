package com.icl.surveillance.ui.patients.custom

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.icl.surveillance.R

/** One day of a contact's follow-up schedule, as shown on the Follow up tab. */
data class ScheduleRow(
    val day: Int,
    val date: String,
    val detail: String,
    val status: String,
    val tone: CardTone,
    /** Set for the one day that can be recorded now; the row then shows a "Record" button. */
    val onRecord: (() -> Unit)? = null,
    /** Any other row: what tapping it does (e.g. a toast saying why it cannot be recorded). */
    val onTap: (() -> Unit)? = null,
)

/** Cards of the VHF Follow up tab: the 21-day progress summary and the day-by-day schedule. */
object VhfScheduleCards {

    /**
     * Progress through the follow-up window: a headline ("Day 6 of 21"), the window dates, a
     * progress bar of days recorded and a row of figures (label to value, tone).
     */
    fun progress(
        parent: ViewGroup,
        headline: String,
        window: String,
        recorded: Int,
        total: Int,
        status: String,
        statusTone: CardTone,
        figures: List<Triple<String, String, CardTone>>,
    ): View {
        val context = parent.context
        val card = card(parent)
        val body = column(context, 16, 16)

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(context, headline, 18f, R.color.vhf_text_primary, bold = true))
            addView(text(context, window, 12f, R.color.vhf_text_secondary).apply { setPadding(0, dp(context, 2), 0, 0) })
        })
        top.addView(pill(context, status, statusTone))
        body.addView(top)

        body.addView(LinearProgressIndicator(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 14) }
            max = total.coerceAtLeast(1)
            progress = recorded.coerceIn(0, total)
            trackThickness = dp(context, 8)
            trackCornerRadius = dp(context, 4)
            setIndicatorColor(ContextCompat.getColor(context, R.color.vhf_success))
            trackColor = ContextCompat.getColor(context, R.color.vhf_neutral_bg)
        })
        body.addView(text(context, "$recorded of $total days recorded", 12f, R.color.vhf_text_secondary).apply {
            setPadding(0, dp(context, 6), 0, 0)
        })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 14) }
        }
        figures.forEachIndexed { index, (label, value, tone) ->
            if (index > 0) row.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 1), LinearLayout.LayoutParams.MATCH_PARENT)
                setBackgroundColor(ContextCompat.getColor(context, R.color.vhf_card_stroke))
            })
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(text(context, value, 18f, tone.accent, bold = true).apply { gravity = Gravity.CENTER })
                addView(text(context, label, 11f, R.color.vhf_text_secondary).apply { gravity = Gravity.CENTER })
            })
        }
        body.addView(row)
        card.addView(body)
        return card
    }

    /** The follow-up schedule as one card: a row per day, the recordable day highlighted. */
    fun schedule(parent: ViewGroup, rows: List<ScheduleRow>): View {
        val context = parent.context
        val card = card(parent)
        val list = column(context, 0, 0)
        rows.forEachIndexed { index, row ->
            if (index > 0) list.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1))
                    .apply { marginStart = dp(context, 64) }
                setBackgroundColor(ContextCompat.getColor(context, R.color.vhf_card_stroke))
            })
            list.addView(scheduleRow(context, row))
        }
        card.addView(list)
        return card
    }

    private fun scheduleRow(context: android.content.Context, row: ScheduleRow): View {
        val accent = ContextCompat.getColor(context, row.tone.accent)
        val active = row.onRecord != null
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            if (active) {
                setBackgroundColor(ContextCompat.getColor(context, row.tone.tint))
                setOnClickListener { row.onRecord?.invoke() }
            } else if (row.onTap != null) {
                val ripple = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener { row.onTap?.invoke() }
            }
        }
        // Day badge: filled when the day is done, outlined otherwise.
        val done = row.tone == CardTone.SUCCESS || row.tone == CardTone.ALERT
        line.addView(TextView(context).apply {
            text = row.day.toString()
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(context, 36), dp(context, 36))
            background = ContextCompat.getDrawable(context, R.drawable.bg_vhf_circle)
            if (done) {
                backgroundTintList = ColorStateList.valueOf(accent)
                setTextColor(Color.WHITE)
            } else {
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, row.tone.tint))
                setTextColor(accent)
            }
            contentDescription = "Day ${row.day}"
        })
        line.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(context, 14) }
            addView(text(context, row.date, 14f, R.color.vhf_text_primary, bold = true))
            addView(text(context, row.detail, 12f, R.color.vhf_text_secondary).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        })
        if (active) {
            line.addView(MaterialButton(context).apply {
                text = "Record"
                isAllCaps = false
                textSize = 13f
                minHeight = 0
                minimumHeight = 0
                insetTop = 0
                insetBottom = 0
                setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
                cornerRadius = dp(context, 18)
                backgroundTintList = ColorStateList.valueOf(accent)
                setTextColor(Color.WHITE)
                setIconResource(R.drawable.ic_vhf_edit_calendar)
                iconTint = ColorStateList.valueOf(Color.WHITE)
                iconSize = dp(context, 16)
                iconPadding = dp(context, 6)
                setOnClickListener { row.onRecord?.invoke() }
            })
        } else {
            line.addView(pill(context, row.status, row.tone))
        }
        line.contentDescription = "Day ${row.day}, ${row.date}, ${row.status}"
        return line
    }

    private fun card(parent: ViewGroup): MaterialCardView {
        val context = parent.context
        return MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(context, 4), dp(context, 6), dp(context, 4), dp(context, 6)) }
            radius = dp(context, 14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(context, 1)
            setStrokeColor(ContextCompat.getColor(context, R.color.vhf_card_stroke))
            setCardBackgroundColor(Color.WHITE)
        }
    }

    private fun column(context: android.content.Context, padH: Int, padV: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, padH), dp(context, padV), dp(context, padH), dp(context, padV))
    }

    private fun pill(context: android.content.Context, label: String, tone: CardTone) = TextView(context).apply {
        text = label
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, tone.accent))
        background = ContextCompat.getDrawable(context, R.drawable.bg_vhf_pill)
        backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, tone.tint))
        setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
    }

    private fun text(
        context: android.content.Context,
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
    ) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(ContextCompat.getColor(context, color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
