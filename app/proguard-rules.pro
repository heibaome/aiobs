# AutoAim ProGuard Rules

# Keep MNN JNI
-keep class com.autoaim.core.MNNInference { *; }
-keepclasseswithmembernames class com.autoaim.core.MNNInference {
    native <methods>;
}

# Keep Shizuku
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Keep Valorant config
-keep class com.autoaim.valorant.ValorantConfig { *; }
-keep class com.autoaim.valorant.ValorantConfig$* { *; }

# Keep service
-keep class com.autoaim.core.AutoAimService { *; }

# General Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
