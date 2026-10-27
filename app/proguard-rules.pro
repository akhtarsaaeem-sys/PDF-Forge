# iText
-keep class com.itextpdf.** { *; }
-dontwarn com.itextpdf.**

# Tesseract
-keep class cz.adaptech.tesseract4android.** { *; }
-dontwarn cz.adaptech.tesseract4android.**

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.pdforge.app.**$$serializer { *; }
-keepclassmembers class com.pdforge.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.pdforge.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep application class
-keep class com.pdforge.app.PDFForgeApp { *; }

# General
-keepattributes Signature
-keepattributes *Annotation*
