package com.icl.surveillance.debug

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.work.ExistingWorkPolicy
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.get
import com.google.android.fhir.search.LOCAL_LAST_UPDATED_PARAM
import com.google.android.fhir.search.Order
import com.google.android.fhir.search.search
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.fhir.sync.Sync
import com.icl.surveillance.BuildConfig
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.fhir.AppFhirSyncWorker
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.CaseTypes
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.viewmodels.AddClientViewModel
import com.icl.surveillance.viewmodels.AddClientViewModel.PersistResult
import java.util.Calendar
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.StringType
import timber.log.Timber

/**
 * DEBUG ONLY. Generates synthetic submissions for the data-entry modules of the app.
 *
 * For each selected module it takes the most recent real (non-synthetic) QuestionnaireResponse
 * submitted on this device, clones it [Options.count] times, changes only the fields that make a
 * record identifiable (names, phone numbers, ID numbers, villages, narrative text, dates and —
 * optionally — counts / choices) and pushes every clone through the exact same
 * validation + extraction path a real submission uses
 * ([AddClientViewModel.persistPatientData] / [AddClientViewModel.persistUserResponse]).
 *
 * Records carry no marker and are indistinguishable from real submissions once synced. The IDs
 * of everything generated are kept only on the device, in [GENERATED_LOG_FILE] (app-private,
 * never synced), so they can be traced or cleaned up later and are never used as seeds.
 */
object SyntheticDataGenerator {

    /** App-private log: one line per generated record — module|patient|encounter|response IDs. */
    const val GENERATED_LOG_FILE = "generated_test_records.txt"

    /** Marker used by earlier builds; only checked so those records are never picked as seeds. */
    private const val LEGACY_TAG_SYSTEM = "http://nphiis.health.go.ke/fhir/CodeSystem/test-data"
    private const val LOG_TAG = "SyntheticData"

    enum class SaveMode { CASE, STANDALONE }

    data class Module(
        /** Value stored in the `currentCase` preference / Encounter.reasonCode. */
        val caseName: String,
        val label: String,
        val questionnaireFile: String,
        val saveMode: SaveMode = SaveMode.CASE,
        /** Diseases narrative text may mention for this module. */
        val diseases: List<String> = NarrativeWriter.DISEASES_GENERAL,
    )

    /** Every parent-level data entry module that goes through AddParentCaseActivity. */
    val modules = listOf(
        Module(CaseTypes.MEASLES, "Measles case", "add-case.json", diseases = listOf("measles")),
        Module(CaseTypes.AFP, "AFP case", "afp-case.json", diseases = listOf("polio")),
        Module(CaseTypes.VL, "VL case", "vl-case.json", diseases = listOf("kala-azar")),
        Module(CaseTypes.VHF, "VHF case", "vhf-case.json", diseases = listOf("Ebola", "Marburg")),
        Module(CaseTypes.MOH_505, "MOH 505 report", "moh505.json"),
        Module(CaseTypes.MPOX_REGISTER, "Mpox register", "mpox-register.json", diseases = listOf("mpox")),
        Module(CaseTypes.MPOX_TALLY_SHEET, "Mpox tally sheet", "mpox-tally-sheet.json", diseases = listOf("mpox")),
        Module(CaseTypes.MPOX_SUPERVISOR_CHECKLIST,
            "Mpox supervisor checklist",
            "mpox-supervisor-checklist.json",
            SaveMode.STANDALONE,
            diseases = listOf("mpox"),
        ),
        Module(CaseTypes.RUMOR,
            "Social listening & rumor tracking",
            "rumor-tracking-case.json"
        ),
        Module(CaseTypes.RCCE_COUNTY, "RCCE county/sub-county", "social-county.json"),
        Module(CaseTypes.RCCE_COMMUNITY, "RCCE community", "social-community.json"),
    )

    data class Options(
        val count: Int = 500,
        /** Also randomise counts and multiple-choice answers that no other question depends on. */
        val varyAnswers: Boolean = true,
        /**
         * Upload to the server after this many records and wait for it to finish (0 = never).
         * The app's bundle upload reads every pending change into memory at once, so letting
         * thousands of records queue up runs the device out of memory on the next sync.
         */
        val uploadEvery: Int = DEFAULT_UPLOAD_EVERY,
        /** Give each record a real county/sub-county/ward/facility, round-robin over all counties. */
        val spreadLocations: Boolean = true,
        /**
         * Copy the latest real submission of the module when there is one. Off by default:
         * records are built from the questionnaire alone, so no manual entry is needed.
         */
        val useLatestSubmission: Boolean = false,
    )

    const val DEFAULT_UPLOAD_EVERY = 25

    data class ModuleReport(
        val label: String,
        val seedFound: Boolean = true,
        val saved: Int = 0,
        val invalid: Int = 0,
        val failed: Int = 0,
        val counties: Int = 0,
        val note: String? = null,
    )

