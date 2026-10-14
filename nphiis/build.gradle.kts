import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.navigation.safeargs)

    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

configureRuler()

/**
 * Endpoints and secrets come from the root local.properties (git-ignored), never from source.
 * The keys are listed in local.properties.example.
 */
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

/** BuildConfig field name -> (local.properties key, required). */
val nphiisConfig = linkedMapOf(
    "BASE_URL" to ("nphiis.baseUrl" to true),
    "BASE_AUTH_URL" to ("nphiis.authBaseUrl" to true),
    "ALERTS_BASE_URL" to ("nphiis.alertsBaseUrl" to true),
    "DEBUG_TOKEN" to ("nphiis.debugToken" to false),
    "IPRS_BASE_URL" to ("nphiis.iprs.baseUrl" to true),
    "IPRS_USERNAME" to ("nphiis.iprs.username" to false),
    "IPRS_PASSWORD" to ("nphiis.iprs.password" to false),
    "IPRS_BASIC_AUTH" to ("nphiis.iprs.basicAuth" to false),
    "DHA_BASE_URL" to ("nphiis.dha.baseUrl" to true),
    "DHA_CLIENT_ID" to ("nphiis.dha.clientId" to false),
    "DHA_CLIENT_SECRET" to ("nphiis.dha.clientSecret" to false),
    "CR_BASE_URL" to ("nphiis.cr.baseUrl" to true),
    // Google Maps + Places (residence picker). Optional: without it the map shows no tiles.
    "MAPS_API_KEY" to ("nphiis.mapsApiKey" to false),
)

val missingNphiisKeys = nphiisConfig.values
    .filter { (key, required) -> required && localProperties.getProperty(key).isNullOrBlank() }
    .map { it.first }
if (missingNphiisKeys.isNotEmpty()) {
    throw GradleException(
        "Missing in local.properties: ${missingNphiisKeys.joinToString()}. " +
            "Copy them from local.properties.example."
    )
}

/** A Java string literal for buildConfigField. */
fun quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.icl.surveillance"
    compileSdk = Sdk.COMPILE_SDK
    defaultConfig {
        applicationId = Releases.Surveillance.applicationId
        minSdk = Sdk.MIN_SDK
        targetSdk = Sdk.TARGET_SDK
        versionCode = Releases.Surveillance.versionCode
        versionName = Releases.Surveillance.versionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Endpoints and secrets from local.properties (see above).
        nphiisConfig.forEach { (field, keyAndRequired) ->
            buildConfigField("String", field, quoted(localProperties.getProperty(keyAndRequired.first)?.trim() ?: ""))
        }
        // Maps SDK reads its key from the manifest (com.google.android.geo.API_KEY).
        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("nphiis.mapsApiKey")?.trim() ?: ""
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    compileOptions {
        // Flag to enable support for the new language APIs
        // See https://developer.android.com/studio/write/java8-support
        isCoreLibraryDesugaringEnabled = true
    }

    packaging { resources.excludes.addAll(listOf("META-INF/ASL-2.0.txt", "META-INF/LGPL-3.0.txt")) }
    kotlin { jvmToolchain(11) }
}

dependencies {
//    implementation(libs.firebase.crashlytics)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.ext.junit)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.material)
    implementation(libs.timber)
    implementation(project(":datacapture")) {
        exclude(group = "com.google.android.fhir", module = "engine")
    }
    implementation(project(":engine"))

    testImplementation(libs.junit)

    implementation("com.airbnb.android:lottie:6.6.0")
    implementation(platform("com.google.firebase:firebase-bom:33.6.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")
    implementation("com.google.android.gms:play-services-location:21.2.0")
    // Residence picker map. Place search uses the Places API (New) over HTTP (PlacesSearch):
    // the Places SDK needs Guava 33, but :engine / :datacapture pin Guava 32.1.3.
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.dagger:hilt-android:2.51.1")

}
