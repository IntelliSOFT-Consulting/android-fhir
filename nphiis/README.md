# NPHIIS Developer Guide

## Project Background

NPHIIS stands for **National Public Health Intelligence Information System**.
In this repository, it lives in the `nphiis` Android application module and is
built on top of the Android FHIR SDK modules in the same workspace.

Kenya's surveillance system has largely relied on paper-based reporting, which
has contributed to delays in data collection and outbreak response. Although
digital tools have improved real-time reporting, many processes remain partially
manual, affecting both data quality and timeliness.

To address this, the Division of Disease Surveillance and Response (DDSR) is
digitizing surveillance tools and enabling interoperability between laboratory
and epidemiological systems for a more efficient and integrated public health
response.

Within that broader effort, the NPHIIS app supports public health surveillance
workflows such as:

- case registration and case follow-up
- structured questionnaire capture
- local FHIR resource storage and sync
- notifications, location capture, and supporting public health workflows

This is a multi-module repository, so contributors should open and build the
**repository root**, not the `nphiis` folder by itself.

## Current Capabilities

The NPHIIS application currently provides the following core capabilities.

### Forms & Tools Digitized

- Measles Case Investigation Form (CIF)
- AFP CIF and Contact Tracing Form
- Visceral Leishmaniasis (VL) Case Management Form
- MOH 505 and MOH 503
- Social Investigation Form, Rumor Tracking Tool, and Mpox tools

### Platform Capabilities

- Real-time disease surveillance with automated outbreak alerting
- Electronic case-based reporting for notifiable disease surveillance
  (previously MOH 502)
- A mobile data collection application built on the Android FHIR SDK for
  surveillance officers, with offline capability and synchronization
- FHIR-based interoperability for seamless data exchange with laboratory
  information systems such as KEMRI Lab and other health information systems
  such as KHIS 

  
## Repository Layout

The most important places to know when contributing to `nphiis` are:

- `nphiis/src/main/java/com/icl/surveillance/ui`: screens and user flows
- `nphiis/src/main/java/com/icl/surveillance/auth`: login, password, and lock flows
- `nphiis/src/main/java/com/icl/surveillance/fhir`: FHIR engine setup, sync, and resource handling
- `nphiis/src/main/java/com/icl/surveillance/network`: API and network integration
- `nphiis/src/main/java/com/icl/surveillance/viewmodels`: presentation and business logic
- `nphiis/src/main/res/layout`: XML layouts
- `nphiis/src/main/res/navigation`: navigation graphs
- `nphiis/src/main/assets`: questionnaire JSON, seed bundles, and other app assets
- `nphiis/src/test/java`: unit tests
- `nphiis/src/androidTest/java`: instrumentation tests
- `buildSrc/src/main/kotlin/Releases.kt`: app version information
- `nphiis/build.gradle.kts`: module build configuration

## Prerequisites

The repository's recommended setup is:

- Java 17
- Android Studio Koala `2024.1.1+`
- Node.js
- Android SDK installed in Android Studio
- an emulator or physical Android device running Android 8.0+ because `nphiis` has `minSdk = 26`

Why Node.js matters: this repository uses Prettier for XML formatting through
Spotless, so XML-related changes depend on a working Node.js install.

Relevant repository values today:

- Gradle wrapper: `8.13`
- `compileSdk = 36`
- `targetSdk = 35`
- `minSdk = 26`

## Clone the Repository

If you are contributing through GitHub, fork first if needed, then clone the
repository locally:

```bash
git clone https://github.com/IntelliSOFT-Consulting/android-fhir.git
cd android-fhir
git checkout -b feature/my-change
```

If your team uses a different remote, substitute the correct repository URL.

For repository-wide contribution policies, also review:

- [`../Contributing.md`](../Contributing.md)
- [`../docs/contrib/git.md`](../docs/contrib/git.md)

## Open in Android Studio

