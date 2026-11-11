# Native code reads and writes these classes by name through JNI: keep them as they are.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
