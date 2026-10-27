package com.icl.surveillance.utils

import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.sync.Sync
import com.icl.surveillance.R
import com.icl.surveillance.fhir.AppFhirSyncWorker
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The "Sync" item of the ⋮ menu (R.menu.menu_sync), shared by every screen with a menu.
 * Inflate R.menu.menu_sync in onCreateOptionsMenu and pass items to [onItemSelected].
 */
object SyncMenu {

    /** Starts a sync for the Sync item; false for any other item. */
    fun onItemSelected(activity: AppCompatActivity, item: MenuItem): Boolean {
        if (item.itemId != R.id.action_refresh) return false
        if (!NetworkUtils.isInternetAvailable(activity)) {
            Toast.makeText(activity, "No internet connection", Toast.LENGTH_SHORT).show()
            return true
        }
        // The sync work outlives this screen; only its scheduling runs here.
        activity.lifecycleScope.launch {
            runCatching { Sync.oneTimeSync<AppFhirSyncWorker>(activity.applicationContext) }
                .onFailure { Timber.e(it, "Sync from the menu failed to start") }
        }
        Toast.makeText(activity, "Sync Started ... ", Toast.LENGTH_SHORT).show()
        return true
    }
}
