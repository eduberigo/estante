#!/bin/bash
# Instala o APK no emulador, abre o app com e sem permissão e guarda prints + log.
set -x
PKG=com.eduberigo.estante
mkdir -p out
adb logcat -c
adb install -r app/build/outputs/apk/release/app-release.apk > out/install.txt 2>&1
adb shell pm list packages | grep estante >> out/install.txt
adb shell cmd package resolve-activity --brief $PKG >> out/install.txt 2>&1

# 1) Sem permissão
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >> out/install.txt 2>&1
sleep 8
adb exec-out screencap -p > out/1-sem-permissao.png
adb shell uiautomator dump /sdcard/ui1.xml >/dev/null 2>&1; adb pull /sdcard/ui1.xml out/ui1.xml
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" > out/atividade-1.txt

# 2) Com permissão e partituras de teste
adb shell mkdir -p "/sdcard/Partituras/Casamentos" "/sdcard/Partituras/Pop Rock"
for f in test/pdfs/*; do
  adb push "$f" "/sdcard/Partituras/Casamentos/"
  adb push "$f" "/sdcard/Partituras/Pop Rock/"
done
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell am force-stop $PKG
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 8
adb exec-out screencap -p > out/2-com-permissao.png
adb shell uiautomator dump /sdcard/ui2.xml >/dev/null 2>&1; adb pull /sdcard/ui2.xml out/ui2.xml
adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" > out/atividade-2.txt

# 3) Entra em Partituras/Casamentos (toque pelo texto não é confiável; usa am start com a pasta salva)
adb shell am force-stop $PKG
adb shell "run-as $PKG true" 2>/dev/null
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 5
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml out/ui.xml

# 4) Autoteste de PDF/exportação
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity --es selftest /sdcard/Partituras/Casamentos
sleep 20

adb logcat -d > out/logcat-completo.txt
grep -E "AndroidRuntime|FATAL|$PKG|estante" out/logcat-completo.txt > out/logcat-app.txt
set +x
for f in out/install.txt out/atividade-1.txt out/atividade-2.txt; do echo "===== $f"; cat "$f"; done
for f in out/ui1.xml out/ui2.xml out/ui.xml; do echo "===== TEXTOS $f"; grep -o 'text="[^"]*"' "$f" | grep -v 'text=""' | head -60; grep -o 'content-desc="[^"]*"' "$f" | grep -v '=""' | head -30; done
echo "===== AUTOTESTE"; grep -E "ESTANTE_SELFTEST" -A 8 out/logcat-completo.txt | head -60
echo "===== ERROS"; grep -E "FATAL|AndroidRuntime|E/.*estante" -A 25 out/logcat-completo.txt | head -150
echo "===== FIM"
exit 0
