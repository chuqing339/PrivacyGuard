# Proguard rules for PrivacyGuard
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}
-keep class androidx.room.** { *; }
-keep class com.privacyguard.app.data.local.** { *; }
-keep class com.privacyguard.app.data.model.** { *; }

# libsu
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Miuix
-keep class top.yukonga.miuix.** { *; }
