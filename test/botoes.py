"""Toca em TODOS os botões de cada tela do Estante, como um dedo de verdade toca
(o dedo sempre mexe um tiquinho entre encostar e soltar), e registra se cada um respondeu.

Para cada botão:
  1. toque de dedo (encosta, desliza 5 px, solta em ~0,12 s)
  2. se nada mudou na tela, toque "limpo" (sem nenhum movimento), só para diagnóstico
Resultado:
  OK            -> respondeu ao toque de dedo
  FALHA-DEDO    -> só respondeu ao toque limpo (é exatamente o defeito de "precisa tocar 2 vezes")
  SEM-MUDANCA   -> nenhum dos dois mudou a tela (botão que não muda nada visível, ex.: estrela já marcada)
Também entra no que abriu cada botão (menus, diálogos, painéis) e testa os botões de lá (2º nível).
"""
import re, subprocess, sys, time
import xml.etree.ElementTree as ET

PKG = "com.eduberigo.estante"
A = "/sdcard/Partituras"
PERFECT = f"{A}/Casamentos/Perfect - Ed Sheeran - X - pop - G - 95.pdf"
IMGDIR = f"{A}/Casamentos"
BUDGET = float(sys.argv[1]) if len(sys.argv) > 1 else 1800  # segundos para o teste todo
T0 = time.time()


def adb(*a, timeout=40):
    try:
        return subprocess.run(["adb", *a], capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def sh(cmd):
    return adb("shell", cmd)


m = re.search(r"(\d+)x(\d+)", sh("wm size").strip().splitlines()[-1])
W, H = int(m.group(1)), int(m.group(2))
CLOCK = re.compile(r"^\d{1,2}:\d{2}$")


def dump():
    for _ in range(3):
        x = adb("exec-out", "uiautomator", "dump", "/dev/tty", timeout=30)
        i, j = x.find("<?xml"), x.rfind("</hierarchy>")
        if i >= 0 and j > i:
            try:
                return ET.fromstring(x[i:j + len("</hierarchy>")])
            except ET.ParseError:
                pass
        time.sleep(1)
    return None


def bounds(n):
    b = re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))
    return tuple(int(v) for v in b)


def texts(n):
    out = []
    for d in n.iter("node"):
        t = (d.get("text") or "").strip()
        c = (d.get("content-desc") or "").strip()
        for v in (t, c):
            if v and not CLOCK.match(v) and v not in out:
                out.append(v)
    return out


def label(n):
    t = (n.get("text") or "").strip() or (n.get("content-desc") or "").strip()
    if not t:
        t = " / ".join(texts(n)[:2])
    return (t or n.get("class", "?").split(".")[-1]).replace("\n", " ")[:50]


def pkg_of(root):
    for n in root.iter("node"):
        if n.get("package"):
            return n.get("package")
    return ""


def sig(root):
    if root is None:
        return ("sem-tela",)
    s = []
    for n in root.iter("node"):
        t = n.get("text") or ""
        if CLOCK.match(t.strip()):
            t = "<hora>"
        s.append((n.get("package"), t, n.get("content-desc") or "", n.get("checked"), n.get("selected"), n.get("bounds")))
    return tuple(s)


def buttons(root):
    """Botões do app visíveis na tela, na ordem em que aparecem."""
    if root is None:
        return []
    res, seen = [], {}
    for n in root.iter("node"):
        if n.get("package") != PKG:
            continue
        if n.get("clickable") != "true" and n.get("checkable") != "true":
            continue
        if n.get("enabled") == "false":
            continue
        x0, y0, x1, y1 = bounds(n)
        w, h = x1 - x0, y1 - y0
        if w < 8 or h < 8 or x1 <= 0 or y1 <= 0 or x0 >= W or y0 >= H:
            continue
        if w * h > 0.35 * W * H:  # área enorme (fundo de diálogo, página inteira)
            continue
        lb = label(n)
        k = seen.get(lb, 0)
        seen[lb] = k + 1
        res.append({"label": lb, "k": k, "b": (x0, y0, x1, y1)})
    return res


