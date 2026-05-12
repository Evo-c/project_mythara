# Mythara — Android proguard rules
#
# These rules ship alongside `proguard-android-optimize.txt`. Keep this
# file lean — only declare what the optimizer cannot infer.

# Retrofit + kotlinx.serialization keep rules
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# kotlinx.serialization — preserve @Serializable classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclasseswithmembers class **.*$$serializer {
    static **$$serializer INSTANCE;
}
-keepclassmembers class com.mythara.minimax.** {
    *** Companion;
}
-keepclasseswithmembers class com.mythara.minimax.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room — DAO / database classes
-keep class androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class * { *; }

# OkHttp logging — only stripped in release; keep platform classes
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
