package com.icl.surveillance.ui.patients

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.search.count
import com.google.android.fhir.search.search
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.hl7.fhir.r4.model.Patient
import timber.log.Timber

/**
 * In-memory index of the active patients' identifiers: patient id -> (system -> values).
 *
 * The engine cannot search an identifier by its system alone, so finding "every patient with a
 * vhf-case-information identifier" would read every patient on each list open. This index reads
 * them once, then:
 *  - [put] keeps it current for records saved on this device (call it after every Patient save);
 *  - a cheap count check on each use re-reads it in the background when patients were added or
 *    removed elsewhere (sync), while the current index is served immediately.
 */
object CaseIdentifierIndex {

    private const val TAG = "CASE_INDEX"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var index: Map<String, Map<String, List<String>>>? = null

    @Volatile
    private var indexedCount = -1L

    @Volatile
    private var refresh: Job? = null

    /**
     * Patients that carry an identifier in any of [systems], with those identifiers' values
     * (the case Encounter ids).
     */
    suspend fun patientsWith(engine: FhirEngine, systems: Set<String>): Map<String, List<String>> {
        val current = index ?: mutex.withLock { index ?: rebuild(engine) }
        refreshIfChanged(engine)
        return current.mapNotNull { (patientId, bySystem) ->
            val values = systems.flatMap { bySystem[it].orEmpty() }
            if (values.isEmpty()) null else patientId to values
        }.toMap()
    }

    /** Records a saved patient's identifiers, so lists see the change straight away. */
    fun put(patient: Patient) {
        val current = index ?: return // not built yet: the first read will include it
        index = current + (patient.logicalId to patient.identifiersBySystem())
        Timber.tag(TAG).d("put %s -> %s", patient.logicalId, patient.identifiersBySystem().keys)
    }

    /** Forgets everything, e.g. on sign-out or after a full re-sync. */
    fun reset() {
        index = null
        indexedCount = -1L
    }

    private suspend fun rebuild(engine: FhirEngine): Map<String, Map<String, List<String>>> {
        val started = System.currentTimeMillis()
        val built = engine.search<Patient> {
            filter(Patient.ACTIVE, { value = of(true) })
        }.map { it.resource }
            .filter { it.hasIdentifier() }
            .associate { it.logicalId to it.identifiersBySystem() }
        index = built
        indexedCount = activeCount(engine)
        Timber.tag(TAG).d(
            "built: %d patients with identifiers (of %d) in %d ms",
            built.size, indexedCount, System.currentTimeMillis() - started
        )
        return built
    }

    private suspend fun refreshIfChanged(engine: FhirEngine) {
        if (refresh?.isActive == true) return
        val count = activeCount(engine)
        if (count == indexedCount) return
        Timber.tag(TAG).d("patient count %d -> %d: refreshing in the background", indexedCount, count)
        refresh = scope.launch {
            runCatching { mutex.withLock { rebuild(engine) } }
                .onFailure { Timber.tag(TAG).e(it, "refresh failed") }
        }
    }

    private suspend fun activeCount(engine: FhirEngine): Long =
        engine.count<Patient> { filter(Patient.ACTIVE, { value = of(true) }) }

    private fun Patient.identifiersBySystem(): Map<String, List<String>> =
        identifier.filter { it.hasSystem() && it.hasValue() }
            .groupBy({ it.system }, { it.value })
}
