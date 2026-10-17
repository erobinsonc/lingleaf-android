# Release only: no log output, so nothing about the learner's reading reaches logcat.
# The CI tour build keeps its logs, so a failure there shows its cause.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
