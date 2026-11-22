# PDFBox (exportar setlists, juntar imagens em PDF)
-keep class com.tom_roush.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.**

# commons-compress (backup .zip): dependências opcionais não usadas
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-keep class org.apache.commons.compress.archivers.zip.** { *; }
