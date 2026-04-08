-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
-keepclasseswithmembers class org.matrix.vector.daemon.VectorDaemon {
    public static void main(java.lang.String[]);
}
-keepclasseswithmembers class org.matrix.vector.daemon.Cli {
    public static void main(java.lang.String[]);
}


# Keep IPC data models intact so Gson serializes the correct JSON keys
-keep class org.matrix.vector.daemon.CliRequest { *; }
-keep class org.matrix.vector.daemon.CliResponse { *; }

# Preserve annotations, generic signatures, and inner classes (critical for picocli reflection)
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Keep internal Picocli classes required for `mixinStandardHelpOptions = true`
-keep class picocli.CommandLine$AutoHelpMixin { *; }
-keep class picocli.CommandLine$HelpCommand { *; }

# Keep ANY class (and its constructor) annotated with @Command
-keep @picocli.CommandLine$Command class * {
    <init>(...);
}

# Keep ANY field/method using a Picocli annotation (@Option, @Parameters, etc.)
-keepclassmembers class * {
    @picocli.CommandLine$* *;
}


-keepclasseswithmembers class org.matrix.vector.daemon.env.LogcatMonitor {
    private int refreshFd(boolean);
}
-keepclassmembers class ** implements android.content.ContextWrapper {
    public int getUserId();
    public android.os.UserHandle getUser();
}
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
-repackageclasses
-allowaccessmodification
