package com.icl.surveillance.fhir.forms

import android.content.Context
import com.icl.surveillance.utils.FormatterClass

/**
 * Demo mode for the VHF contact follow-up, switched on from a case's Contacts tab.
 *
 * When on, follow ups can be recorded for the next day in the schedule without waiting for that
 * day's date (each is dated today and keeps its entered day number), so the full 21-day flow can
 * be shown in one sitting. When off, the normal date rules apply. On by default for demos.
 */
object VhfDemoMode {
    private const val PREF = "vhfFollowUpDemoMode"

    /** Last known setting; refreshed from preferences by the screens that use it. */
    @Volatile
    var enabled: Boolean = false
        private set

    /** On unless it has been switched off on the Contacts tab. */
    fun refresh(context: Context): Boolean {
        enabled = FormatterClass().getSharedPref(PREF, context) != "false"
        return enabled
    }

    fun set(context: Context, on: Boolean) {
        FormatterClass().saveSharedPref(PREF, on.toString(), context)
        enabled = on
    }
}
