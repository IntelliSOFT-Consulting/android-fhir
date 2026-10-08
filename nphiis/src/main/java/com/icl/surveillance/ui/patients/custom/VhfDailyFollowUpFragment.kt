package com.icl.surveillance.ui.patients.custom

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.icl.surveillance.BuildConfig
import com.icl.surveillance.R
import com.icl.surveillance.databinding.FragmentVlLabBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.ContactFollowUpState
import com.icl.surveillance.fhir.forms.ContactFollowUpState.Companion.DISPLAY
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.VhfContactTracker
import com.icl.surveillance.fhir.forms.VhfDemoMode
import com.icl.surveillance.utils.FormatterClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Daily Follow Up" tab of a VHF contact: the 21-day window counted from the date of contact,
 * a day-by-day view of the follow-ups recorded, missed and due, and the next action — record
 * today's follow up, convert a symptomatic contact to a case, or close out after day 21.
 */
class VhfDailyFollowUpFragment : Fragment() {

    private var _binding: FragmentVlLabBinding? = null
    private val binding get() = _binding!!
    private val fhirEngine by lazy { FhirApplication.fhirEngine(requireContext()) }
    private val window = FormFields.Vhf.FOLLOW_UP_WINDOW_DAYS

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVlLabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun load() {
        val formatter = FormatterClass()
        val patientId = formatter.getSharedPref("patientIdParent", requireContext())
        val encounterId = formatter.getSharedPref("encounterId", requireContext())
        if (patientId == null || encounterId == null) {
            showMessage("Unable to load this contact's follow up. Please reopen the record.")
            return
        }
        VhfDemoMode.refresh(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                VhfContactTracker.load(fhirEngine, patientId, encounterId)
            }
            if (_binding == null) return@launch
            if (state == null) {
                showMessage("Unable to load this contact's follow up. Please reopen the record.")
            } else {
                render(state)
            }
        }
    }

    private fun showMessage(message: String) {
        binding.fab.visibility = View.GONE
        binding.getStartedButton.visibility = View.GONE
        binding.tvEmptyMessage.text = message
        binding.lnEmpty.visibility = View.VISIBLE
    }

    private fun render(state: ContactFollowUpState) {
        binding.lnEmpty.visibility = View.GONE
        val canRecord = state.isContact && !state.isClosed
        val demo = VhfDemoMode.enabled
        // Testing aid (debug builds and demo mode): add the next follow up in the schedule.
        binding.fab.apply {
            visibility = if ((BuildConfig.DEBUG || demo) && canRecord) View.VISIBLE else View.GONE
            setImageResource(R.drawable.ic_vhf_edit_calendar)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = "Record follow up"
            setOnClickListener { recordNext(state) }
        }
        val parent = binding.lnParent
        parent.removeAllViews()

        //if (demo && state.isContact) {
          //  parent.addView(
           //     VhfCards.action(
            //        parent, CardTone.WARNING, R.drawable.ic_vhf_info,
            //        "Demo mode", "Follow ups can be recorded ahead of their dates"
           //     )
         //   )
      //  }
        parent.addView(statusCard(parent, state))
        if (state.isContact && state.exposureDate == null) {
            parent.addView(
                VhfCards.action(
                    parent, CardTone.WARNING, R.drawable.ic_vhf_warning,
                    "Date of contact missing",
                    "Counting from the registration date, ${DISPLAY.format(state.dayZero)}.",
                    actionText = "Edit record",
                    onAction = { VhfContactActions.editRecord(requireContext()) }
                )
            )
        }

        if (state.isContact) {
            val missed = state.missedDays.size
            parent.addView(
                VhfCards.stats(
                    parent,
                    listOf(
                        Triple("Follow-up day", dayValue(state), CardTone.INFO),
                        Triple("Recorded", state.visits.size.toString(), CardTone.SUCCESS),
                        Triple("Missed", missed.toString(), if (missed > 0) CardTone.WARNING else CardTone.NEUTRAL),
                        Triple("Days left", daysLeft(state).toString(), CardTone.NEUTRAL),
                    )
                )
            )
        }

        val context = requireContext()
        parent.addView(SummaryRows.title(context, "Follow-up window"))
        parent.addView(
            SummaryRows.field(
                context, "Date of contact (day 0)",
                state.exposureDate?.let { DISPLAY.format(it) } ?: "Not captured"
            )
        )
        parent.addView(SummaryRows.field(context, "Last day of follow up (day $window)", DISPLAY.format(state.lastDay)))
        if (state.missedDays.isNotEmpty()) {
            parent.addView(
                SummaryRows.field(
                    context, "Missed days", "Day ${state.missedDays.joinToString(", ")}", R.color.vhf_warning
                )
            )
        }
        parent.addView(
            SummaryRows.field(
                context, "Monitoring status",
                state.monitoringStatus.ifBlank { FormFields.Vhf.STATUS_UNDER_FOLLOW_UP }
            )
        )

        parent.addView(VhfCards.sectionHeader(parent, "Follow-up schedule"))
        val next = state.nextDay
        for (day in 0..window) {
            val visits = state.visits.filter { it.day == day }
            if (day == 0 && visits.isEmpty()) continue
            val label = "Day $day · ${DISPLAY.format(state.dateOfDay(day))}"

            // Only the next day in the schedule, once its date has arrived, can be recorded.
            if (canRecord && visits.isEmpty() && day == next && state.nextDayOpen) {
                val caption = when {
                    day == state.daysSinceExposure -> "Due today"
                    day > state.daysSinceExposure -> "Next in the schedule (demo)"
                    else -> "Overdue · next in the schedule"
                }
                parent.addView(
                    VhfCards.scheduleAction(parent, label, caption, "Record") {
                        VhfContactActions.recordFollowUp(context, state, day)
                    }
                )
                continue
            }

            val (value, color) = when {
                visits.any { it.symptomatic } -> "Symptomatic" to R.color.vhf_alert
                visits.isNotEmpty() -> "No symptoms" to R.color.vhf_success
                state.isClosed || !state.isContact -> "Not recorded" to R.color.vhf_text_secondary
                day <= state.daysSinceExposure -> "Missed · locked" to R.color.vhf_warning
                else -> "Upcoming" to R.color.vhf_text_secondary
            }
            parent.addView(SummaryRows.field(context, label, value, color))
        }
    }

    /**
     * The one thing to do next, as a card: convert a symptomatic contact, close out after day 21,
     * or record today's follow up. Closed and non-contact records get an information card.
     */
    private fun statusCard(parent: ViewGroup, state: ContactFollowUpState): View {
        val context = requireContext()
        val symptomatic = state.firstSymptomaticVisit
        return when {
            !state.isContact -> VhfCards.action(
                parent, CardTone.NEUTRAL, R.drawable.ic_vhf_info,
                "Not a contact record",
                "Type of case: ${state.caseType.ifBlank { "not set" }}"
            )

            // Outcome recorded as "Became a suspected case" but the record is still a contact.
            state.monitoringStatus.equals(FormFields.Vhf.STATUS_BECAME_CASE, ignoreCase = true) ->
                VhfCards.action(
                    parent, CardTone.ALERT, R.drawable.ic_vhf_warning,
                    "Became a suspected case",
                    "Convert the record to a suspected case to record clinical details and collect samples.",
                    actionText = "Convert to case",
                    actionIcon = R.drawable.ic_vhf_swap,
                    onAction = { VhfContactActions.convertToCase(context, state) }
                )

            state.isClosed -> VhfCards.action(
                parent,
                if (state.monitoringStatus == FormFields.Vhf.STATUS_COMPLETED) CardTone.SUCCESS else CardTone.NEUTRAL,
                R.drawable.ic_vhf_assignment_done,
                "Follow up closed",
                state.monitoringStatus
            )

            symptomatic != null -> VhfCards.action(
                parent, CardTone.ALERT, R.drawable.ic_vhf_warning,
                "Symptomatic on ${symptomatic.day?.let { "day $it" } ?: "follow up"}",
                symptomatic.date?.let { DISPLAY.format(it) }.orEmpty(),
                actionText = "Convert to case",
                actionIcon = R.drawable.ic_vhf_swap,
                onAction = { VhfContactActions.convertToCase(context, state) }
            )

            state.windowComplete -> VhfCards.action(
                parent, CardTone.SUCCESS, R.drawable.ic_vhf_assignment_done,
                "21-day follow up complete",
                "Day $window · ${DISPLAY.format(state.lastDay)}",
                actionText = "Close out",
                actionIcon = R.drawable.ic_vhf_check_circle,
                onAction = { VhfContactActions.updateMonitoring(context, state) }
            )

            state.daysSinceExposure < 0 -> VhfCards.action(
                parent, CardTone.NEUTRAL, R.drawable.ic_vhf_event_available,
                "Follow up not started",
                "Day 0 · ${DISPLAY.format(state.dayZero)}"
            )

            state.nextDayOpen -> {
                // The next day in the schedule: due today, or overdue when earlier days are missing.
                val day = state.nextDay ?: state.currentDay
                val behind = state.daysSinceExposure - day
                VhfCards.action(
                    parent,
                    if (behind > 0) CardTone.WARNING else CardTone.INFO,
                    R.drawable.ic_vhf_event_available,
                    if (behind > 0) "Day $day follow up outstanding" else "Day $day follow up due",
                    "${DISPLAY.format(state.dateOfDay(day))} · ${state.visits.size} of $window days recorded" +
                        if (behind > 0) " · $behind ${if (behind == 1) "day" else "days"} behind" else "",
                    actionText = "Record day $day",
                    actionIcon = R.drawable.ic_vhf_edit_calendar,
                    onAction = { VhfContactActions.recordFollowUp(context, state, day) }
                )
            }

            else -> {
                val next = state.nextDay
                VhfCards.action(
                    parent, CardTone.SUCCESS, R.drawable.ic_vhf_check_circle,
                    "Follow up up to date",
                    if (next != null) "Next follow up: day $next, ${DISPLAY.format(state.dateOfDay(next))}."
                    else "All $window days recorded."
                )
            }
        }
    }

    /** Opens the next day in the schedule, or says when it opens if its date has not arrived. */
    private fun recordNext(state: ContactFollowUpState) {
        val next = state.nextDay
        when {
            next == null -> Toast.makeText(requireContext(), "All $window days are recorded.", Toast.LENGTH_SHORT).show()
            !state.nextDayOpen -> Toast.makeText(
                requireContext(),
                "Day $next opens on ${DISPLAY.format(state.dateOfDay(next))}.",
                Toast.LENGTH_SHORT
            ).show()

            else -> VhfContactActions.recordFollowUp(requireContext(), state, next)
        }
    }

    private fun dayValue(state: ContactFollowUpState): String =
        if (state.daysSinceExposure < 0) "–" else "${state.currentDay}/$window"

    private fun daysLeft(state: ContactFollowUpState): Int =
        (window - state.daysSinceExposure).coerceIn(0, window)
}
