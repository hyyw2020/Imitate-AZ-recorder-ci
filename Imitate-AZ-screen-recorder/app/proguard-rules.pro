# Add project specific ProGuard rules here.
-keepattributes Signature
-keepattributes *Annotation*

-keep class com.monkeycode.screenrecorder.** { *; }
-keepclassmembers class com.monkeycode.screenrecorder.** { *; }

-keep class android.media.** { *; }
-keep class android.hardware.display.** { *; }
