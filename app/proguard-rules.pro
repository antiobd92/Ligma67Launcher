# Ligma67Launcher R8 rules.
# kotlinx.serialization ships its own consumer rules; keep our serializable models' companions.
-keepclassmembers @kotlinx.serialization.Serializable class com.ligma67.launcher.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.ligma67.launcher.**$$serializer { *; }

# Hidden API reflection (StatusBarManager) lives in the framework, nothing to keep on our side.
-dontwarn android.app.StatusBarManager