def keyset(root):
    return frozenset((b["label"], b["k"]) for b in buttons(root))


def find(root, btn):
    for b in buttons(root):
        if b["label"] == btn["label"] and b["k"] == btn["k"]:
            return b
    return None


def center(b):
    x0, y0, x1, y1 = b["b"]
    # dentro da tela
    return max(2, min(W - 3, (x0 + x1) // 2)), max(2, min(H - 3, (y0 + y1) // 2))


def tap_finger(b):
    x, y = center(b)
    sh(f"input swipe {x} {y} {x + 4} {y + 3} 120")


def tap_clean(b):
    x, y = center(b)
    sh(f"input tap {x} {y}")


def start(extra):
    sh(f"am force-stop {PKG}")
    sh('rm -rf /sdcard/Partituras; cp -r /sdcard/pristine/Partituras /sdcard/')
    sh(f'am start -n {PKG}/.MainActivity --ez limpar true --es fixar "{A}/Casamentos|{A}/Pop Rock" {extra}')
    time.sleep(5)


def tapl(text, clean=True, long=False):
    r = dump()
    for b in buttons(r):
        if text in b["label"]:
            x, y = center(b)
            if long:
                sh(f"input swipe {x} {y} {x} {y} 900")
            else:
                sh(f"input tap {x} {y}")
            time.sleep(2)
            return True
    # também procura texto que não é botão (itens de lista)
    if r is not None:
        for n in r.iter("node"):
            if text in (n.get("text") or "") or text in (n.get("content-desc") or ""):
                x0, y0, x1, y1 = bounds(n)
                x, y = (x0 + x1) // 2, (y0 + y1) // 2
                sh(f"input swipe {x} {y} {x} {y} 900" if long else f"input tap {x} {y}")
                time.sleep(2)
                return True
    print(f"  (preparo: não achou '{text}')")
    return False


def scroll_down():
    sh(f"input swipe {W // 5} {H * 4 // 5} {W // 5} {H // 4} 400")
    time.sleep(2)


def reader():
    start(f'--es abrir "{PERFECT}"')
    time.sleep(2)
    sh(f"input tap {W // 2} {H // 2}")
    time.sleep(2)


# Telas: nome -> preparo
SCREENS = [
    ("Leitor (barras)", reader),
    ("Leitor > Anotar", lambda: (reader(), tapl("Anotar"))),
    ("Leitor > Metrônomo", lambda: (reader(), tapl("Metrônomo"))),
    ("Leitor > Imagem", lambda: (reader(), tapl("Imagem"))),
    ("Leitor > Recortar", lambda: (reader(), tapl("Recortar"))),
    ("Leitor > Âncoras", lambda: (reader(), tapl("Âncoras"))),
    ("Início", lambda: start("")),
    ("Início (rolado)", lambda: (start(""), scroll_down())),
    ("Início > segurar pasta", lambda: (start(""), tapl("Casamentos", long=True))),
    ("Pasta", lambda: start(f'--es pasta "{A}/Casamentos"')),
    ("Pasta > pré-visualização", lambda: (start(f'--es pasta "{A}/Casamentos"'), tapl("Perfect"))),
    ("Pasta > menu ⋮", lambda: (start(f'--es pasta "{A}/Casamentos"'), tapl("Mais"))),
    ("Pasta > seleção", lambda: (start(f'--es pasta "{A}/Casamentos"'), tapl("Garota", long=True))),
    ("Pasta > seleção > Opções", lambda: (start(f'--es pasta "{A}/Casamentos"'), tapl("Garota", long=True), tapl("Opções"))),
    ("Busca", lambda: (start(f'--es pasta "{A}/Casamentos"'), tapl("Buscar"), sh("input text Perfect"), time.sleep(3))),
    ("Navegar > Artistas", lambda: (start(""), scroll_down(), tapl("Artistas"))),
    ("Navegar > Títulos", lambda: (start(""), scroll_down(), tapl("Títulos"))),
]

results = []  # (tela, botão, resultado)


def settle_to(prep, base_keys, last):
    """Garante que estamos na tela-base; devolve a árvore atual."""
    r = last if last is not None else dump()
    if r is not None and pkg_of(r) == PKG and keyset(r) == base_keys:
        return r
    if r is not None and pkg_of(r) != PKG:
        sh("input keyevent 4")
        time.sleep(1.5)
    prep()
    return dump()


def run_buttons(name, prep, base, btns, end, collect):
    base_keys = keyset(base)
    print(f"\n## {name}: {len(btns)} botões", flush=True)
    last = base
    opened = []
    for btn in btns:
        if time.time() > end:
            results.append((name, btn["label"], "NAO-TESTADO (tempo)"))
            continue
        cur = settle_to(prep, base_keys, last)
        b = find(cur, btn) if cur is not None else None
        if b is None:
            results.append((name, btn["label"], "SUMIU"))
            print(f"  ?  {btn['label']}  -> não achei de novo", flush=True)
            last = None
            continue
        before = sig(cur)
        tap_finger(b)
        time.sleep(1.3)
        after = dump()
        if sig(after) != before:
            res = "OK"
        else:
            tap_clean(b)
            time.sleep(1.3)
            after = dump()
            res = "FALHA-DEDO" if sig(after) != before else "SEM-MUDANCA"
        results.append((name, btn["label"], res))
        mark = {"OK": "✔", "FALHA-DEDO": "✘"}.get(res, "·")
        print(f"  {mark}  {btn['label']}  -> {res}", flush=True)
        if collect and res != "SEM-MUDANCA" and after is not None and pkg_of(after) == PKG:
            new = [x for x in buttons(after) if (x["label"], x["k"]) not in base_keys]
            if 0 < len(new) <= 30:
                opened.append(btn)
        last = after
    return opened


level2 = []
for name, prep in SCREENS:
    prep()
    base = dump()
    if base is None:
        print(f"## {name}: não consegui ler a tela", flush=True)
        continue
    for btn in run_buttons(name, prep, base, buttons(base), T0 + BUDGET * 0.6, True):
        level2.append((name, prep, btn))

print(f"\n==== 2º nível: {len(level2)} menus/diálogos/painéis abertos por botões", flush=True)
for name, prep, btn in level2:
    sub = f"{name} > {btn['label']}"
    if time.time() > T0 + BUDGET:
        results.append((sub, "(todos)", "NAO-TESTADO (tempo)"))
        continue

    def prep2(prep=prep, btn=btn):
        prep()
        r = dump()
        b = find(r, btn) if r is not None else None
        if b:
            tap_clean(b)
            time.sleep(2)

    prep()
    r0 = dump()
    k0 = keyset(r0) if r0 is not None else frozenset()
    prep2()
    r1 = dump()
    if r1 is None or pkg_of(r1) != PKG:
        continue
    new = [x for x in buttons(r1) if (x["label"], x["k"]) not in k0]
    run_buttons(sub, prep2, r1, new, T0 + BUDGET, False)

sh(f"am force-stop {PKG}")
tot = len(results)
cnt = {}
for _, _, r in results:
    k = r.split(" ")[0]
    cnt[k] = cnt.get(k, 0) + 1
print("\n==== RESUMO BOTOES")
print(f"total {tot}; " + "; ".join(f"{k} {v}" for k, v in sorted(cnt.items())))
print("---- FALHA-DEDO (só funcionaram sem o dedo mexer):")
for t, b, r in results:
    if r == "FALHA-DEDO":
        print(f"  {t} | {b}")
print("---- SEM-MUDANCA:")
for t, b, r in results:
    if r == "SEM-MUDANCA":
        print(f"  {t} | {b}")
print(f"==== FIM BOTOES ({int(time.time() - T0)} s)")
