package com.icl.surveillance.workflow

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.icl.surveillance.debug.VhfWorkflowSimulator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole VHF workflow on a device, through the app's real save code (debug/VhfWorkflowSimulator):
 * flagged person -> contact -> 3 follow ups -> converted (CIF) -> lab confirmed -> confirmed ->
 * died. Records are saved in the app's database, tagged with the run's id.
 *
 * Without sync: the records stay on the device and upload on the app's next sync. To test sync
 * too, run the simulator from Settings (Simulate VHF Workflow) with "Sync after each step" on,
 * or pass `-e sync true` (instrumentation argument) on a device logged into a test server.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class VhfWorkflowSimulationTest {

    @Test
    fun everyStepOfTheVhfWorkflowPasses() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val sync = InstrumentationRegistry.getArguments().getString("sync") == "true"
        val reports = VhfWorkflowSimulator.run(app, sync)

        val problems = reports.filterNot { it.passed }.joinToString("\n") { step ->
            buildString {
                append("${step.number}. ${step.title}")
                step.error?.let { append(": $it") }
                (step.checks + step.afterSync).filterNot { it.passed }.forEach { append("\n   ✗ ${it.name} ${it.detail}") }
                if (step.synced == false) append("\n   ✗ sync failed")
            }
        }
        assertEquals("Steps run\n$problems", 9, reports.size)
        assertTrue("Failed steps:\n$problems", problems.isEmpty())
    }
}
