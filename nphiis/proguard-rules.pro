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
