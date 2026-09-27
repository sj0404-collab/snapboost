# Shizuku API is accessed reflectively at runtime - keep its public surface.
-keep class dev.rikka.shizuku.** { *; }
-keep class rikka.** { *; }
-dontwarn dev.rikka.**
-dontwarn rikka.**

# AudioTrack / AudioRecord JNI-backed getters we read reflectively on some OEM builds
-keepclassmembers class android.media.AudioTrack {
    public static int getMinBufferSize(int, int, int);
    public static int getNativeOutputSampleRate();
    public static int getNativeOutputSampleRateStatic();
}
