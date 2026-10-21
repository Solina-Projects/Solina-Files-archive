# Preserve attributes used by reflection and serialization libraries.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# JNI exports in rust-core/src/lib.rs use the class and native method names
# as part of their exported Java_* symbols. Renaming them breaks native calls.
-keep class ir.solina.fm.nativecore.RustFileEngine { *; }

# Remove Android log calls from optimized release builds.
# Warning/error logs are removed too; use crash reporting for production diagnostics.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
