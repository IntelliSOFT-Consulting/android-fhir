package com.icl.surveillance.clients

import android.graphics.Bitmap
import android.widget.DatePicker
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.hamcrest.Matcher
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.icl.surveillance.R
import com.icl.surveillance.models.ClientDemographics
import com.icl.surveillance.models.ClientLookupOutcome
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClientLookupUiTest {
    private val adult = ClientDemographics(givenNames = "Test", surname = "Client", sex = "female",
        phone = "0700000000", identificationNumber = "TEST001", dateOfBirth = "1990-01-02",
        currentResidence = "HIDDEN ADDRESS", recordDetails = listOf("Hidden label" to "HIDDEN VALUE"))
    private val child = ClientDemographics(givenNames = "Test", surname = "Dependant", sex = "male",
        dateOfBirth = "2018-03-04", relationship = "Child")

    private fun show(activity: ClientLookupActivity, outcome: ClientLookupOutcome, minor: Boolean = false) {
        if (minor) activity.findViewById<RadioGroup>(R.id.ageToggle).check(R.id.ageMinor)
        ClientLookupActivity::class.java.getDeclaredMethod("showOutcome", ClientLookupOutcome::class.java,
            Boolean::class.javaPrimitiveType, String::class.java).apply { isAccessible = true }
            .invoke(activity, outcome, minor, "TEST001")
    }
    private fun texts(view: View): List<String> {
        if (view.visibility != View.VISIBLE) return emptyList()
        return when (view) {
            is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            is TextView -> listOf(view.text.toString())
            else -> emptyList()
        }
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val folder = instrumentation.targetContext.getExternalFilesDir(null)!!
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun dateOfBirthUsesDirectControlsAndPreservesTheSelectedDate() {
        ActivityScenario.launch(ClientLookupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ClientLookupActivity::class.java.getDeclaredMethod("setDob", String::class.java)
                    .apply { isAccessible = true }.invoke(activity, "1995-12-29")
                ClientLookupActivity::class.java.getDeclaredMethod("pickDob")
                    .apply { isAccessible = true }.invoke(activity)
            }
            onView(isAssignableFrom(DatePicker::class.java)).perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = isAssignableFrom(DatePicker::class.java)
                override fun getDescription() = "Select a date of birth directly"
                override fun perform(uiController: UiController, view: View) {
                    val picker = view as DatePicker
                    assertEquals(1995, picker.year)
                    assertEquals(11, picker.month)
                    assertEquals(29, picker.dayOfMonth)
                    assertTrue(picker.spinnersShown)
                    assertFalse(picker.calendarViewShown)
                    assertTrue(picker.maxDate <= System.currentTimeMillis())
                    picker.updateDate(2000, 1, 29)
                    uiController.loopMainThreadUntilIdle()
                }
            })
            capture("lookup-date-picker.png")
            onView(withText(android.R.string.ok)).perform(click())
            scenario.onActivity { activity ->
                assertEquals("2000-02-29", activity.findViewById<TextView>(R.id.dob).text.toString())
            }
        }
    }

    @Test fun adultShowsSummaryWithoutHouseholdOrSource() {
        ActivityScenario.launch(ClientLookupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ClientLookupActivity::class.java.getDeclaredField("selectedDob").apply { isAccessible = true }
                    .set(activity, "1989-01-02")
                show(activity, ClientLookupOutcome.Dependents(adult, listOf(child), "Client Registry"))
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.dobWarning).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.childrenCard).visibility)
                val visible = texts(activity.findViewById(R.id.resultCard))
                assertTrue(visible.containsAll(listOf("Test Client", "Female", "0700000000", "TEST001", "1990-01-02")))
                assertFalse(visible.any { it.contains("HIDDEN") || it.contains("Registry") || it.contains("Dependant") })
                ClientLookupActivity::class.java.getDeclaredField("selectedDob").apply { isAccessible = true }
                    .set(activity, adult.dateOfBirth)
                show(activity, ClientLookupOutcome.Found(adult, "Client Registry"))
                assertEquals(View.GONE, activity.findViewById<View>(R.id.dobWarning).visibility)
            }
            capture("lookup-adult.png")
        }
    }
    @Test fun dependantsHaveLimitedVisibleDetailsAndRemainSelectable() {
        ActivityScenario.launch(ClientLookupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                show(activity, ClientLookupOutcome.Dependents(adult, listOf(child), "CR"), true)
                val list = activity.findViewById<ViewGroup>(R.id.childrenList)
                assertEquals(2, list.childCount)
                val visible = texts(list)
                assertTrue(visible.containsAll(listOf("Test Client", "Test Dependant", "Female", "Male", "2018-03-04")))
                assertFalse(visible.any { it.contains("HIDDEN") || it == "Show details" })
            }
            capture("lookup-dependants.png")
            scenario.onActivity { activity ->
                activity.findViewById<ViewGroup>(R.id.childrenList).getChildAt(1)
                    .findViewById<View>(R.id.personSelect).performClick()
                assertEquals("Test Dependant", activity.findViewById<TextView>(R.id.resultName).text.toString())
            }
        }
    }
    @Test fun guardianDobNeverPrefillsOrFlagsAChild() {
        ActivityScenario.launch(ClientLookupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                show(activity, ClientLookupOutcome.Dependents(adult, listOf(child), "Registry"), true)
                ClientLookupActivity::class.java.getDeclaredMethod("setDob", String::class.java)
                    .apply { isAccessible = true }.invoke(activity, adult.dateOfBirth)
                assertEquals("Parent / guardian date of birth",
                    activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.dobLayout).hint.toString())
                val prefillMethod = ClientLookupActivity::class.java.getDeclaredMethod("prefillFor", ClientDemographics::class.java)
                    .apply { isAccessible = true }
                val missingDob = prefillMethod.invoke(activity, child.copy(dateOfBirth = null)) as Map<*, *>
                assertFalse(missingDob.containsKey(com.icl.surveillance.fhir.forms.FormFields.Person.DATE_OF_BIRTH))
                val childPrefill = prefillMethod.invoke(activity, child) as Map<*, *>
                assertEquals(child.dateOfBirth, childPrefill[com.icl.surveillance.fhir.forms.FormFields.Person.DATE_OF_BIRTH])
                val mismatch = ClientLookupActivity::class.java.getDeclaredMethod("dobMismatch", ClientDemographics::class.java)
                    .apply { isAccessible = true }.invoke(activity, child)
                assertEquals(false, mismatch)
            }
        }
    }
    @Test fun missingValuesDoNotCreatePlaceholderDetails() {
        ActivityScenario.launch(ClientLookupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                show(activity, ClientLookupOutcome.Found(ClientDemographics(
                    recordDetails = listOf("Extra" to "HIDDEN VALUE")), "IPRS"))
                assertEquals(0, activity.findViewById<ViewGroup>(R.id.resultRows).childCount)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.resultName).visibility)
            }
        }
    }
    @Test fun errorsNeverExposeServiceOrConfigurationDetails() {
        ActivityScenario.launch(ClientLookupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                show(activity, ClientLookupOutcome.Failed("IPRS CR DHA token missing credentials"))
                assertEquals(activity.getString(R.string.client_lookup_failed),
                    activity.findViewById<TextView>(R.id.messageText).text.toString())
                show(activity, ClientLookupOutcome.NotFound)
                val message = activity.findViewById<TextView>(R.id.messageText).text.toString()
                assertFalse(message.contains("Registry") || message.contains("IPRS"))
                assertEquals("Search", activity.findViewById<TextView>(R.id.searchButton).text.toString())
                val adapter = activity.findViewById<android.widget.AutoCompleteTextView>(R.id.idType).adapter
                assertFalse((0 until adapter.count).any { adapter.getItem(it).toString().contains("Registry") })
            }
        }
    }
}
