# R8 is on for release for speed, not size — see the note on isMinifyEnabled in
# build.gradle.kts. These rules keep it away from everything that finds classes
# by name at runtime, where a missing class shows up as a crash on a device
# rather than as a build error.

# Nothing is renamed. Stack traces stay readable, and every lookup by name —
# enum names in preferences, Media3 custom commands, library reflection — keeps
# working without a rule of its own.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*

# ---- HTML parsing -------------------------------------------------------------
# jsoup builds its node graph reflectively.
-keep class org.jsoup.** { *; }

# ---- Native -------------------------------------------------------------------
# ONNX Runtime is called from JNI, which R8 cannot see.
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# ---- Serialization --------------------------------------------------------------
# protobuf-lite reads its generated messages' fields reflectively.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
# Our own @Serializable models, whole: kotlinx.serialization's bundled rules
# cover the generated serializers, and keeping the classes too costs little.
-keep @kotlinx.serialization.Serializable class com.extrumz.app.** { *; }

# ---- WebView bridges ------------------------------------------------------------
# The Spotify token page calls back into this from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- JVM-only APIs ------------------------------------------------------------
# Types that exist on a desktop JVM and not on Android. Some of the libraries
# this app depends on have desktop-only paths that mention them; nothing here
# reaches those paths, so the debug build — which R8 never touches — has always
# lived without them.
-dontwarn java.beans.**
-dontwarn java.lang.management.**
-dontwarn javax.el.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn org.ietf.jgss.**
