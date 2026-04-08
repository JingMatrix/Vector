-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
-keepclasseswithmembers class org.matrix.vector.daemon.VectorDaemon {
    public static void main(java.lang.String[]);
}
-keepclasseswithmembers class org.matrix.vector.daemon.Cli {
    public static void main(java.lang.String[]);
}
-keep class org.matrix.vector.daemon.Cli { *; }
-keep class org.matrix.vector.daemon.Cli$Companion { *; }
-keep class org.matrix.vector.daemon.*Command { *; }
-keep class org.matrix.vector.daemon.CliRequest { *; }
-keep class org.matrix.vector.daemon.CliResponse { *; }

-keep class picocli.CommandLine$AutoHelpMixin { *; }
-keep class picocli.CommandLine$HelpCommand { *; }
-keep @picocli.CommandLine$Command class picocli.** { *; }

# MUST keep annotations for Picocli to function via reflection
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

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
