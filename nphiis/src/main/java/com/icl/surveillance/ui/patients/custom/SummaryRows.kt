package com.icl.surveillance.ui.patients.custom

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.icl.surveillance.R

/** Label / value rows in the style of the case-summary tabs. */
object SummaryRows {

    fun title(context: Context, text: String): View =
        row(context) {
            addView(TextView(context).apply {
                this.text = text
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(context, R.color.summary_page_title_text))
            })
        }

    fun field(
        context: Context,
        label: String,
        value: String,
        @ColorRes valueColor: Int = R.color.summary_page_value_text,
        onClick: (() -> Unit)? = null,
    ): View = row(context) {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        line.addView(TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.summary_page_label_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        line.addView(TextView(context).apply {
            text = value
            textSize = 13f
            textAlignment = TextView.TEXT_ALIGNMENT_TEXT_END
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, valueColor))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(line)
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    fun note(context: Context, text: String, @ColorRes color: Int = R.color.summary_page_label_text): View =
        row(context) {
            addView(TextView(context).apply {
                this.text = text
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, color))
            })
        }

    private fun row(context: Context, content: LinearLayout.() -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            content()
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply {
                    topMargin = 8
                    bottomMargin = 8
                }
                setBackgroundColor(ContextCompat.getColor(context, R.color.summary_page_divider))
            })
        }
}
