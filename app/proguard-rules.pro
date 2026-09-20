# Правила R8/ProGuard. Приложение обращается к org.json и framework-классам,
# которые не нужно защищать от обфускации.
-keepattributes SourceFile,LineNumberTable
-dontwarn org.json.**
