# Sanad ProGuard Rules

# Keep Retrofit
-keepattributes Signature
-keepattributes Exceptions
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*

# Keep data classes
-keep class com.sanad.agent.model.** { *; }

# Keep accessibility service
-keep class com.sanad.agent.service.SanadAccessibilityService { *; }
