# Keep Oboe/JNI native bridge entry points.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep our JNI-facing classes and their members (called by name from C++).
-keep class com.audiopro.djmrec.audio.** { *; }
-keep class com.audiopro.djmrec.usb.** { *; }
