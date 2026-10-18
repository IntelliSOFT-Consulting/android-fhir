# NPHIIS release shrinking (R8). Google Play requires DEX code over 10 MB to be at least 25%
# optimized/shrunk (from Feb 2027); the unshrunk app shipped ~150 MB of DEX, most of it FHIR
# versions and libraries the app never calls. Test every release build (see README note).

# Keep useful stack traces in release (Crashlytics de-obfuscates with the uploaded mapping).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Generic/annotation metadata used by Retrofit, Gson and kotlinx.serialization.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# ---- App code: small (~3% of classes); kept whole because models, network responses and
# saved state are read with Gson / kotlinx.serialization by field name, and workers, fragments
# and activities are created by class name.
-keep class com.icl.surveillance.** { *; }

# ---- Android FHIR SDK (engine, sync, data capture): loads its own classes by name (sync
# status via Gson + Class.forName, R4 resources in ResourceMapper) and Retrofit services.
-keep class com.google.android.fhir.** { *; }

# ---- HAPI FHIR: R4 only. HAPI finds model classes by name and the FHIRPath engine reads
# fields by reflection, so the R4 structures are kept whole. DSTU2 / DSTU3 / R4B / R5 models
# and the version converters are never used and are removed (about a fifth of all classes).
-keep class ca.uhn.fhir.** { *; }
-keep class org.hl7.fhir.r4.** { *; }
-keep class org.hl7.fhir.instance.model.api.** { *; }
-keep class org.hl7.fhir.utilities.** { *; }
-keep class org.hl7.fhir.exceptions.** { *; }

# XML / JSON runtime used by HAPI parsers (service-loaded or reflective).
-keep class com.ctc.wstx.** { *; }
-keep class org.codehaus.stax2.** { *; }
-keep class javax.xml.stream.** { *; }
-keep class org.apache.xerces.** { *; }
-keep class com.fasterxml.jackson.** { *; }
# ICU loads its data and some classes by name.
-keep class com.ibm.icu.** { *; }
# Used by HapiWorkerContext (FHIRPath engine in the questionnaire screens).
-keep class com.github.benmanes.caffeine.cache.** { *; }

# Database encryption (engine option).
-keep,includedescriptorclasses class net.sqlcipher.** { *; }
-keep,includedescriptorclasses interface net.sqlcipher.** { *; }

# Gson (R8 full mode): generic types read through TypeToken subclasses.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Retrofit HTTP annotations are read reflectively.
-keepclassmembers interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Html.TagHandler linkage in minified builds.
-keep class androidx.core.text.HtmlCompat { *; }
-keep class androidx.core.text.HtmlCompat$* { *; }
-keep class * implements android.text.Html$TagHandler { *; }

# Release builds drop verbose/debug/info/warning log calls (errors are kept).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# HAPI / its optional dependencies reference classes not present on Android (server-side
# validators, logging back-ends, javax APIs). They are never reached at runtime.
-dontwarn **
-ignorewarnings
