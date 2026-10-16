# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.nezjusz.codedroid.core.net.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.nezjusz.codedroid.core.net.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.nezjusz.codedroid.core.net.**$$serializer { *; }
