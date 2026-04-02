package com.icl.surveillance.utils

import android.view.View

private const val DEFAULT_CLICK_DEBOUNCE_DELAY_MS = 600L

fun View.setSingleClickListener(
    debounceDelayMs: Long = DEFAULT_CLICK_DEBOUNCE_DELAY_MS,
    onClick: (View) -> Unit,
) {
    setOnClickListener { view ->
        if (!view.isEnabled) {
            return@setOnClickListener
        }

        view.isEnabled = false
        try {
            onClick(view)
        } finally {
            view.postDelayed({ view.isEnabled = true }, debounceDelayMs)
        }
    }
}
