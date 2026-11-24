#!/bin/bash
# Prepara dados de teste e roda o teste de todos os botões.
PKG=com.eduberigo.estante
mkdir -p out
adb install -r app/build/outputs/apk/release/app-release.apk >/dev/null 2>&1
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell mkdir -p "/sdcard/Partituras/Casamentos" "/sdcard/Partituras/Pop Rock"
for f in test/pdfs/*; do
  adb push "$f" "/sdcard/Partituras/Casamentos/" >/dev/null
  adb push "$f" "/sdcard/Partituras/Pop Rock/" >/dev/null
done
adb shell "rm -rf /sdcard/pristine; mkdir -p /sdcard/pristine; cp -r /sdcard/Partituras /sdcard/pristine/"
python3 -u test/botoes.py "${1:-2400}" 2>&1 | tee out/botoes.txt
