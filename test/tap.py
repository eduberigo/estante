import re, subprocess, sys
# Uso: tap.py "texto"  -> toca no elemento cujo text/content-desc contém o texto
xml = open("out/ui-tmp.xml", encoding="utf-8").read()
want = sys.argv[1]
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); d = re.search(r'content-desc="([^"]*)"', n)
    label = (t.group(1) if t else "") + "|" + (d.group(1) if d else "")
    if want in label:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        x = (int(b.group(1)) + int(b.group(3))) // 2; y = (int(b.group(2)) + int(b.group(4))) // 2
        subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
        print("tocou", want, x, y); sys.exit(0)
print("NAO ACHOU", want)
