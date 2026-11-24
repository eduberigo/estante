import re, subprocess, sys, time
# Toca 3 vezes na barra "Preto" (entre os rótulos "mais claro" e "mais escuro") do painel de imagem.
xml = open("out/ui-tmp.xml", encoding="utf-8").read()
def bounds(label):
    for m in re.finditer(r'<node [^>]*>', xml):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n)
        if t and t.group(1) == label:
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
            return [int(x) for x in b.groups()]
    return None
l = bounds("mais claro"); r = bounds("mais escuro")
if not l or not r:
    print("NAO ACHOU a barra Preto"); sys.exit(0)
y = (l[1] + l[3]) // 2
x0, x1 = l[2] + 30, r[0] - 30
for f in (0.8, 0.25, 0.6):
    x = int(x0 + (x1 - x0) * f)
    subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
    print("toque na barra", x, y)
    time.sleep(1.5)