    data class State(
        val running: Boolean = false,
        val currentModule: String? = null,
        /** Extra status such as "Uploading…"; null while generating. */
        val phase: String? = null,
        val done: Int = 0,
        val total: Int = 0,
        val reports: List<ModuleReport> = emptyList(),
        val finished: Boolean = false,
        val cancelled: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Process-scoped so generation keeps going if the user leaves the Profile screen.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(context: Context, selected: List<Module>, options: Options): Boolean {
        check(BuildConfig.DEBUG) { "Synthetic data generation is only available in debug builds" }
        if (isRunning || selected.isEmpty()) return false
        val app = context.applicationContext as Application
        _state.value = State(running = true, total = selected.size * options.count)
        job = scope.launch {
            try {
                for (module in selected) {
                    if (!generateModule(app, module, options)) break
                }
                _state.update { it.copy(running = false, finished = true, currentModule = null) }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(running = false, finished = true, cancelled = true, currentModule = null)
                }
                throw e
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * The hidden "reporting site" answers the data-entry screen pre-fills for the logged-in user
     * (role + assigned locations), which decide which location questions the form shows.
     * Users without a recognised role are treated as national so every location is asked.
     */
    private fun reportingSitePrefill(app: Application): List<QuestionnaireResponse.QuestionnaireResponseItemComponent> {
        val formatter = com.icl.surveillance.utils.FormatterClass()
        fun pref(key: String) = formatter.getSharedPref(key, app).orEmpty()
        fun answer(linkId: String, value: String) =
            QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                this.linkId = linkId
                answerFirstRep.value = StringType(value)
            }
        val role = com.icl.surveillance.models.UserRole.fromAny(pref("practitionerRole"))
        val group = QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            linkId = FormFields.ReportingSite.GROUP
            text = "Reporting Site"
        }
        when (role?.scope) {
            com.icl.surveillance.models.LocationLevel.COUNTY -> {
                group.addItem(answer(FormFields.ReportingSite.USER_ROLE, "COUNTY_DISEASE_SURVEILLANCE_OFFICER"))
                group.addItem(answer(FormFields.ReportingSite.USER_COUNTY, pref("county")))
            }

            com.icl.surveillance.models.LocationLevel.SUB_COUNTY,
            com.icl.surveillance.models.LocationLevel.WARD -> {
                group.addItem(answer(FormFields.ReportingSite.USER_ROLE, "SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER"))
                group.addItem(answer(FormFields.ReportingSite.USER_COUNTY, pref("county")))
                group.addItem(answer(FormFields.ReportingSite.USER_SUB_COUNTY, pref("subCounty")))
            }

            com.icl.surveillance.models.LocationLevel.FACILITY -> {
                group.addItem(answer(FormFields.ReportingSite.USER_ROLE, "VACCINATOR"))
                group.addItem(answer(FormFields.ReportingSite.USER_FACILITY, pref("facility")))
                group.addItem(answer(FormFields.ReportingSite.USER_WARD, pref("ward")))
                group.addItem(answer(FormFields.ReportingSite.USER_SUB_COUNTY, pref("subCounty")))
                group.addItem(answer(FormFields.ReportingSite.USER_COUNTY, pref("county")))
            }

            else -> group.addItem(answer(FormFields.ReportingSite.USER_ROLE, "ADMINISTRATOR"))
        }
        return listOf(group)
    }

    private fun generatedLog(app: Application) = java.io.File(app.filesDir, GENERATED_LOG_FILE)

    private fun readGeneratedResponseIds(app: Application): Set<String> =
        runCatching {
            generatedLog(app).takeIf { it.exists() }?.readLines()
                ?.mapNotNull { it.split("|").getOrNull(3)?.takeIf { id -> id.isNotBlank() } }
                ?.toSet()
        }.getOrNull() ?: emptySet()

    private fun logGenerated(app: Application, module: Module, response: QuestionnaireResponse) {
        runCatching {
            val line = listOf(
                module.caseName,
                response.subject?.referenceElement?.idPart.orEmpty(),
                response.encounter?.referenceElement?.idPart.orEmpty(),
                response.idElement?.idPart ?: response.id.orEmpty(),
            ).joinToString("|")
            generatedLog(app).appendText(line + "\n")
        }.onFailure { Timber.tag(LOG_TAG).w(it, "Could not record generated IDs") }
    }

    /** @return false when the whole run should stop (an upload failed). */
    private suspend fun generateModule(app: Application, module: Module, options: Options): Boolean {
        _state.update { it.copy(currentModule = module.label) }
        val engine = FhirApplication.fhirEngine(app)
        val generatedIds = readGeneratedResponseIds(app)
        val seed = if (options.useLatestSubmission) findLatestSeed(engine, module, generatedIds) else null

        val questionnaire = FhirContext.forR4Cached().newJsonParser().parseResource(
            Questionnaire::class.java,
            app.assets.open(module.questionnaireFile).bufferedReader().use { it.readText() }
        )
        val plan = VariationPlan(questionnaire, module.diseases)
        // Building from the form needs real locations for the reporting-site questions.
        val picker = if (options.spreadLocations || seed == null) LocationPicker(engine) else null
        val countiesOnDevice = picker?.load() ?: 0
        val locations = picker?.takeIf { countiesOnDevice > 0 }
        if (seed == null && locations == null) {
            addReport(
                ModuleReport(
                    module.label,
                    note = "No county locations on this device. Sync locations first, then retry."
                )
            )
            _state.update { it.copy(done = it.done + options.count) }
            return true
        }
        val locationNote = if (picker != null && countiesOnDevice == 0) {
            Timber.tag(LOG_TAG).w("No county Locations (partOf Location/0) on device; keeping seed location")
            "No county locations on this device (sync locations first) — all records use the " +
                    "submitted record's facility."
        } else null
        val prefill = reportingSitePrefill(app)
        val countiesUsed = HashSet<String>()
        // A private, never-attached instance: only the suspend persist* functions are used.
        val viewModel = AddClientViewModel(
            app, SavedStateHandle(mapOf(QUESTIONNAIRE_FILE_PATH_KEY to module.questionnaireFile))
        )

        var report = ModuleReport(module.label, note = locationNote)
        var consecutiveInvalid = 0
        for (index in 1..options.count) {
            kotlin.coroutines.coroutineContext.ensureActive()
            var result = PersistResult.INVALID
            // Fall back to lighter variation if a varied value trips a form constraint.
            val levels: List<Level?> = when {
                seed == null -> listOf(null, null, null) // fresh builds, up to 3 tries
                options.varyAnswers -> Level.entries
                else -> listOf(Level.IDENTITY, Level.TEXT_ONLY)
            }
            val random = Random(System.nanoTime())
            val chain = locations?.pick(index, random)
            for (level in levels) {
                val clone = if (seed == null || level == null) {
                    plan.build(random, chain, prefill)
                } else {
                    plan.vary(seed, index, level, random, chain)
                }
                result = when (module.saveMode) {
                    SaveMode.CASE -> viewModel.persistPatientData(
                        clone, app, caseOverride = module.caseName
                    )

                    SaveMode.STANDALONE -> viewModel.persistUserResponse(
                        clone, module.caseName, app
                    )
                }
                if (result == PersistResult.SAVED) logGenerated(app, module, clone)
                if (result != PersistResult.INVALID) break
            }
            if (result == PersistResult.SAVED && chain != null && countiesUsed.add(chain.county.id)) {
                report = report.copy(counties = countiesUsed.size)
            }
            report = when (result) {
                PersistResult.SAVED -> report.copy(saved = report.saved + 1)
                PersistResult.INVALID -> report.copy(invalid = report.invalid + 1)
                PersistResult.FAILED -> report.copy(failed = report.failed + 1)
            }
            consecutiveInvalid = if (result == PersistResult.INVALID) consecutiveInvalid + 1 else 0
            _state.update { it.copy(done = it.done + 1) }
            val uploadDue = options.uploadEvery > 0 && result == PersistResult.SAVED &&
                    (report.saved % options.uploadEvery == 0)
            if (uploadDue && !uploadPending(app)) {
                report = report.copy(
                    note = "Upload failed after ${report.saved} records (offline or server error). " +
                            "Stopped so pending changes don't pile up; sync, then run again."
                )
                addReport(report)
                _state.update { it.copy(done = it.total) }
                return false
            }
            if (consecutiveInvalid >= 3 && report.saved == 0) {
                report = report.copy(
                    note = if (seed == null) {
                        "Generated records did not pass the form's validation; see logcat " +
                                "(tag $LOG_TAG) for the failing questions."
                    } else {
                        "The latest submitted record no longer passes validation " +
                                "(form changed?). Submit a fresh one and retry."
                    }
                )
                _state.update { it.copy(done = it.done + (options.count - index)) }
                break
            }
        }
        // Upload the remainder of this module before moving on.
        if (options.uploadEvery > 0 && report.saved % options.uploadEvery != 0 &&
            !uploadPending(app)
        ) {
            addReport(
                report.copy(
                    note = "Generated, but the final upload failed. Sync manually before running again."
                )
            )
            return false
        }
        addReport(report)
        return true
    }

    /**
     * Runs a one-time sync and suspends until it finishes.
     * @return true when the sync succeeded.
     */
    private suspend fun uploadPending(app: Application): Boolean {
        _state.update { it.copy(phase = "Uploading to server…") }
        try {
            var started = false
            val outcome = withTimeoutOrNull(UPLOAD_TIMEOUT_MS) {
                Sync.oneTimeSync<AppFhirSyncWorker>(
                    app, existingWorkPolicy = ExistingWorkPolicy.KEEP
                ).first { status ->
                    when (status) {
                        is CurrentSyncJobStatus.Succeeded,
                        is CurrentSyncJobStatus.Failed,
                        CurrentSyncJobStatus.Cancelled -> started // ignore a previous run's result

                        else -> {
                            started = true
                            false
                        }
                    }
                }
            }
            return outcome is CurrentSyncJobStatus.Succeeded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(LOG_TAG).e(e, "Upload during synthetic data generation failed")
            return false
        } finally {
            _state.update { it.copy(phase = null) }
        }
    }

    private const val UPLOAD_TIMEOUT_MS = 10 * 60 * 1000L

    private fun addReport(report: ModuleReport) {
        _state.update { it.copy(reports = it.reports + report) }
    }

    // ---------------------------------------------------------------- seed lookup

    private fun QuestionnaireResponse.isGenerated(generatedIds: Set<String>) =
        (idElement?.idPart ?: "") in generatedIds || meta.tag.any { it.system == LEGACY_TAG_SYSTEM }

    /** Newest locally saved (then synced) response for [module] that was not generated. */
    private suspend fun findLatestSeed(
        engine: FhirEngine,
        module: Module,
        generatedIds: Set<String>,
    ): QuestionnaireResponse? {
        val pageSize = 100
        var offset = 0
        while (offset < 20_000) {
            val page = engine.search<QuestionnaireResponse> {
                sort(LOCAL_LAST_UPDATED_PARAM, Order.DESCENDING)
                count = pageSize
                from = offset
            }
            if (page.isEmpty()) return null
            for (result in page) {
                val qr = result.resource
                if (qr.isGenerated(generatedIds) || qr.item.isEmpty()) continue
                if (belongsTo(engine, qr, module)) return qr
            }
            offset += pageSize
        }
        return null
    }

    private suspend fun belongsTo(
        engine: FhirEngine,
        qr: QuestionnaireResponse,
        module: Module
    ): Boolean {
        if (module.saveMode == SaveMode.STANDALONE) {
            return qr.extension.any { it.url == "supervisor_checklist" }
        }
        qr.encounter?.referenceElement?.idPart?.let { encounterId ->
            val encounter = runCatching { engine.get<Encounter>(encounterId) }.getOrNull()
            if (encounter != null) {
                return encounter.reasonCode.any { cc -> cc.coding.any { it.code == module.caseName } }
            }
        }
        // Fallback: the patient's identifier system is the case slug.
        val patientId = qr.subject?.referenceElement?.idPart ?: return false
        val patient = runCatching { engine.get<Patient>(patientId) }.getOrNull() ?: return false
        val slug = module.caseName.trim().lowercase()
            .replace("[^a-z0-9\\s-]".toRegex(), "")
            .replace("\\s+".toRegex(), "-")
            .replace("-+".toRegex(), "-")
        return patient.identifier.any { it.system == slug }
    }

    // ---------------------------------------------------------------- variation

    enum class Level {
        /** Identity fields + dates + locations + choices/counts (follow-up questions rebuilt). */
        FULL,
        /** Identity fields + dates + locations. */
        IDENTITY,
        /** Identity fields + locations (dates untouched). */
        TEXT_ONLY,
    }

    private enum class Kind { FIRST_NAME, SURNAME, FULL_NAME, PHONE, ID_NUMBER, PLACE, NARRATIVE, KEEP }

    private class VariationPlan(
        private val questionnaire: Questionnaire,
        private val diseases: List<String>,
    ) {
        /** Shared by all fields of the record being built, so its narrative stays consistent. */
        private var record: NarrativeWriter.Record =
            NarrativeWriter.newRecord(Random.Default, diseases, PLACES)

        /** "Today" of the record being built (real today shifted back by whole weeks). */
        private var baseDate: java.util.Date = java.util.Date()

        /** Date of birth chosen for the record being built (doses fall between it and onset). */
        private var dob: java.util.Date? = null

        /** Location chain of the record being built (facility-name questions reuse it). */
        private var chainNow: LocationPicker.Chain? = null

        /** "value <= / >= other question" rules from questionnaire-constraint extensions. */
        private data class Rule(val linkId: String, val op: String, val other: String)

        private val rules = ArrayList<Rule>()

        /** Oldest patient age the form allows (Age in Years max), capped for realism. */
        private var maxAgeYears = 80

        private val items = HashMap<String, Questionnaire.QuestionnaireItemComponent>()
        private val expressionText: String

        init {
            val expressions = StringBuilder()
            fun collectExpressions(exts: List<org.hl7.fhir.r4.model.Extension>) {
                exts.forEach { ext ->
                    when (val v = ext.value) {
                        is Expression -> expressions.append(v.expression).append('\n')
                        is StringType -> expressions.append(v.value).append('\n')
                    }
                    collectExpressions(ext.extension)
                }
            }
            collectExpressions(questionnaire.extension)
            fun walk(list: List<Questionnaire.QuestionnaireItemComponent>) {
                list.forEach { item ->
                    items[item.linkId] = item
                    collectExpressions(item.extension)
                    walk(item.item)
                }
            }
            walk(questionnaire.item)
            expressionText = expressions.toString()

            val rulePattern = Regex(
                "%context\\.answer\\.value\\s*(<=|>=|<|>)\\s*%resource\\.descendants\\(\\)" +
                        "\\.where\\(linkId='([^']+)'\\)\\.answer\\.value"
            )
            items.values.forEach { item ->
                item.extension.filter { it.url.endsWith("questionnaire-constraint") }.forEach { c ->
                    val expression = c.extension.firstOrNull { it.url == "expression" }
                        ?.value?.primitiveValue() ?: return@forEach
                    rulePattern.find(expression)?.let { m ->
                        rules.add(Rule(item.linkId, m.groupValues[1], m.groupValues[2]))
                    }
                }
                if ((item.text ?: "").lowercase().contains("age in years")) {
                    item.extension.firstOrNull { it.url.endsWith("maxValue") }
                        ?.let { (it.value as? IntegerType)?.value }
                        ?.let { maxAgeYears = minOf(it, 80) }
                }
            }
        }

        private fun isHidden(item: Questionnaire.QuestionnaireItemComponent) =
            item.readOnly || item.extension.any {
                it.url.endsWith("questionnaire-hidden") && (it.value as? BooleanType)?.booleanValue() == true
            }

        /** Used by a FHIRPath expression (calculation, constraint, answer filter, ...). */
        private fun isReferenced(linkId: String) =
            expressionText.contains("'$linkId'") || expressionText.contains("\"$linkId\"")

        private fun canChange(linkId: String, item: Questionnaire.QuestionnaireItemComponent) = !isHidden(item) && !isReferenced(linkId)

        private fun kindOf(linkId: String, item: Questionnaire.QuestionnaireItemComponent): Kind {
            if (linkId.startsWith("user_") || isHidden(item)) return Kind.KEEP
            val t = (item.text ?: "").lowercase()
            if (KEEP_PATTERN.containsMatchIn(t)) return Kind.KEEP
            val hasPhoneRegex = item.extension.any {
                it.url.endsWith("regex") && (it.value?.primitiveValue() ?: "").contains("\\d{10")
            }
            if (hasPhoneRegex || listOf("phone", "telephone", "mobile").any { t.contains(it) }) {
                return Kind.PHONE
            }
            val isPlace = listOf(
                "village", "residence", "landmark", "town", "estate", "street", "community health unit"
            ).any { t.contains(it) }
            if (t.contains("name")) {
                val notAPerson = listOf(
                    "facility", "center", "centre", "site", "station", "count", "infection", "media",
                    "antibiotic", "vaccine", "laboratory"
                ).any { t.contains(it) }
                return when {
                    isPlace -> Kind.PLACE
                    notAPerson -> Kind.KEEP
                    t.contains("first") || t.contains("middle") -> Kind.FIRST_NAME
                    t.contains("surname") || t.contains("family") || t.contains("last") -> Kind.SURNAME
                    else -> Kind.FULL_NAME
                }
            }
            if (isPlace) return Kind.PLACE
            if (listOf(
                    "ip/op", "ip/ op", "identification number", "batch number", "team no",
                    "patient number", "specimen id", "national id"
                ).any { t.contains(it) }
            ) return Kind.ID_NUMBER
            if (NarrativeWriter.topicOf(t) != null) return Kind.NARRATIVE
            return Kind.KEEP
        }

        fun vary(
            seed: QuestionnaireResponse,
            index: Int,
            level: Level,
            random: Random,
            chain: LocationPicker.Chain?,
        ): QuestionnaireResponse {
            val clone = QuestionnaireResponse()
            clone.status = seed.status
            seed.questionnaire?.let { clone.questionnaire = it }
            clone.meta = Meta()
            // Whole weeks keep weekly reporting dates (week-ending) on the same weekday,
            // and shifting every date by the same amount keeps from/to and onset/report order.
            val dayShift = if (level == Level.TEXT_ONLY) 0 else -7 * random.nextInt(0, 27)
            baseDate = shift(java.util.Date(), dayShift)
            record = NarrativeWriter.newRecord(random, diseases, PLACES)
            dob = null
            chainNow = chain
            clone.item = seed.item.mapTo(ArrayList()) { it.copy() }
            varyItems(clone.item, index, level, random, dayShift)
            if (chain != null) applyLocation(clone.item, chain)
            if (level == Level.FULL) {
                varyChoices(clone.item, random)
                // Answer follow-up questions the new choices opened, drop those they closed.
                val answers = HashMap<String, List<org.hl7.fhir.r4.model.Type>>()
                indexAnswers(clone.item, answers)
                clone.item = reconcile(questionnaire.item, clone.item, answers, random, chain)
            }
            enforceRules(clone.item, random)
            return clone
        }

        /**
         * Builds a complete record from the form definition alone (no earlier submission needed):
         * starts from the reporting-site details the data-entry screen pre-fills ([prefill]) and
         * answers every enabled question, then applies the form's date/count rules.
         */
        fun build(
            random: Random,
            chain: LocationPicker.Chain?,
            prefill: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
        ): QuestionnaireResponse {
            val response = QuestionnaireResponse()
            response.status = QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
            response.meta = Meta()
            // Event date: a random day within the last ~6 months.
            baseDate = shift(java.util.Date(), -(7 * random.nextInt(0, 27) + random.nextInt(0, 7)))
            record = NarrativeWriter.newRecord(random, diseases, PLACES)
            dob = null
            chainNow = chain
            val start = prefill.mapTo(ArrayList()) { it.copy() }
            val answers = HashMap<String, List<org.hl7.fhir.r4.model.Type>>()
            indexAnswers(start, answers)
            response.item = reconcile(questionnaire.item, start, answers, random, chain)
            enforceRules(response.item, random)
            return response
        }

        // ---------------------------------------------------------- form rules

        /** Repairs answers that break a "date/count must be <= or >= another answer" rule. */
        private fun enforceRules(
            list: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
            random: Random,
        ) {
            if (rules.isEmpty()) return
            val byLinkId = HashMap<String, QuestionnaireResponse.QuestionnaireResponseItemComponent>()
            fun index(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
                items.forEach { item ->
                    byLinkId.putIfAbsent(item.linkId, item)
                    item.answer.forEach { index(it.item) }
                    index(item.item)
                }
            }
            index(list)
            val today = java.util.Date()
            // Rules chain (e.g. dose 2 >= dose 1 >= ...), so a few passes settle them.
            repeat(4) {
                rules.forEach { rule ->
                    val item = byLinkId[rule.linkId]?.answer?.firstOrNull() ?: return@forEach
                    val other = byLinkId[rule.other]?.answer?.firstOrNull()?.value ?: return@forEach
                    val value = item.value
                    val wantsLater = rule.op.startsWith(">")
                    when {
                        value is DateType && other is DateType && value.value != null && other.value != null -> {
                            val ok = if (wantsLater) !value.value.before(other.value) else !value.value.after(other.value)
                            if (!ok) {
                                var fixed = shift(other.value, if (wantsLater) random.nextInt(0, 4) else -random.nextInt(0, 6))
                                if (fixed.after(today)) fixed = if (other.value.after(today)) today else other.value
                                item.value = DateType(fixed)
                            }
                        }

                        value is IntegerType && other is IntegerType && value.value != null && other.value != null -> {
                            val ok = if (wantsLater) value.value >= other.value else value.value <= other.value
                            if (!ok) {
                                item.value = IntegerType(
                                    if (wantsLater) other.value + random.nextInt(0, 6)
                                    else random.nextInt(0, other.value + 1)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------- identity fields

        private fun varyItems(
            list: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
            index: Int,
            level: Level,
            random: Random,
            dayShift: Int,
        ) {
            list.forEach { responseItem ->
                val def = items[responseItem.linkId]
                if (def != null) {
                    responseItem.answer.forEach { answer ->
                        varyAnswer(responseItem.linkId, def, answer, index, level, random, dayShift)
                    }
                }
                responseItem.answer.forEach { varyItems(it.item, index, level, random, dayShift) }
                varyItems(responseItem.item, index, level, random, dayShift)
            }
        }

        private fun varyAnswer(
            linkId: String,
            def: Questionnaire.QuestionnaireItemComponent,
            answer: QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent,
            index: Int,
            level: Level,
            random: Random,
            dayShift: Int,
        ) {
            when (val value = answer.value) {
                is StringType -> {
                    if (def.hasAnswerOption()) return // open-choice picked from a list
                    val original = value.value ?: return
                    val varied = textFor(linkId, def, random) ?: original
                    answer.value = StringType(varied.take(maxLength(def)))
                }

                is DateType -> if (dayShift != 0 && !isHidden(def) && value.value != null) {
                    answer.value = DateType(shift(value.value, dayShift), value.precision)
                }

                is DateTimeType -> if (dayShift != 0 && !isHidden(def) && value.value != null) {
                    answer.value = DateTimeType(shift(value.value, dayShift), value.precision, value.timeZone)
                }

                is IntegerType -> if (level == Level.FULL && canChange(linkId, def)) {
                    val original = value.value ?: return
                    if (!DURATION_PATTERN.containsMatchIn((def.text ?: "").lowercase()) && original >= 0) {
                        answer.value = IntegerType(clamp(def, random.nextInt(original / 2, original * 2 + 4)))
                    }
                }

                else -> Unit // choices are handled by varyChoices, locations by applyLocation
            }
        }

        /** New text for identity/narrative fields; null keeps the current value. */
        private fun textFor(linkId: String, def: Questionnaire.QuestionnaireItemComponent, random: Random): String? =
            when (kindOf(linkId, def)) {
                Kind.FIRST_NAME -> FIRST_NAMES.random(random)
                Kind.SURNAME -> SURNAMES.random(random)
                Kind.FULL_NAME -> if ((def.text ?: "").lowercase().contains("given")) {
                    "${FIRST_NAMES.random(random)} ${FIRST_NAMES.random(random)}"
                } else {
                    "${FIRST_NAMES.random(random)} ${SURNAMES.random(random)}"
                }
                Kind.PHONE -> "07" + digits(8, random)
                Kind.ID_NUMBER -> {
                    val t = (def.text ?: "").lowercase()
                    val year = Calendar.getInstance().get(Calendar.YEAR)
                    when {
                        t.contains("in-patient") -> "IP/$year/${digits(5, random)}"
                        t.contains("out-patient") -> "OP/$year/${digits(5, random)}"
                        t.contains("specimen id") -> "VHF-$year-${digits(5, random)}"
                        t.contains("ip") -> "OP/$year/${digits(5, random)}"
                        t.contains("batch") -> "FDP${digits(5, random)}"
                        t.contains("team") -> (1 + random.nextInt(30)).toString()
                        else -> (1 + random.nextInt(4)).toString() + digits(7, random)
                    }
                }

                Kind.PLACE -> {
                    val t = (def.text ?: "").lowercase()
                    // The record's home village is reused; landmarks/streets vary freely.
                    if (t.contains("village") || t.contains("residence") ||
                        t.contains("community health unit")
                    ) record.place else PLACES.random(random)
                }

                Kind.NARRATIVE -> NarrativeWriter.topicOf(def.text ?: "")
                    ?.let { NarrativeWriter.write(it, record) }

                Kind.KEEP -> null
            }

        // ---------------------------------------------------------- locations

        private fun applyLocation(list: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>, chain: LocationPicker.Chain) {
            list.forEach { item ->
                val reference = locationFor(item.linkId, chain)
                if (reference != null && item.answer.any { it.value is Reference }) {
                    item.answer = mutableListOf(answerOf(reference))
                }
                item.answer.forEach { applyLocation(it.item, chain) }
                applyLocation(item.item, chain)
            }
        }

        /** County / sub-county / ward / facility questions of the reporting site and residence. */
        private fun locationFor(linkId: String, chain: LocationPicker.Chain?): Reference? {
            chain ?: return null
            // Reporting site, patient residence and (VL) travel history all use the record's chain.
            val place = when (linkId) {
                in COUNTY_QUESTIONS -> chain.county
                in SUB_COUNTY_QUESTIONS -> chain.subCounty
                in WARD_QUESTIONS -> chain.ward
                in FormFields.ReportingSite.FACILITY_VARIANTS -> chain.facility
                else -> return null
            }
            return locationRef(place)
        }

        private fun locationRef(place: LocationPicker.Place) = Reference("Location/${place.id}").apply {
            display = place.name
        }

        // ---------------------------------------------------------- choices

        private fun codingOptions(def: Questionnaire.QuestionnaireItemComponent) = def.answerOption.mapNotNull { it.value as? Coding }

        private fun varyChoices(list: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>, random: Random) {
            list.forEach { item ->
                val def = items[item.linkId]
                if (def != null && canChange(item.linkId, def) && item.answer.isNotEmpty()) {
                    val options = codingOptions(def)
                    when {
                        item.answer.all { it.value is Coding } && options.size > 1 ->
                            item.answer = pickCodings(def, options, random).mapTo(ArrayList()) { answerOf(it) }

                        item.answer.size == 1 && item.answer[0].value is BooleanType ->
                            item.answer[0].value = BooleanType(random.nextBoolean())
                    }
                }
                item.answer.forEach { varyChoices(it.item, random) }
                varyChoices(item.item, random)
            }
        }

        private fun pickCodings(def: Questionnaire.QuestionnaireItemComponent, options: List<Coding>, random: Random): List<Coding> =
            if (def.repeats) {
                options.shuffled(random).take(1 + random.nextInt(minOf(3, options.size)))
            } else {
                listOf(options.random(random))
            }.map { it.copy() }

        // ---------------------------------------------------------- follow-up questions

        private fun indexAnswers(list: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>, into: MutableMap<String, List<org.hl7.fhir.r4.model.Type>>) {
            list.forEach { item ->
                if (item.answer.isNotEmpty()) into[item.linkId] = item.answer.mapNotNull { it.value }
                item.answer.forEach { indexAnswers(it.item, into) }
                indexAnswers(item.item, into)
            }
        }

        private fun forget(item: QuestionnaireResponse.QuestionnaireResponseItemComponent, answers: MutableMap<String, List<org.hl7.fhir.r4.model.Type>>) {
            answers.remove(item.linkId)
            item.answer.forEach { a -> a.item.forEach { forget(it, answers) } }
            item.item.forEach { forget(it, answers) }
        }

        /**
         * Rebuilds [current] in questionnaire order: items whose enableWhen is now false are
         * removed, newly enabled questions are answered, everything else is kept.
         */
        private fun reconcile(
            defs: List<Questionnaire.QuestionnaireItemComponent>,
            current: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
            answers: MutableMap<String, List<org.hl7.fhir.r4.model.Type>>,
            random: Random,
            chain: LocationPicker.Chain?,
        ): MutableList<QuestionnaireResponse.QuestionnaireResponseItemComponent> {
            val byLinkId = current.groupBy { it.linkId }
            val result = ArrayList<QuestionnaireResponse.QuestionnaireResponseItemComponent>()
            for (def in defs) {
                val existing = byLinkId[def.linkId]
                if (!isEnabled(def, answers)) {
                    existing?.forEach { forget(it, answers) }
                    continue
                }
                if (existing != null) {
                    existing.forEach { item ->
                        if (def.type == Questionnaire.QuestionnaireItemType.GROUP) {
                            item.item = reconcile(def.item, item.item, answers, random, chain)
                        } else {
                            if (item.answer.isEmpty() && !isHidden(def)) {
                                generateAnswers(def, random, chain, answers)?.let {
                                    item.answer = it
                                    answers[def.linkId] = it.mapNotNull { a -> a.value }
                                }
                            }
                            if (def.item.isNotEmpty()) {
                                item.answer.forEach { a ->
                                    a.item = reconcile(def.item, a.item, answers, random, chain)
                                }
                            }
                        }
                    }
                    result.addAll(existing)
                } else {
                    create(def, answers, random, chain)?.let { result.add(it) }
                }
            }
            return result
        }

        private fun create(
            def: Questionnaire.QuestionnaireItemComponent,
            answers: MutableMap<String, List<org.hl7.fhir.r4.model.Type>>,
            random: Random,
            chain: LocationPicker.Chain?,
        ): QuestionnaireResponse.QuestionnaireResponseItemComponent? {
            if (def.type == Questionnaire.QuestionnaireItemType.DISPLAY || isHidden(def)) return null
            val item = QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                linkId = def.linkId
                def.text?.let { text = it }
            }
            if (def.type == Questionnaire.QuestionnaireItemType.GROUP) {
                item.item = reconcile(def.item, emptyList(), answers, random, chain)
                return item.takeIf { it.item.isNotEmpty() }
            }
            // Fill every required question and most optional ones, like a real data clerk.
            if (!def.required && random.nextInt(100) >= OPTIONAL_FILL_PERCENT) return null
            val generated = generateAnswers(def, random, chain, answers) ?: return null
            item.answer = generated
            answers[def.linkId] = generated.mapNotNull { it.value }
            if (def.item.isNotEmpty()) {
                generated.forEach { it.item = reconcile(def.item, emptyList(), answers, random, chain) }
            }
            return item
        }

        private fun generateAnswers(
            def: Questionnaire.QuestionnaireItemComponent,
            random: Random,
            chain: LocationPicker.Chain?,
            answers: Map<String, List<org.hl7.fhir.r4.model.Type>> = emptyMap(),
        ): MutableList<QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent>? {
            val t = (def.text ?: "").lowercase()
            // The form's own default (e.g. Country = Kenya) is what a clerk would leave in place.
            if (def.hasInitial() && def.initialFirstRep.hasValue()) {
                return mutableListOf(answerOf(def.initialFirstRep.value.copy()))
            }
            val value: List<org.hl7.fhir.r4.model.Type> = when (def.type) {
                Questionnaire.QuestionnaireItemType.CHOICE,
                Questionnaire.QuestionnaireItemType.OPENCHOICE -> {
                    val codings = codingOptions(def)
                    val strings = def.answerOption.mapNotNull { it.value as? StringType }
                    when {
                        codings.isNotEmpty() ->
                            coherentChoice(def.linkId, codings, answers, random) ?: pickCodings(def, codings, random)
                        strings.isNotEmpty() -> listOf(strings.random(random).copy())
                        else -> return null
                    }
                }

                Questionnaire.QuestionnaireItemType.STRING,
                Questionnaire.QuestionnaireItemType.TEXT -> {
                    val context = (listOf(t) + def.enableWhen.mapNotNull { items[it.question]?.text?.lowercase() })
                        .joinToString(" ")
                    val text = textFor(def.linkId, def, random) ?: specifyText(def.linkId, context, random)
                    ?: if (def.required) "Not specified" else return null
                    listOf(StringType(text.take(maxLength(def))))
                }

                Questionnaire.QuestionnaireItemType.DATE -> listOf(DateType(newDate(t, random)))
                Questionnaire.QuestionnaireItemType.DATETIME -> listOf(DateTimeType(newDate(t, random)))
                Questionnaire.QuestionnaireItemType.INTEGER -> {
                    val v = when {
                        Regex("\\bage\\b").containsMatchIn(t) -> 1 + random.nextInt(60)
                        t.contains("dose") -> 1 + random.nextInt(3)
                        t.contains("day of follow") -> 1 + random.nextInt(21)
                        else -> random.nextInt(0, 21)
                    }
                    listOf(IntegerType(clamp(def, v)))
                }

                Questionnaire.QuestionnaireItemType.DECIMAL -> {
                    val v = when {
                        t.contains("temp") -> 36.5 + random.nextInt(0, 35) / 10.0
                        t.contains("weight") -> 5.0 + random.nextInt(0, 750) / 10.0
                        else -> 1.0 + random.nextInt(0, 990) / 10.0
                    }
                    listOf(org.hl7.fhir.r4.model.DecimalType(v))
                }

                Questionnaire.QuestionnaireItemType.BOOLEAN -> listOf(BooleanType(random.nextBoolean()))
                Questionnaire.QuestionnaireItemType.REFERENCE ->
                    listOf(locationFor(def.linkId, chain) ?: return null)

                else -> return null
            }
            return value.mapTo(ArrayList()) { answerOf(it) }
        }

        /** Plausible values for "specify" / reason / role style questions. */
        /** Values for free-text questions, most specific rules first. */
        private fun specifyText(linkId: String, t: String, random: Random): String? {
            if (t.startsWith("other")) otherSpecify(t, random)?.let { return it }
            caseFieldText(t, random)?.let { return it }
            return commonSpecifyText(linkId, t, random)
        }

        /** "Other (specify)" boxes, by what they follow (context includes the controlling question). */
        private fun otherSpecify(t: String, random: Random): String? = when {
            t.contains("other vhf") -> OTHER_VHF.random(random)
            t.contains("symptom") -> OTHER_SYMPTOMS.random(random)
            t.contains("place of death") -> OTHER_PLACES_OF_DEATH.random(random)
            t.contains("relationship") -> OTHER_RELATIONSHIPS.random(random)
            t.contains("species") -> OTHER_ANIMALS.random(random)
            t.contains("animal exposure") -> OTHER_ANIMAL_EXPOSURES.random(random)
            t.contains("ppe") -> OTHER_PPE.random(random)
            t.contains("type of sample") -> OTHER_SAMPLES.random(random)
            t.contains("tests performed") -> OTHER_LAB_TESTS.random(random)
            t.contains("location of investigation") || t.contains("location of contact") ->
                OTHER_SETTINGS.random(random)

            t.contains("exposure") || t.contains("contact") -> OTHER_CONTACT_TYPES.random(random)
            t.contains("provided the information") -> OTHER_INFORMANTS.random(random)
            t.contains("sex") -> "Intersex"
            else -> null
        }

        /** Case-form free text (VHF and similar): treatment, lab, contacts, travel. */
        private fun caseFieldText(t: String, random: Random): String? = when {
            t.contains("laboratory facility") -> LABORATORIES.random(random)
            t.contains("receiving care") -> chainNow?.facility?.name
            t.contains("antibiotic") -> ANTIBIOTICS.random(random)
            t.contains("name of vaccine") -> EBOLA_VACCINES.random(random)
            t.contains("next of kin") || t.contains("respondent") ->
                "${FIRST_NAMES.random(random)} ${SURNAMES.random(random)}"

            t.contains("employed as") -> OCCUPATIONS.random(random)
            t.contains("bleeding") -> BLEEDING_SITES.random(random)
            t.contains("treatment given") -> TREATMENTS.random(random)
            t.contains("narration") -> LAB_NARRATIONS.random(random)
            t.contains("stopover") -> PLACES.shuffled(random).take(2).joinToString(", ")
            t.contains("location of exposure") -> PLACES.random(random)
            else -> null
        }

        /**
         * Keeps VHF answers consistent with each other (outcome vs status, lab results vs final
         * classification) and gives case types a realistic mix. Null = no rule, pick at random.
         */
        private fun coherentChoice(
            linkId: String,
            options: List<Coding>,
            answers: Map<String, List<org.hl7.fhir.r4.model.Type>>,
            random: Random,
        ): List<Coding>? {
            fun answered(id: String) = (answers[id]?.firstOrNull() as? Coding)?.code
            fun pick(code: String) = options.firstOrNull { it.code == code }?.let { listOf(it.copy()) }
            fun weighted(vararg weights: Pair<String, Int>): List<Coding>? {
                var roll = random.nextInt(weights.sumOf { it.second })
                val code = weights.first { (_, weight) -> (roll - weight).also { roll = it } < 0 }.first
                return pick(code)
            }
            val vhf = FormFields.Vhf
            val caseType = answered(vhf.CASE_TYPE)
            val preliminary = answered(vhf.PRELIMINARY_RESULT)
            return when (linkId) {
                vhf.CASE_TYPE -> weighted("suspected" to 40, "contact" to 30, "probable" to 15, "confirmed" to 15)
                vhf.OUTCOME -> weighted("alive" to 80, "dead" to 20)
                vhf.CASE_STATUS ->
                    if (answered(vhf.OUTCOME) == "dead") pick("dead")
                    else weighted("active" to 60, "recovered" to 40)

                vhf.SAMPLES_COLLECTED -> weighted("yes" to 75, "no" to 25)
                vhf.PRELIMINARY_RESULT ->
                    if (caseType == "confirmed") pick("positive")
                    else weighted("positive" to 25, "negative" to 55, "pending" to 20)

                vhf.FINAL_RESULT -> when (preliminary) {
                    "positive" -> pick("positive")
                    "negative" -> pick("negative")
                    else -> weighted("positive" to 30, "negative" to 70)
                }


                else -> null
            }
        }

        private fun commonSpecifyText(linkId: String, t: String, random: Random): String? = when {
            linkId.startsWith("others-specify") -> OTHER_DISEASES.random(random)
            t.contains("health facility") || t.contains("vaccination center") ||
                    t.contains("vaccination site") || t.contains("site name") ->
                chainNow?.facility?.name ?: PLACES.random(random) + " Health Centre"

            t.contains("signature") -> "${FIRST_NAMES.random(random)} ${SURNAMES.random(random)}"
            t.contains("country") -> COUNTRIES.random(random)
            t.trim() == "location" || t.startsWith("location ") -> PLACES.random(random)
            t.contains("concomitant") -> CO_INFECTIONS.random(random)
            t.contains("education") -> EDUCATION_OTHER.random(random)
            t.contains("specimen") -> SPECIMENS.random(random)
            t.contains("paralysis") -> PARALYSIS_SITES.random(random)
            t.contains("rash") -> RASH_TYPES.random(random)
            t.contains("subcommittee") -> SUBCOMMITTEE_MEMBERS.random(random)
            t.contains("government sector") -> GOVERNMENT_SECTORS.random(random)
            t.contains("private sector") -> PRIVATE_SECTOR.random(random)
            t.contains("meeting frequency") -> MEETING_FREQUENCIES.random(random)
            t.contains("minutes") -> "Minutes of the last meeting were shared and verified."
            t.contains("contingency") ->
                "Plan developed and approved by the County Health Management Team."

            t.contains("other activity") -> RCCE_ACTIVITIES.random(random)
            t.contains("social listen") -> LISTENING_METHODS.random(random)
            t.contains("indicate the number") || t.contains("hotline") -> "0800" + digits(6, random)
            t.contains("achievement") -> ACHIEVEMENTS.random(random)
            t.contains("what is it") -> record.disease.replaceFirstChar { it.uppercase() }
            t.contains("signs and symptoms") -> SYMPTOMS.random(random)
            t.contains("transmit") -> TRANSMISSION.random(random)
            t.contains("preventive") -> PREVENTION.random(random)
            t.contains("radio") -> RADIO_STATIONS.random(random)
            t.contains("tv") -> TV_STATIONS.random(random)
            t.contains("social media") -> SOCIAL_MEDIA.random(random)
            t.contains("mass media") || t.contains("print") -> PRINT_MEDIA.random(random)
            t.contains("cadre") || t.contains("occupation") -> CADRES.random(random)
            t.contains("agency") || t.contains("partner") -> AGENCIES.random(random)
            t.contains("diagnosis") -> record.disease.replaceFirstChar { it.uppercase() }
            t.contains("role") || t.contains("function") -> ROLES.random(random)
            t.contains("if no") -> DISTRUST_REASONS.random(random)
            t.contains("why") || t.contains("reason") -> TRUST_REASONS.random(random)
            t.contains("religion") -> listOf("Hindu", "Bahá'í", "Traditional African religion").random(random)
            t.contains("specify") -> OTHER_GENERIC.random(random)
            else -> null
        }

        /**
         * Dates built around the record's event date: birth years before, vaccine doses between
         * birth and onset, onset a few days before, specimens/results just after, week-ending on a
         * Sunday. Form rules are enforced afterwards by [enforceRules].
         */
        private fun newDate(t: String, random: Random): java.util.Date {
            val today = java.util.Date()
            val event = baseDate
            val isDose = Regex("\\b(dose|doses|opv|ipv|vaccination|vaccinated|sia)\\b").containsMatchIn(t)
            val date = when {
                t.contains("birth") && isDose -> shift(dob ?: shift(event, -365), random.nextInt(0, 3))
                t.contains("birth") -> {
                    val days = random.nextInt(180, maxOf(181, maxAgeYears * 365))
                    shift(event, -days).also { dob = it }
                }

                isDose -> {
                    val start = shift(dob ?: shift(event, -900), 42)
                    val span = ((event.time - start.time) / DAY_MS).toInt() - 7
                    if (span > 0) shift(start, random.nextInt(0, span)) else shift(event, -14)
                }

                Regex("\\b(contact|exposure|departure|arrival)\\b").containsMatchIn(t) ->
                    shift(event, -random.nextInt(5, 22)) // within the 21-day incubation window

                Regex("\\b(onset|symptoms?|rash|paralysis)\\b").containsMatchIn(t) ->
                    shift(event, -random.nextInt(1, 7))

                t.contains("week ending") -> {
                    val c = Calendar.getInstance().apply { time = event }
                    while (c.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) c.add(Calendar.DAY_OF_YEAR, -1)
                    c.time
                }

                Regex("\\b(specimen|sent|result|received|to|end|discharge|outcome)\\b").containsMatchIn(t) ->
                    shift(event, random.nextInt(0, 4))

                else -> event
            }
            return if (date.after(today)) today else date
        }

        // ---------------------------------------------------------- enableWhen

        private fun isEnabled(def: Questionnaire.QuestionnaireItemComponent, answers: Map<String, List<org.hl7.fhir.r4.model.Type>>): Boolean {
            if (def.enableWhen.isEmpty()) return true
            val results = def.enableWhen.map { ew -> evaluate(ew, answers[ew.question].orEmpty()) }
            return if (def.enableBehavior == Questionnaire.EnableWhenBehavior.ALL) {
                results.all { it }
            } else {
                results.any { it }
            }
        }

        private fun evaluate(
            ew: Questionnaire.QuestionnaireItemEnableWhenComponent,
            values: List<org.hl7.fhir.r4.model.Type>,
        ): Boolean {
            val expected = ew.answer
            return when (ew.operator) {
                Questionnaire.QuestionnaireItemOperator.EXISTS ->
                    (expected as? BooleanType)?.booleanValue() == values.isNotEmpty()

                Questionnaire.QuestionnaireItemOperator.EQUAL -> values.any { sameValue(it, expected) }
                Questionnaire.QuestionnaireItemOperator.NOT_EQUAL -> values.any { !sameValue(it, expected) }
                Questionnaire.QuestionnaireItemOperator.GREATER_THAN -> values.any { (order(it, expected) ?: 0) > 0 }
                Questionnaire.QuestionnaireItemOperator.GREATER_OR_EQUAL -> values.any { (order(it, expected) ?: -1) >= 0 }
                Questionnaire.QuestionnaireItemOperator.LESS_THAN -> values.any { (order(it, expected) ?: 0) < 0 }
                Questionnaire.QuestionnaireItemOperator.LESS_OR_EQUAL -> values.any { (order(it, expected) ?: 1) <= 0 }
                else -> false
            }
        }

        /** Same rules as the form library: codings by system+code, primitives by value. */
        private fun sameValue(a: org.hl7.fhir.r4.model.Type, b: org.hl7.fhir.r4.model.Type?): Boolean {
            b ?: return false
            if (a::class != b::class) return false
            if (a is Coding && b is Coding) return a.system == b.system && a.code == b.code
            if (a is Reference && b is Reference) return a.reference == b.reference
            return a.isPrimitive && a.primitiveValue() == b.primitiveValue()
        }

        private fun order(a: org.hl7.fhir.r4.model.Type, b: org.hl7.fhir.r4.model.Type?): Int? = when {
            b == null -> null
            a is IntegerType && b is IntegerType -> a.value.compareTo(b.value)
            a is org.hl7.fhir.r4.model.DecimalType && b is org.hl7.fhir.r4.model.DecimalType ->
                a.value.compareTo(b.value)

            a is DateType && b is DateType -> a.value.compareTo(b.value)
            a is DateTimeType && b is DateTimeType -> a.value.compareTo(b.value)
            else -> null
        }

        // ---------------------------------------------------------- helpers

        private fun answerOf(value: org.hl7.fhir.r4.model.Type) =
            QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply { this.value = value }

        private fun maxLength(def: Questionnaire.QuestionnaireItemComponent) = if (def.hasMaxLength()) def.maxLength else Int.MAX_VALUE

        private fun digits(n: Int, random: Random) = (1..n).joinToString("") { random.nextInt(10).toString() }

        private fun clamp(def: Questionnaire.QuestionnaireItemComponent, value: Int): Int {
            var v = value
            def.extension.forEach { ext ->
                val limit = (ext.value as? IntegerType)?.value ?: return@forEach
                if (ext.url.endsWith("minValue")) v = maxOf(v, limit)
                if (ext.url.endsWith("maxValue")) v = minOf(v, limit)
            }
            return v
        }

        private fun shift(date: java.util.Date, days: Int): java.util.Date =
            Calendar.getInstance().apply {
                time = date
                add(Calendar.DAY_OF_YEAR, days)
            }.time
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val OTHER_VHF = listOf(
        "Crimean-Congo haemorrhagic fever", "Rift Valley fever", "Yellow fever", "Lassa fever",
        "Dengue haemorrhagic fever"
    )
    private val OTHER_SYMPTOMS = listOf(
        "Abdominal pain", "Joint pain", "Sore throat", "Hiccups", "Red eyes", "Difficulty swallowing"
    )
    private val OTHER_PLACES_OF_DEATH = listOf("On the way to hospital", "Traditional healer's home", "Church")
    private val OTHER_RELATIONSHIPS = listOf("Neighbour", "Colleague", "Classmate", "Church member")
    private val OTHER_ANIMALS = listOf("Antelope", "Duiker", "Bush pig", "Porcupine")
    private val OTHER_ANIMAL_EXPOSURES = listOf("Hunting", "Skinning bushmeat", "Cleaning animal pens")
    private val OTHER_PPE = listOf("Face shield", "Gumboots", "Apron")
    private val OTHER_SAMPLES = listOf("Oral swab", "Semen", "Breast milk")
    private val OTHER_LAB_TESTS = listOf("Virus isolation", "IgM serology", "GeneXpert Ebola")
    private val OTHER_SETTINGS = listOf("School", "Market", "Place of worship", "Matatu stage")
    private val OTHER_CONTACT_TYPES = listOf(
        "Shared utensils", "Washed the patient's clothes", "Cared for the patient at home"
    )
    private val OTHER_INFORMANTS = listOf("Neighbour", "Community health promoter", "Village elder")
    private val LABORATORIES = listOf(
        "KEMRI VHF Laboratory, Nairobi", "National Public Health Laboratory", "KEMRI-CDC Kisumu"
    )
    private val ANTIBIOTICS = listOf("Ceftriaxone", "Amoxicillin", "Ciprofloxacin", "Metronidazole")
    private val EBOLA_VACCINES = listOf("Ervebo (rVSV-ZEBOV)", "Zabdeno / Mvabea")
    private val OCCUPATIONS = listOf(
        "Teacher", "Farmer", "Nurse", "Trader", "Driver", "Miner", "Hunter", "Butcher", "Clinical officer"
    )
    private val BLEEDING_SITES = listOf("Gums", "Nose", "Vomit", "Stool", "Injection sites", "Eyes")
    private val TREATMENTS = listOf(
        "Oral rehydration salts and paracetamol",
        "IV fluids, antiemetics and analgesics",
        "Supportive care in the isolation unit",
        "IV fluids, antimalarials pending results and oxygen"
    )
    private val LAB_NARRATIONS = listOf(
        "Sample received in good condition and tested by RT-PCR.",
        "Sample triple-packaged and transported under cold chain.",
        "Result shared with the county rapid response team.",
        "Repeat sample requested for confirmation."
    )

    private val COUNTRIES = listOf("Uganda", "Tanzania", "South Sudan", "Ethiopia", "Somalia", "Rwanda")
    private val CO_INFECTIONS = listOf("HIV", "Tuberculosis", "Malaria", "Pneumonia", "HIV and Tuberculosis")
    private val EDUCATION_OTHER = listOf("Adult literacy class", "Madrasa", "Vocational training", "Informal schooling")
    private val SPECIMENS = listOf("Throat swab", "Nasopharyngeal swab", "Oral fluid", "Lesion swab")
    private val PARALYSIS_SITES = listOf("Neck muscles", "Facial muscles", "Trunk", "Respiratory muscles")
    private val RASH_TYPES = listOf("Vesicular rash", "Petechial rash", "Urticarial rash", "Papular rash")
    private val OTHER_DISEASES = listOf("Brucellosis", "Leprosy", "Trachoma", "Scabies", "Leishmaniasis")
    private val OTHER_GENERIC = listOf(
        "Referred to the sub-county health promotion team",
        "Discussed during the community dialogue day",
        "Shared with the area chief for follow-up"
    )
    private val SUBCOMMITTEE_MEMBERS = listOf(
        "Ministry of Education", "Kenya Red Cross", "Inter-religious council", "County Information Office"
    )
    private val GOVERNMENT_SECTORS = listOf(
        "Agriculture and Livestock", "Education", "Interior and National Administration", "Water and Sanitation"
    )
    private val PRIVATE_SECTOR = listOf(
        "Local pharmacies", "Matatu owners association", "Hotel and hospitality association", "Telecom providers"
    )
    private val MEETING_FREQUENCIES = listOf("Bi-weekly", "Every two months", "During outbreaks only", "Quarterly")
    private val RCCE_ACTIVITIES = listOf(
        "School health talks", "Community radio talk shows", "Door-to-door sensitisation", "Market day campaigns"
    )
    private val LISTENING_METHODS = listOf(
        "Community feedback forms, radio call-ins and CHP reports",
        "Monitoring WhatsApp groups and holding barazas",
        "Suggestion boxes at health facilities and CHP household visits"
    )
    private val ACHIEVEMENTS = listOf(
        "Trained 120 CHPs on risk communication",
        "Reached 15,000 households with cholera prevention messages",
        "Established a functional county rumour log",
        "Held monthly radio sessions with health officials"
    )
    private val SYMPTOMS = listOf(
        "Fever, rash and body weakness", "Fever, headache and joint pains",
        "Diarrhoea, vomiting and dehydration", "Painful skin lesions and swollen glands"
    )
    private val TRANSMISSION = listOf(
        "Through close contact with an infected person",
        "Through contaminated food and water",
        "Through mosquito bites",
        "Through contact with sick or dead animals"
    )
    private val PREVENTION = listOf(
        "Washing hands with soap, safe water and early treatment",
        "Vaccination and avoiding contact with sick people",
        "Sleeping under treated nets and clearing stagnant water",
        "Avoiding meat from animals that died suddenly"
    )

    // VL-only location questions (residence ward, travel history).
    private const val VL_RESIDENCE_WARD = "754362784943"
    private const val VL_TRAVEL_COUNTY = "751649865991"
    private const val VL_TRAVEL_SUB_COUNTY = "751649866545"
    private const val VL_TRAVEL_WARD = "683913433621"

    /** Location questions answered from the record's location chain. */
    private val COUNTY_QUESTIONS = FormFields.ReportingSite.COUNTY_VARIANTS +
            listOf(FormFields.Residence.COUNTY, VL_TRAVEL_COUNTY)
    private val SUB_COUNTY_QUESTIONS = FormFields.ReportingSite.SUB_COUNTY_VARIANTS +
            listOf(FormFields.Residence.SUB_COUNTY, VL_TRAVEL_SUB_COUNTY)
    private val WARD_QUESTIONS = FormFields.ReportingSite.WARD_VARIANTS +
            listOf(FormFields.Residence.WARD, VL_RESIDENCE_WARD, VL_TRAVEL_WARD)

    /** Percentage of optional, newly shown questions that get an answer. */
    private const val OPTIONAL_FILL_PERCENT = 70

    private val RADIO_STATIONS = listOf(
        "Radio Citizen", "Radio Jambo", "Ramogi FM", "Inooro FM", "Kameme FM", "Mulembe FM",
        "Chamgei FM", "Egesa FM", "Musyi FM", "Radio Maisha", "Star FM", "Bahari FM", "Sulwe FM"
    )
    private val TV_STATIONS = listOf("Citizen TV", "NTV", "KTN Home", "KBC Channel 1", "Inooro TV", "TV47")
    private val SOCIAL_MEDIA = listOf("Telegram", "Instagram", "YouTube", "Snapchat", "X Spaces")
    private val PRINT_MEDIA = listOf("Daily Nation", "The Standard", "Taifa Leo", "The Star", "Local newsletter")
    private val CADRES = listOf(
        "Village elder", "Teacher", "Boda boda rider", "Market trader", "Youth leader",
        "Assistant chief", "Women group leader", "Pharmacist"
    )
    private val AGENCIES = listOf(
        "AMREF Health Africa", "World Vision", "UNICEF", "Médecins Sans Frontières",
        "County Department of Health", "Local CBO"
    )
    private val ROLES = listOf(
        "Sub-county Disease Surveillance Coordinator", "Community Health Assistant",
        "Public Health Officer", "Health Promotion Officer", "Clinical Officer", "Nursing Officer"
    )
    private val TRUST_REASONS = listOf(
        "The message came from a health worker I know.",
        "It was announced by the area chief.",
        "Many people in the village were saying the same thing.",
        "It was shared by a religious leader.",
        "It was on a radio station I listen to every day."
    )
    private val DISTRUST_REASONS = listOf(
        "The source was unknown and the message had no official reference.",
        "The health facility had given different information.",
        "The message was exaggerated and asked people to forward it.",
        "It contradicted what the CHP told us.",
        "No one in the village had seen such cases."
    )

    /** Word-boundary matches so e.g. "village" is not mistaken for "age". */
    private val KEEP_PATTERN =
        Regex("\\b(epid|age|days since|signature|role|diagnosis)\\b")
    private val DURATION_PATTERN = Regex("\\b(age|years?|months?|weeks?|days?)\\b")

    private val FIRST_NAMES = listOf(
        "Achieng", "Wanjiru", "Kipchoge", "Otieno", "Mwangi", "Njeri", "Atieno", "Kamau",
        "Chebet", "Wafula", "Akinyi", "Mutua", "Wambui", "Kiprono", "Nekesa", "Omondi",
        "Halima", "Abdi", "Fatuma", "Barasa", "Jepkosgei", "Muthoni", "Ochieng", "Zawadi",
        "Baraka", "Imani", "Kibet", "Nyambura", "Ali", "Amina", "Juma", "Mercy", "Brian",
        "Faith", "Kevin", "Grace", "Dennis", "Esther", "Collins", "Lilian"
    )

    private val SURNAMES = listOf(
        "Odhiambo", "Kariuki", "Mohamed", "Kiplagat", "Wekesa", "Mutiso", "Njoroge", "Onyango",
        "Cheruiyot", "Wanyama", "Hassan", "Gitau", "Rotich", "Owino", "Nyaga", "Kilonzo",
        "Ndungu", "Langat", "Ouma", "Musyoka", "Kosgei", "Wairimu", "Okoth", "Simiyu",
        "Kemboi", "Mbugua", "Adan", "Nduta", "Opiyo", "Chege"
    )

    private val PLACES = listOf(
        "Kalobeyei", "Kakuma", "Lodwar", "Ngong", "Kitengela", "Ruiru", "Githurai", "Kayole",
        "Mlolongo", "Kibera", "Mathare", "Kariobangi", "Bondo", "Ahero", "Kisumu Ndogo",
        "Mtwapa", "Likoni", "Kongowea", "Eldama Ravine", "Kapsabet", "Iten", "Nanyuki",
        "Isiolo Town", "Garbatulla", "Wajir Bor", "Elwak", "Hola", "Garsen", "Voi", "Taveta",
        "Kitui Central", "Mwingi", "Matuu", "Kangundo", "Murang'a", "Othaya", "Karatina",
        "Chuka", "Maua", "Kakamega Town"
    )
}