1. Launch Android Studio.
2. Choose **Open**.
3. Select the repository root folder, `android-fhir`.
4. Let Android Studio import the Gradle project.
5. When prompted for the Gradle JDK, choose **Java 17**.
6. Wait for indexing and Gradle sync to finish.

Important: open the root project, not `nphiis/` directly, because `nphiis`
depends on other modules in this workspace such as `:engine` and
`:datacapture`.

## Gradle Sync and First Build

Android Studio usually starts Gradle sync automatically after the project opens.
If it does not:

1. Click **File > Sync Project with Gradle Files**.
2. Wait for the sync to finish.
3. If `local.properties` is missing, let Android Studio create it after you
   configure your Android SDK path.

To verify the module builds from the command line, run:

```bash
./gradlew :nphiis:assembleDebug
```

If Gradle sync fails because of a JDK mismatch, check Android Studio under:

`Settings/Preferences > Build, Execution, Deployment > Build Tools > Gradle`

and confirm the Gradle JDK is set to Java 17.

## Running the App

To run `nphiis` locally:

1. Start an emulator or connect a physical device.
2. In Android Studio, select the `nphiis` app run configuration.
3. Click **Run**.
4. Let the debug build install on the device.

If you prefer to build from the terminal first:

```bash
./gradlew :nphiis:assembleDebug
```

The debug APK will be generated under:

`nphiis/build/outputs/apk/debug/`

## Day-to-Day Development Commands

Useful commands from the repository root:

```bash
./gradlew :nphiis:assembleDebug
./gradlew :nphiis:testDebugUnitTest
./gradlew :nphiis:connectedDebugAndroidTest
./gradlew :nphiis:lint
./gradlew :nphiis:spotlessCheck
./gradlew :nphiis:spotlessApply
```

Notes:

- `connectedDebugAndroidTest` requires a connected device or emulator.
- `spotlessApply` is the safest way to format Kotlin, Gradle, and XML changes.
- The module currently has very light test coverage, so manual verification is
  still important for UI and workflow changes.

## Contribution Areas

### UI and UX Contributions

Use these locations when working on screens, navigation, and layout updates:

- `nphiis/src/main/java/com/icl/surveillance/ui`
- `nphiis/src/main/java/com/icl/surveillance/auth`
- `nphiis/src/main/res/layout`
- `nphiis/src/main/res/navigation`
- `nphiis/src/main/res/values`

Recommended validation:

- run the app on a device or emulator
- test the updated screen manually
- run `./gradlew :nphiis:lint`
- run `./gradlew :nphiis:spotlessApply`

### Questionnaire and Form Contributions

Most form and surveillance content changes live in:

- `nphiis/src/main/assets/*.json`
- `nphiis/src/main/assets/bundles/*.json`
- `nphiis/*.csv`

This area includes questionnaire definitions, bundles, and related generated or
supporting header files. When you change a questionnaire structure, review any
related CSV exports and downstream code that reads those fields.

Recommended validation:

- launch the affected workflow in the app
- complete the questionnaire end to end
- verify save, edit, and sync behavior
- update any related docs if form behavior changes

### FHIR, Sync, and Data Contributions

Use these locations when working on FHIR resource logic, sync, or data flow:

- `nphiis/src/main/java/com/icl/surveillance/fhir`
- `nphiis/src/main/java/com/icl/surveillance/network`
- `nphiis/src/main/java/com/icl/surveillance/services`
- `nphiis/src/main/java/com/icl/surveillance/models`
- `nphiis/src/main/java/com/icl/surveillance/viewmodels`

Recommended validation:

- run the affected flows manually
- verify local save and retrieval behavior
- verify sync or upload behavior if your change touches server interaction
- add or update tests where practical

### Documentation Contributions

Use these locations for documentation updates:

- `nphiis/README.md`
- `../README.md`
- `../docs/`
- `../Contributing.md`

Documentation changes are valuable contributions, especially when setup,
release, or workflow behavior changes.

### Build, Dependency, and Release Contributions

Use these files when your change affects app configuration:

