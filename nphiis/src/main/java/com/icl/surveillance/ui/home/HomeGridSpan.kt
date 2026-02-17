package com.icl.surveillance.ui.home

import android.content.Context

private const val TWO_COLUMN_MIN_WIDTH_DP = 390
private const val THREE_COLUMN_MIN_WIDTH_DP = 700

fun Context.homeGridSpanCount(): Int {
    val screenWidthDp = resources.configuration.screenWidthDp
    return when {
        screenWidthDp >= THREE_COLUMN_MIN_WIDTH_DP -> 3
        screenWidthDp >= TWO_COLUMN_MIN_WIDTH_DP -> 2
        else -> 1
    }
}
