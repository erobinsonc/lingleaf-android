# Release builds: R8 shrinks the app. kotlinx.serialization ships its own rules;
# these keep the saved-data classes' serializers explicitly, so a library update
# can never make the app unable to read what the learner saved.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class app.lingleaf.** {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class app.lingleaf.**
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.lingleaf.**$$serializer { *; }

# No log output in shrunk builds: nothing about the learner's reading reaches logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
