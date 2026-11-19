# Projektspezifische R8-Regeln.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# PdfBox-Android (nutzt Reflection und optionale Bibliotheken)
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
