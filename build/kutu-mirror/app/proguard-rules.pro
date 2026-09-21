# Keep JNI callback interface (called from native code)
-keep class local.kutu.mirror.bridge.RaopCallbackHandler { *; }
-keep class * implements local.kutu.mirror.bridge.RaopCallbackHandler { *; }
-keep class local.kutu.mirror.bridge.LogListener { *; }
-keep class * implements local.kutu.mirror.bridge.LogListener { *; }

# Keep NativeBridge native methods
-keep class local.kutu.mirror.bridge.NativeBridge { *; }
