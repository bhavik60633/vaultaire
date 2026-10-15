# JNA and the UniFFI bindings find their classes by reflection at runtime (ADR-008).
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.vault_ffi.** { *; }
-dontwarn java.awt.**
