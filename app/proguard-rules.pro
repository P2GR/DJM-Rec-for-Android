# Keep Oboe/JNI native bridge entry points.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep our JNI-facing classes and their members (called by name from C++).
-keep class com.audiopro.djmrec.audio.** { *; }
-keep class com.audiopro.djmrec.usb.** { *; }

# Crashlytics 20.1 uses these API 37 classes only behind a runtime SDK check.
# Keep R8 builds compatible with our lower compile SDK until API 37 is installed.
-dontwarn android.os.ProfilingTrigger
-dontwarn android.os.ProfilingTrigger$Builder
