"""Confere as funções novas pela tela: estrelas na lista, filtro da pasta, tirar do app / trazer de volta,
juntar em PDF (com a pergunta de abrir) e a barra de rolagem do "Adicionar a setlist"."""
import re, subprocess, time
import xml.etree.ElementTree as ET

PKG = "com.eduberigo.estante"
A = "/sdcard/Partituras"


def adb(*a, timeout=40):
    try:
        return subprocess.run(["adb", *a], capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def sh(c):
    return adb("shell", c)


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


def texts(r):
    out = []
    if r is None:
        return out
    for n in r.iter("node"):
        for k in ("text", "content-desc"):
            v = (n.get(k) or "").strip()
            if v:
                out.append(v)
    return out


def node(r, want, exact=False):
    if r is None:
        return None
    for n in r.iter("node"):
        for k in ("text", "content-desc"):
            v = (n.get(k) or "").strip()
            if (v == want) if exact else (want in v):
                return n
    return None


def center(n):
    b = [int(v) for v in re.findall(r"\d+", n.get("bounds"))]
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2


def tap(want, exact=False, long=False, wait=2.0):
    r = dump()
    n = node(r, want, exact)
    if n is None:
        print(f"   (não achei '{want}')")
        return False
    x, y = center(n)
    # toque de dedo: mexe um tiquinho
    sh(f"input swipe {x} {y} {x} {y} 900" if long else f"input swipe {x} {y} {x + 3} {y + 2} 110")
    time.sleep(wait)
    return True


def check(name, ok, extra=""):
    print(f"{'OK   ' if ok else 'FALHA'} {name}{(' — ' + extra) if extra else ''}", flush=True)


def start(extra=""):
    sh(f"am force-stop {PKG}")
    sh(f'am start -n {PKG}/.MainActivity --ez limpar true --es fixar "{A}/Casamentos" {extra}')
    time.sleep(6)


m = re.search(r"(\d+)x(\d+)", sh("wm size").strip().splitlines()[-1])
W, H = int(m.group(1)), int(m.group(2))

print("==== NOVAS FUNCOES")
# 1) Estrelas na lista da pasta
start(f'--es pasta "{A}/Casamentos"')
tap("Ver em lista")
r = dump()
rows = [n for n in r.iter("node") if (n.get("content-desc") or "") == "4 estrelas"] if r is not None else []
check("estrelas tocáveis na lista", len(rows) >= 2, f"{len(rows)} linhas com estrelas")
if rows:
    x, y = center(rows[0])
    sh(f"input swipe {x} {y} {x + 3} {y + 2} 110"); time.sleep(2)
filled = [v for v in texts(dump()) if v == "★"]
check("dar 4 estrelas pela lista (primeiro toque)", len(filled) == 4, f"{len(filled)} estrelas cheias na tela")

# 2) Filtro da pasta
tap("Filtrar nesta pasta")
sh("input text garota"); time.sleep(2)
t = texts(dump())
check("filtro da pasta mostra só o que bate", any("Garota de Ipanema" in v for v in t) and not any(v.startswith("Perfect") for v in t))
tap("Fechar filtro")
t = texts(dump())
check("fechar o filtro volta a mostrar tudo", any(v.startswith("Perfect") for v in t))

# 3) Tirar do app e trazer de volta
tap("Garota de Ipanema", long=True)
tap("Opções")
tap("Tirar do app")
t = texts(dump())
check("item some da pasta", not any(v.startswith("Garota de Ipanema") for v in t))
check("aviso de item fora do app na pasta", any("fora do app" in v for v in t))
tap("Buscar")
sh("input text Ipanema"); time.sleep(3)
t = texts(dump())
check("item fora do app não aparece na busca", not any(v.startswith("Garota de Ipanema") for v in t), f"{[v for v in t if 'Ipanema' in v][:3]}")
sh("input keyevent 4"); time.sleep(1); sh("input keyevent 4"); time.sleep(2)
tap("Mais")
tap("Fora do app")
t = texts(dump())
check("lista Fora do app mostra o item", any("Garota de Ipanema" in v for v in t))
tap("Trazer de volta")
tap("Fechar", exact=True)
start(f'--es pasta "{A}/Casamentos"')
t = texts(dump())
check("trazer de volta faz o item reaparecer", any(v.startswith("Garota de Ipanema") for v in t))

# 4) Juntar em PDF
tap("Ver em lista")
tap("Perfect", long=True)
tap("Garota de Ipanema")
tap("Opções")
tap("Juntar em um PDF")
t = texts(dump())
check("diálogo Juntar em um PDF abre", any("Juntar em um PDF" in v for v in t))
tap("Criar PDF", wait=8)
t = texts(dump())
check("pergunta se quer abrir o PDF criado", any("Quer abrir agora" in v for v in t))
tap("Abrir", exact=True, wait=5)
sh(f"input tap {W // 2} {H // 2}"); time.sleep(2)
t = texts(dump())
pages = [v for v in t if re.fullmatch(r"\d+ / \d+", v)]
check("PDF criado abre no leitor", bool(pages), f"{pages} {[v for v in t if '+ 1' in v][:2]}")
made = sh(f'ls "{A}/Casamentos"')
check("arquivo criado na pasta", "+ 1.pdf" in made, made.replace("\n", " | ")[:200])

# 5) Adicionar a setlist com muitos setlists: barra de rolagem discreta à direita
start(f'--es pasta "{A}/Casamentos" --ei setlistsTeste 14')
tap("Perfect", long=True)
tap("Opções")
tap("Adicionar a setlist")
r = dump()
rows = [n for n in r.iter("node") if re.fullmatch(r"Teste \d+", (n.get("text") or ""))] if r is not None else []
check("diálogo com muitos setlists", len(rows) >= 3, f"{len(rows)} visíveis")
try:
    from PIL import Image
    import io
    png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=30).stdout
    img = Image.open(io.BytesIO(png)).convert("RGB")
    dens = int(re.findall(r"\d+", sh("wm density"))[-1]) / 160.0
    # Linha (Row) que contém o nome do setlist: a área clicável vai até a borda da lista.
    def row_of(n):
        best = None
        for p in r.iter("node"):
            if n in list(p.iter("node")) and p.get("clickable") == "true":
                best = p
        return best
    rb = [int(v) for v in re.findall(r"\d+", (row_of(rows[0]) or rows[0]).get("bounds"))]
    top = rb[1]
    right = rb[2]
    xs = range(int(right - 9 * dens), int(right - 1 * dens))
    def lum(px): return sum(px) / 3
    bg = lum(img.getpixel((int(right - 14 * dens), top + 2)))
    dark = sum(1 for y in range(top, top + int(60 * dens), 2) for x in xs if abs(lum(img.getpixel((x, y))) - bg) > 40)
    check("barra de rolagem aparece ao lado da lista", dark > 20, f"{dark} pontos da barra (fundo {bg:.0f})")
except Exception as e:
    check("barra de rolagem aparece ao lado da lista", False, f"não consegui medir: {e}")
print("==== FIM NOVAS")
