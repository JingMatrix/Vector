-keepclasseswithmembers class org.matrix.vector.core.Main {
    public static void forkCommon(boolean, boolean, java.lang.String, java.lang.String, android.os.IBinder);
}
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
-keepclasseswithmembers class org.matrix.vector.service.BridgeService {
    public static boolean *(android.os.IBinder, int, long, long, int);
}
-repackageclasses
-allowaccessmodification