- `nphiis/build.gradle.kts`
- `buildSrc/src/main/kotlin/Sdk.kt`
- `buildSrc/src/main/kotlin/Releases.kt`

Use extra care here because these changes can affect every developer and every
build environment.

## Recommended Contribution Workflow

1. Pull the latest changes from your main branch.
2. Create a focused feature branch.
3. Make one logical change at a time.
4. Run formatting, lint, and relevant tests locally.
5. Do a quick manual smoke test in the app.
6. Open a draft PR if the work is still in progress.
7. Mark the PR ready for review only when checks pass and the change is easy to review.

The repository's Git guidance also recommends:

- a clean, reviewable branch before requesting review
- a single logical initial commit when the PR first becomes review-ready
- follow-up commits for review feedback instead of rewriting every round

See:

- [`../docs/contrib/git.md`](../docs/contrib/git.md)

If you are contributing through the current GitHub repository,
`IntelliSOFT-Consulting/android-fhir`, also review the PR guidance in:

- [`../Contributing.md`](../Contributing.md)

## Quality Checklist Before Opening a PR

Before sending a contribution for review, try to complete this checklist:

- code compiles with `./gradlew :nphiis:assembleDebug`
- formatting passes with `./gradlew :nphiis:spotlessApply`
- lint passes with `./gradlew :nphiis:lint`
- relevant tests pass
- changed screens are manually tested
- changed questionnaires are exercised end to end
- docs are updated if setup, behavior, or release steps changed

Useful test commands:

```bash
./gradlew :nphiis:testDebugUnitTest
./gradlew :nphiis:connectedDebugAndroidTest
```

For broader repository testing and style guidance, see:

- [`../docs/contrib/test.md`](../docs/contrib/test.md)
- [`../docs/contrib/style.md`](../docs/contrib/style.md)
- [`../docs/contrib/prereqs.md`](../docs/contrib/prereqs.md)

## Release and Deployment

Before generating a release, update the app release version in
`buildSrc/src/main/kotlin/Releases.kt` under `Releases.Surveillance`:

- increment `versionCode` for every new release build
- update `versionName` to the release label you want to ship

Example:

```kotlin
object Surveillance {
    const val applicationId = "com.icl.nphi"
    const val versionCode = 22
    const val versionName = "1.0"
}
```

### Generate the Release AAB

Run the bundle task from the repository root:

```bash
./gradlew :nphiis:bundleRelease
```

The generated Android App Bundle will be available at:

`nphiis/build/outputs/bundle/release/nphiis-release.aab`

### Generate the Release APK

Run the APK task from the repository root:

```bash
./gradlew :nphiis:assembleRelease
```

The generated APK will be available at:

`nphiis/build/outputs/apk/release/nphiis-release.apk`

### Signing Note

This repository does not commit a release signing configuration for `nphiis`.
If you need a signed artifact for Play Store upload or device distribution, use
your local keystore through Android Studio's
`Build > Generate Signed Bundle / APK` flow, or add your local signing config
before running the Gradle tasks.

## Troubleshooting

### Gradle Sync Fails

Check the following first:

- Android Studio is using Java 17 for Gradle
- the Android SDK path is configured correctly
- you opened the repository root instead of only `nphiis/`

### XML Formatting Looks Inconsistent

Install Node.js, then run:

```bash
./gradlew :nphiis:spotlessApply
```

### Build Works in the Terminal but Not in Android Studio

Try:

1. **File > Sync Project with Gradle Files**
2. **Invalidate Caches / Restart**
3. confirm the selected run configuration is the `nphiis` app module

## Additional References

- [`../Contributing.md`](../Contributing.md)
- [`../docs/contrib/prereqs.md`](../docs/contrib/prereqs.md)
- [`../docs/contrib/git.md`](../docs/contrib/git.md)
- [`../docs/contrib/style.md`](../docs/contrib/style.md)
- [`../docs/contrib/test.md`](../docs/contrib/test.md)
