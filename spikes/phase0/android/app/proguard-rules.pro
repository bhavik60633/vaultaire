# JNA looks up its own classes and the UniFFI structures by reflection at runtime.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-dontwarn java.awt.**

# The JNI comparison class: native method names must match the Rust export.
-keepclasseswithmembernames class dev.vaultaire.spike.Jni { native <methods>; }
