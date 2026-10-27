package com.icl.surveillance.fhir

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.NetworkType
import com.google.android.fhir.DatabaseErrorStrategy
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.FhirEngineConfiguration
import com.google.android.fhir.FhirEngineProvider
import com.google.android.fhir.NetworkConfiguration
import com.google.android.fhir.ServerConfiguration
import com.google.android.fhir.datacapture.DataCaptureConfig
import com.google.android.fhir.datacapture.XFhirQueryResolver
import com.google.android.fhir.search.search // Import the local fhir
import com.google.android.fhir.sync.HttpAuthenticationMethod
import com.google.android.fhir.sync.PeriodicSyncConfiguration
import com.google.android.fhir.sync.RepeatInterval
import com.google.android.fhir.sync.Sync
import com.google.android.fhir.sync.remote.HttpLogger
import com.google.firebase.FirebaseApp
import com.icl.surveillance.BuildConfig
import com.icl.surveillance.utils.Constants.BASE_URL
import com.icl.surveillance.utils.Constants.TEST_TOKEN
import com.icl.surveillance.utils.ContribQuestionnaireItemViewHolderFactoryMatchersProviderFactory
import com.icl.surveillance.network.TokenRefresher
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit


class FhirApplication : Application(), DataCaptureConfig.Provider {

    // Only initiate the FhirEngine when used for the first time, not when the app is created.
    private val fhirEngine: FhirEngine by lazy { constructFhirEngine() }

    private var dataCaptureConfig: DataCaptureConfig? = null

    private val dataStore by lazy { DemoDataStore(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    override fun onCreate() {
        super.onCreate()
        instance = this
        hydrateAccessTokenCache()
        // Logs only in debug builds: in release every log line is built (often whole resources
        // or HTTP bodies) for nobody to read, costing memory and CPU on users' phones.
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        FhirEngineProvider.init(
            FhirEngineConfiguration(
                enableEncryptionIfSupported = false,
                DatabaseErrorStrategy.RECREATE_AT_OPEN,
                ServerConfiguration(
                    BASE_URL,
                    httpLogger =
                        HttpLogger(
                            HttpLogger.Configuration(
                                if (BuildConfig.DEBUG) HttpLogger.Level.BASIC else HttpLogger.Level.NONE,
                            ),
                        ) { line ->
                            Timber.tag("App-HttpLog").e(line)
                            Timber.tag("FHIR-HTTP").d(line)
                        },
                    networkConfiguration = NetworkConfiguration(
                        connectionTimeOut = 30,
                        readTimeOut = 60,
                        writeTimeOut = 60,
                        uploadWithGzip = false,
                    ),
                    authenticator = { HttpAuthenticationMethod.Bearer(retrieveStoredToken()) }
                ),
            ),
        )

        try {

            dataCaptureConfig =
                DataCaptureConfig().apply {
                    urlResolver = ReferenceUrlResolver(this@FhirApplication as Context)
                    questionnaireItemViewHolderFactoryMatchersProviderFactory =
                        ContribQuestionnaireItemViewHolderFactoryMatchersProviderFactory
                    xFhirQueryResolver = XFhirQueryResolver { it ->
                        fhirEngine.search(it).map { it.resource }
                    }
                }

        } catch (e: Exception) {
            Timber.e(e)
        }
        syncWhenBackOnline()
    }

    /**
     * When the device comes back online while logged in: sync, refreshing the token first if it
     * expired while offline. Only on a change from offline to online, not at start-up (the
     * launch screens sync then).
     */
    private fun syncWhenBackOnline() {
        appScope.launch {
            var wasOnline: Boolean? = null
            NetworkUtils.observeNetworkConnectivity(this@FhirApplication)
                .catch { Timber.tag(TokenRefresher.TAG).e(it, "Network observer stopped") }
                .collect { online ->
                    val reconnected = wasOnline == false && online
                    wasOnline = online
                    if (!reconnected) return@collect
                    if (FormatterClass().getSharedPref("isLoggedIn", this@FhirApplication) != "true") {
                        return@collect
                    }
                    // The sync refreshes the token first (AppFhirSyncWorker.doWork), and is retried
                    // with backoff if the new connection is not usable yet.
                    Timber.tag(TokenRefresher.TAG).d("Back online: syncing")
                    Sync.oneTimeSync<AppFhirSyncWorker>(this@FhirApplication)
                }
        }
    }


    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }

    fun retrieveStoredToken(): String {
        val token = currentAccessToken()
            ?: FormatterClass().getSharedPref("access_token", this@FhirApplication).orEmpty()
                .also { storedToken ->
                    if (storedToken.isNotBlank()) {
                        updateAccessToken(storedToken)
                    }
                }
        return if (token.isNotBlank()) token else if (BuildConfig.DEBUG) TEST_TOKEN else ""
    }

    private fun hydrateAccessTokenCache() {
        updateAccessToken(FormatterClass().getSharedPref("access_token", this))
    }

    /** Saves for a patient's records also stamp its Patient with the time (PatientDataLastUpdated). */
    private fun constructFhirEngine(): FhirEngine {
        return PatientTouchingFhirEngine(FhirEngineProvider.getInstance(this))
    }

    companion object {
        @Volatile
        private lateinit var instance: FhirApplication

        @Volatile
        private var accessTokenCache: String? = null

        fun appContext(): Context = instance.applicationContext

        fun currentAccessToken(): String? = accessTokenCache?.takeIf { it.isNotBlank() }

        fun updateAccessToken(token: String?) {
            accessTokenCache = token?.takeIf { it.isNotBlank() }
        }

        fun hasAccessToken(): Boolean = !currentAccessToken().isNullOrBlank()

        fun fhirEngine(context: Context) =
            (context.applicationContext as FhirApplication).fhirEngine

        fun dataStore(context: Context) = (context.applicationContext as FhirApplication).dataStore

        /** Background work that must finish even if the screen that started it closes. */
        fun applicationScope(): CoroutineScope = instance.appScope
    }

    override fun getDataCaptureConfig(): DataCaptureConfig =
        dataCaptureConfig ?: DataCaptureConfig()
}
