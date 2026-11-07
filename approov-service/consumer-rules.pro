# Approov SDK Consumer Rules

# Retain public interfaces for the app and service layer
-keep class com.criticalblue.approovsdk.Approov {
    public *;
}
-keep class com.criticalblue.approovsdk.Approov$* {
    public *;
}

# Retain all native methods to ensure JNI binds correctly
-keepclasseswithmembernames class com.criticalblue.approovsdk.** {
    native <methods>;
}

# Keep classes containing native methods from being renamed, as JNI often relies on class names
-keepnames class com.criticalblue.approovsdk.** {
    native <methods>;
}

# Tink is bundled relocated under io.approov.util.okhttp and is otherwise left
# for R8 to shrink: no rule keeps its classes. This is Tink's own
# META-INF/proguard/protobuf.pro restated for the relocated package (the
# original names the package before relocation). It only keeps fields of
# protobuf messages that are already live, so it retains nothing on its own.
-keepclassmembers class * extends io.approov.util.okhttp.tink.shaded.protobuf.GeneratedMessageLite {
  <fields>;
}
