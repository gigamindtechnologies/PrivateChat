# Tor runs through JNI; keep its Java side intact.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.gigamindtechnologies.privatechat.**$$serializer { *; }
-keepclassmembers class com.gigamindtechnologies.privatechat.** {
    *** Companion;
}
-keepclasseswithmembers class com.gigamindtechnologies.privatechat.** {
    kotlinx.serialization.KSerializer serializer(...);
}
