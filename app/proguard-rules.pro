# Monitored Check R8 rules.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Crash reports must stay human readable: keep our own class names so stack
# traces stored on-device remain meaningful to the user.
-keep class com.monitorcheck.** { *; }

-dontwarn org.jetbrains.annotations.**
