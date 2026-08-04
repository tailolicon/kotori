-dontobfuscate

-keep,allowoptimization class eu.kanade.**
-keep,allowoptimization class tachiyomi.**
-keep,allowoptimization class mihon.**

# Keep common dependencies used in extensions
-keep,allowoptimization class androidx.preference.** { public protected *; }
-keep,allowoptimization class kotlin.** { public protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public protected *; }
-keep,allowoptimization class kotlinx.serialization.** { public protected *; }
-keep,allowoptimization class kotlin.time.** { public protected *; }
-keep,allowoptimization class okhttp3.** { public protected *; }
-keep,allowoptimization class okio.** { public protected *; }
-keep,allowoptimization class org.jsoup.** { public protected *; }
-keep,allowoptimization class rx.** { public protected *; }
-keep,allowoptimization class app.cash.quickjs.** { public protected *; }
-keep,allowoptimization class uy.kohesive.injekt.** { public protected *; }
-keep,allowoptimization class com.squareup.zstd.** { public protected *; }

# libplayer.so invokes these callbacks by their exact JNI names. R8 cannot see
# native-to-Java calls and otherwise removes them from minified release builds.
-keep class is.xyz.mpv.MPVLib { *; }
-keep class is.xyz.mpv.MPVLib$* { *; }

# libmoonshine-jni.so resolves these classes, their fields and their constructors by name from
# native code — synthesis results and word timings are built on the native side. The AAR ships no
# consumer rules of its own, so without this R8 renames them and the novel reader's neural voice
# fails at runtime with no build-time warning at all.
-keep class ai.moonshine.voice.** { *; }

# libonnxruntime.so constructs OrtSession/OnnxValue result objects and resolves their constructors
# from JNI. The Android artifact's consumer rules do not protect every class reached by this path;
# R8 otherwise removes a constructor and release builds abort in NewObject(mid == null).
-keep class ai.onnxruntime.** { *; }

# ML Kit text recognition wires its recognisers together through a generated component registry that
# it looks up reflectively, so nothing in the bytecode references those classes directly and R8 is
# free to strip them. Release builds then failed inside TextRecognition.getClient() with a null
# field on com.google.mlkit.vision.text.internal.zzo — OCR dead, and with it every provider that
# reads the page on-device. Debug builds never showed it because they are not minified.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.common.annotation.** { *; }
-keepclassmembers class * {
    @com.google.android.gms.common.annotation.KeepForSdk *;
}
-dontwarn com.google.mlkit.**

# NewPipe's protobuf-javalite models resolve their generated fields by name.
# Preserve those fields so YouTube playlist metadata survives R8 shrinking.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# From extensions-lib
-keep,allowoptimization class eu.kanade.tachiyomi.network.interceptor.RateLimitInterceptorKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.interceptor.SpecificHostRateLimitInterceptorKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.NetworkHelper { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.OkHttpExtensionsKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.network.RequestsKt { public protected *; }
-keep,allowoptimization class eu.kanade.tachiyomi.AppInfo { public protected *; }

-keepclassmembers class * implements java.io.Serializable {
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

##---------------Begin: proguard configuration for RxJava 1.x  ----------
-dontwarn sun.misc.**

-keepclassmembers class rx.internal.util.unsafe.*ArrayQueue*Field* {
   long producerIndex;
   long consumerIndex;
}

-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueProducerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode producerNode;
}

-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueConsumerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode consumerNode;
}

-dontnote rx.internal.util.PlatformDependent
##---------------End: proguard configuration for RxJava 1.x  ----------

##---------------Begin: proguard configuration for okhttp  ----------
-keepclasseswithmembers class okhttp3.MultipartBody$Builder { *; }
##---------------End: proguard configuration for okhttp  ----------

##---------------Begin: proguard configuration for kotlinx.serialization  ----------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.** # core serialization annotations

# kotlinx-serialization-json specific. Add this if you have java.lang.NoClassDefFoundError kotlinx.serialization.json.JsonObjectSerializer
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class eu.kanade.**$$serializer { *; }
-keepclassmembers class eu.kanade.** {
    *** Companion;
}
-keepclasseswithmembers class eu.kanade.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.** {
    <methods>;
}
##---------------End: proguard configuration for kotlinx.serialization  ----------

# XmlUtil
-keep public enum nl.adaptivity.xmlutil.EventType { *; }

# Firebase
-keep class com.google.firebase.installations.** { *; }
-keep interface com.google.firebase.installations.** { *; }

# Built-in sources
#
# `AnimeHttpSource.id` is `by lazy`, which compiles to a synthetic lambda. R8 horizontally merged
# that lambda with one from the built-in subclass, so *every* source — including extension ones —
# ran the built-in body and died casting itself to BuiltInHttpSource the moment the source list was
# assembled. Debug never saw it because nothing merges there. Keeping the built-in hierarchy takes
# it out of merging; these classes are a handful of scrapers, so nothing is lost by not shrinking
# them.
-keep class eu.kanade.tachiyomi.source.anime.builtin.** { *; }
-keep class eu.kanade.tachiyomi.source.novel.builtin.** { *; }
-keep class * implements eu.kanade.tachiyomi.animesource.BuiltInAnimeSource { *; }
-keep class * implements eu.kanade.tachiyomi.source.NovelSource { *; }
