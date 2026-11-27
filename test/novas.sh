#!/bin/bash
# Prepara os arquivos de teste e confere as funções novas pela tela.
PKG=com.eduberigo.estante
mkdir -p out
pip install -q pillow 2>/dev/null || pip install -q --break-system-packages pillow 2>/dev/null
adb install -r app/build/outputs/apk/release/app-release.apk >/dev/null 2>&1
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell mkdir -p "/sdcard/Partituras/Casamentos"
for f in test/pdfs/*; do adb push "$f" "/sdcard/Partituras/Casamentos/" >/dev/null; done
adb shell "rm -rf /sdcard/Documents/Estante"
python3 -u test/novas.py 2>&1 | tee out/novas.txt
