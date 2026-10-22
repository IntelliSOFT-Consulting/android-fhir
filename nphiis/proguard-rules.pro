# Keep useful stack traces in release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve generic/annotation metadata used by Retrofit/Gson.
-keepattributes Signature,*Annotation*

# App models are deserialized reflectively.
-keep class com.icl.surveillance.models.** { *; }
-keepclassmembers class com.icl.surveillance.models.** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Retrofit HTTP annotations are read reflectively.
-keepclassmembers interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Prevent Html.TagHandler linkage issues in minified builds.
-keep class androidx.core.text.HtmlCompat { *; }
-keep class androidx.core.text.HtmlCompat$* { *; }
-keep class * implements android.text.Html$TagHandler { *; }
-keepclassmembers class * implements android.text.Html$TagHandler {
    public void handleTag(boolean, java.lang.String, android.text.Editable, org.xml.sax.XMLReader);
}

# HAPI FHIR + XML/StAX runtime stack used by sync/parsing.
-keep class ca.uhn.fhir.** { *; }
-keep class org.hl7.fhir.** { *; }
-keep class com.ctc.wstx.** { *; }
-keep class org.codehaus.stax2.** { *; }
-keep class javax.xml.stream.** { *; }

-dontwarn aQute.bnd.annotation.spi.ServiceProvider
-dontwarn com.ctc.wstx.**
-dontwarn org.codehaus.stax2.**

# No logging in release: R8 drops these calls (and the strings built for them) when
# isMinifyEnabled is on. Timber is also only planted in debug builds (FhirApplication).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
    public static boolean isLoggable(java.lang.String, int);
}
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** log(...);
    public static *** tag(...);
}
-assumenosideeffects class timber.log.Timber$Forest {
    public *** v(...);
    public *** d(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
    public *** wtf(...);
    public *** log(...);
    public *** tag(...);
}
-assumenosideeffects class timber.log.Timber$Tree {
    public *** v(...);
    public *** d(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
    public *** wtf(...);
    public *** log(...);
}
