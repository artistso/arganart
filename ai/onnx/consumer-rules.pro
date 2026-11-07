# ONNX Runtime loads its native library and some classes reflectively. These rules
# travel with the AAR to any consumer that minifies (P7 enables minification).
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
