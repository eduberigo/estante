#!/bin/bash
# Instala o APK no emulador, abre o app com e sem permissão e guarda prints + log.
set -x
PKG=com.eduberigo.estante
mkdir -p out
adb logcat -c
adb shell settings put secure immersive_mode_confirmations confirmed
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

# 3a) Navegar por artista (Início)
adb shell am force-stop $PKG
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 8
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml out/ui-tmp.xml >/dev/null 2>&1; }
W0=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f1); H0=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f2)
adb shell input swipe $((W0/5)) $((H0*4/5)) $((W0/5)) $((H0/4)) 400; sleep 2
dump; python3 test/tap.py "Artistas" > out/browse.txt; sleep 4; dump; cp out/ui-tmp.xml out/ui-artistas.xml
python3 test/tap.py "Tom Jobim" >> out/browse.txt; sleep 3; dump; cp out/ui-tmp.xml out/ui-artista1.xml
python3 test/tap.py "Garota de Ipanema" >> out/browse.txt; sleep 1; dump; python3 test/tap.py "Garota de Ipanema" >> out/browse.txt; sleep 4
dump; python3 test/tap.py "Got it" > /dev/null; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 3; dump; cp out/ui-tmp.xml out/ui-voltou.xml
python3 test/tap.py "Agrupar por pasta" >> out/browse.txt; sleep 2; dump; cp out/ui-tmp.xml out/ui-pasta.xml
python3 test/tap.py "tom F" long >> out/browse.txt; sleep 2; dump; cp out/ui-tmp.xml out/ui-sel.xml
python3 test/tap.py "Opções" >> out/browse.txt; sleep 2; dump; cp out/ui-tmp.xml out/ui-opcoes.xml
python3 test/tap.py "Pré-visualizar" >> out/browse.txt; sleep 2

# 3b) Leitor + anotação pela interface
dump() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml out/ui-tmp.xml >/dev/null 2>&1; }
adb shell settings put secure immersive_mode_confirmations confirmed
adb shell am force-stop $PKG
adb shell "am start -n $PKG/.MainActivity --es abrir '/sdcard/Partituras/Casamentos/Perfect - Ed Sheeran - X - pop - G - 95.pdf'"
sleep 6
W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f1); H=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f2)
dump; python3 test/tap.py "Got it" > /dev/null; sleep 1
adb shell input tap $((W/2)) $((H/2)); sleep 2; dump; cp out/ui-tmp.xml out/ui-leitor.xml
python3 test/tap.py "Anotar" > out/anot.txt; sleep 2
adb shell input swipe $((W/3)) $((H/3)) $((W*2/3)) $((H/2)) 600; sleep 1
dump; python3 test/tap.py "Dedilhado" >> out/anot.txt; sleep 1
adb shell input tap $((W/2)) $((H*2/3)); sleep 1
dump; python3 test/tap.py "Âncora" >> out/anot.txt; sleep 1
adb shell input tap $((W/2)) $((H/4)); sleep 2
dump; python3 test/tap.py "Coda" >> out/anot.txt; sleep 2
dump; python3 test/tap.py "Borracha" >> out/anot.txt; sleep 1
adb shell input swipe $((W/3)) $((H/3)) $((W*2/3)) $((H/2)) 800; sleep 1
adb shell input tap $((W/2)) $((H*2/3)); sleep 1
dump; python3 test/tap.py "Concluir" >> out/anot.txt; sleep 2
dump; cp out/ui-tmp.xml out/ui-depois.xml
adb shell input tap $((W/2)) $((H/2)); sleep 2; dump; python3 test/tap.py "Metrônomo" >> out/anot.txt; sleep 2
dump; python3 test/tap.py "Contagem" >> out/anot.txt; sleep 1; dump; cp out/ui-tmp.xml out/ui-metro.xml
python3 test/tap.py "Fechar" >> out/anot.txt; sleep 1
adb shell input tap $((W/2)) $((H/2)); sleep 2; dump; python3 test/tap.py "Imagem" >> out/anot.txt; sleep 2
dump; cp out/ui-tmp.xml out/ui-imagem.xml; python3 test/slider.py > out/slider.txt; sleep 2
python3 test/tap.py "Legível" >> out/anot.txt; sleep 3
dump; python3 test/tap.py "Fechar" >> out/anot.txt; sleep 1
adb shell input tap $((W/2)) $((H/2)); sleep 2; dump; python3 test/tap.py "Recortar" >> out/anot.txt; sleep 3
dump; cp out/ui-tmp.xml out/ui-recorte.xml; python3 test/tap.py "Só na tela" >> out/anot.txt; sleep 3
adb exec-out screencap -p > out/3-anotado.png

