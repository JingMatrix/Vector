# The zygisk hooker reaches the manager entirely by reflection: it loads
# "<managerPackage>.Constants" out of the injected dex and invokes the static
# setBinder(IBinder) on it. Neither the class nor the method has a call site inside
# this APK, so R8 would otherwise remove or rename both and the binder handshake
# would fail silently at runtime.
-keep class org.matrix.vector.manager.Constants {
    public static boolean setBinder(android.os.IBinder);
}

# ParasiticManagerHooker redirects the resolved activity to this class by name.
-keep class org.matrix.vector.manager.ui.MainActivity { <init>(); }

# AIDL stubs and the parcelables crossing the daemon boundary.
-keep class org.matrix.vector.ipc.** { *; }
-keep class rikka.parcelablelist.** { *; }

# kotlinx.serialization keeps generated serializers reachable from the companion.
-keepclassmembers class **$$serializer { *** descriptor; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Gson models are constructed reflectively from field names.
-keepclassmembers class org.matrix.vector.manager.data.model.** { <fields>; }

# An enum's constants are reached reflectively: Enum.valueOf asks the class for its values()
# method by name. Kotlin no longer calls that method itself — `entries` compiles to its own
# synthetic field — so the last call site is usually gone and R8 shrinks values() away, which
# leaves every enum in the APK undeserializable. Compose saved instance state is what reaches it
# here: Parcel has no enum case and java.lang.Enum is Serializable, so a saved enum is written
# as VAL_SERIALIZABLE, and restoring the activity after its process died threw
# NoSuchMethodException on the navigation suite's own state value (#871). AGP's
# proguard-android-optimize.txt carries this stanza, but that file has not been on the
# proguardFiles list since #263, so it has to be written out here.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# The same restore path reaches Parcelables, and finds their CREATOR by a reflective field lookup
# that R8 cannot see either, so it drops the field from every class that does not otherwise
# reference it. Compose keeps its own state in one: a `mutableStateOf` that survives process death
# is a ParcelableSnapshotMutableState, and reading it back threw BadParcelableException. The legacy
# manager carried this rule by hand; the rewrite in #796 did not bring it across.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# OkHttp / Okio ship analysis-only references to optional platform classes.
-dontwarn okhttp3.internal.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# androidx.window compiles against the OEM window extensions and the older sidecar
# interface. Neither ships in the SDK — they are provided by the device at runtime, and
# on a device that has neither the library falls back — so R8 sees the references as
# unresolvable and refuses to complete. The navigation suite scaffold pulls the library
# in, so the manager inherits them whether or not it ever asks about a folding screen.
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
