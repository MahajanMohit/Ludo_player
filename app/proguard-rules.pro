# WebRTC — keep all JNI-bound classes
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Ktor / Netty
-keep class io.ktor.** { *; }
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-dontwarn io.ktor.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.app.remotesync.**$$serializer { *; }
-keepclassmembers class com.app.remotesync.** {
    *** Companion;
}
-keepclasseswithmembers class com.app.remotesync.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }

# Timber
-dontwarn org.jetbrains.annotations.**