# 3c) Pedal: toque na tela e logo depois UMA seta para a direita deve virar a página
adb shell am force-stop $PKG
adb shell "am start -n $PKG/.MainActivity --es abrir '/sdcard/Partituras/Casamentos/Perfect - Ed Sheeran - X - pop - G - 95.pdf'"
sleep 6
adb shell input tap $((W/2)) $((H/2)); sleep 1     # mostra a barra (toque = entra em modo toque)
adb shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1
dump; grep -o 'text="[0-9]* / [0-9]*"' out/ui-tmp.xml > out/pedal.txt
adb shell input keyevent KEYCODE_DPAD_LEFT; sleep 1
dump; grep -o 'text="[0-9]* / [0-9]*"' out/ui-tmp.xml >> out/pedal.txt

# 4) Autoteste de PDF/exportação
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity --es selftest /sdcard/Partituras/Casamentos
sleep 75

adb logcat -d > out/logcat-completo.txt
grep -E "AndroidRuntime|FATAL|$PKG|estante" out/logcat-completo.txt > out/logcat-app.txt
set +x
for f in out/install.txt out/atividade-1.txt out/atividade-2.txt; do echo "===== $f"; cat "$f"; done
for f in out/ui1.xml out/ui2.xml out/ui.xml; do echo "===== TEXTOS $f"; grep -o 'text="[^"]*"' "$f" | grep -v 'text=""' | head -60; grep -o 'content-desc="[^"]*"' "$f" | grep -v '=""' | head -30; done
echo "===== LEITOR"; grep -o 'text="[^"]*"' out/ui-leitor.xml | grep -v 'text=""' | head -30
echo "===== ANOTACAO"; cat out/anot.txt; grep -o 'text="[^"]*"' out/ui-depois.xml | grep -v 'text=""' | head -30
echo "===== BROWSE"; cat out/browse.txt; grep -o 'text="[^"]*"' out/ui-artistas.xml | grep -v 'text=""' | head -20; echo "--- artista"; grep -o 'text="[^"]*"' out/ui-artista1.xml | grep -v 'text=""' | head -12; echo "--- voltou do leitor"; grep -o 'text="[^"]*"' out/ui-voltou.xml | grep -v 'text=""' | head -12; echo "--- agrupado por pasta"; grep -o 'text="[^"]*"' out/ui-pasta.xml | grep -v 'text=""' | head -10
echo "--- selecao"; grep -o 'text="[^"]*"\|checkable="true" checked="[a-z]*"' out/ui-sel.xml | grep -v 'text=""' | head -12
echo "--- opcoes"; grep -o 'text="[^"]*"' out/ui-opcoes.xml | grep -v 'text=""' | head -20
for f in out/ui-metro.xml out/ui-imagem.xml out/ui-recorte.xml; do echo "===== $f"; grep -o 'text="[^"]*"' $f | grep -v 'text=""' | head -25; done
echo "===== PEDAL (esperado 2 / 2 e depois 1 / 2)"; cat out/pedal.txt
echo "===== TOQUES (atividade e leitor)"; grep -o "Estante: toque.*" out/logcat-completo.txt | head -30
echo "===== BARRA PRETO (esperado 3 ajustes)"; cat out/slider.txt; grep -c "ajuste de imagem" out/logcat-completo.txt; grep -o "ajuste de imagem.*" out/logcat-completo.txt | head -5
echo "===== AUTOTESTE"; grep -E "ESTANTE_SELFTEST" out/logcat-completo.txt; grep -E "W Estante" -A 4 out/logcat-completo.txt | head -40
echo "===== ERROS"; grep -E "FATAL|AndroidRuntime|E/.*estante" -A 25 out/logcat-completo.txt | head -150
echo "===== FIM"
exit 0
