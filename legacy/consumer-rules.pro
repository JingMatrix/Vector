# Keeping a class also keeps the optimiser from merging it into another one, so the resource types
# below stay classes of their own. That says nothing about the classes the optimiser *invents*: a
# lambda written anywhere becomes a class, and classes of the same shape are merged afterwards, so a
# reference can end up in a class no one wrote and no rule here names. Types whose super class is
# generated at runtime must not travel that way, since they cannot be resolved until the device has
# built the super class and the runtime never retries a failed resolution. That invariant is checked
# against the optimised dex by checkXResourcesIsolationRelease, not from this file.
-keep class android.** { *; }
-keep class de.robv.android.xposed.** { *; }

# The in-memory built class xposed.dummy.XResourcesSuperClass exists only on the device, so the
# class below is a deliberate split: it isolates the reference to XResources from its owner, which
# would otherwise be verified long before that super class exists.
-keepclassmembers class org.matrix.vector.legacy.LegacyDelegateImpl$ResourceProxy { *; }
