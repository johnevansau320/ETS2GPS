"""Parse ETS2 map data (.base sectors, defs, prefabs) out of the virtual file system.

Binary layouts follow the MIT-licensed ts-map project (dariowouters/ts-map), targeting
sector version >= 907 (game 1.5x). Older sectors are skipped with a warning.
"""
import math
import re
import struct

U32 = struct.Struct("<I")
I32 = struct.Struct("<i")
U64 = struct.Struct("<Q")
F32 = struct.Struct("<f")
U16 = struct.Struct("<H")
I16 = struct.Struct("<h")

LETTERS = "\0" + "0123456789abcdefghijklmnopqrstuvwxyz_"
LETTER_ID = {c: i for i, c in enumerate(LETTERS)}


def str_to_token(text):
    res = 0
    for i, ch in enumerate(text.lower()):
        res += (38 ** i) * LETTER_ID.get(ch, 0)
    return res


def token_to_str(tok):
    out = []
    while tok:
        tok, r = divmod(tok, 38)
        out.append(LETTERS[r])
    return "".join(out)


# ---------------------------------------------------------------- SII text ----

def sii_blocks(text):
    """Yield (class, name, props) for each `class : name { key: value }` block."""
    cls = name = None
    props = None
    for raw in text.split("\n"):
        line = raw.split("#", 1)[0].split("//", 1)[0].strip()
        if not line:
            continue
        if props is None:
            m = re.match(r"^([A-Za-z_]\w*)\s*:\s*([^\s{]+)\s*(\{)?\s*$", line)
            if m:
                cls, name = m.group(1), m.group(2)
                props = {}
                continue
            continue
        if line.startswith("}"):
            yield cls, name, props
            cls = name = props = None
            continue
        if line == "{":
            continue
        if ":" in line:
            k, v = line.split(":", 1)
            k, v = k.strip(), v.strip()
            if k.endswith("[]"):
                props.setdefault(k, []).append(v)
            elif re.search(r"\[\d+\]$", k):
                props.setdefault(re.sub(r"\[\d+\]$", "[]", k), []).append(v)
            else:
                props[k] = v


def sii_str(v):
    m = re.search(r'"([^"]*)"', v)
    return m.group(1) if m else v.strip()


def decode(b):
    try:
        return b.decode("utf-8")
    except UnicodeDecodeError:
        return b.decode("cp1252", "replace")


# -------------------------------------------------------------- definitions ----

class Defs:
    def __init__(self, fs, log=print):
        self.fs = fs
        self.log = log
        self.cities = {}      # token -> dict(name, country, id)
        self.roadlooks = {}   # token -> (lanes_left, lanes_right, offset)
        self.prefab_paths = {}  # token -> ppd path
        self.ferry_conns = []  # (port_a_token, port_b_token, minutes, distance)
        self._prefab_cache = {}
        self._load()

    def _files(self, folder, prefix):
        return [f for f in self.fs.listdir(folder)[1] if f.startswith(prefix)]

    def _load(self):
        fs = self.fs
        # cities: def/city.*.sii @include "city/x.sui"
        for f in self._files("def", "city"):
            txt = decode(fs.read("def/" + f))
            for m in re.finditer(r'@include\s+"([^"]+)"', txt):
                inc = m.group(1)
                data = fs.read(inc if inc.startswith("/") else "def/" + inc)
                if data is None:
                    continue
                for cls, name, p in sii_blocks(decode(data)):
                    if cls != "city_data":
                        continue
                    cid = name.split(".", 1)[1]
                    tok = str_to_token(cid)
                    if tok not in self.cities:
                        self.cities[tok] = {"id": cid, "name": sii_str(p.get("city_name", cid)),
                                            "country": p.get("country", ""),
                                            "loc": sii_str(p.get("city_name_localized", "")).strip("@")}
        for f in self._files("def/world", "road_look"):
            for cls, name, p in sii_blocks(decode(fs.read("def/world/" + f))):
                if cls != "road_look":
                    continue
                tok = str_to_token(name.split(".", 1)[1])
                if tok in self.roadlooks:
                    continue
                try:
                    off = float(p.get("road_offset", "0"))
                except ValueError:
                    off = 0.0
                ll, rr = p.get("lanes_left[]", []), p.get("lanes_right[]", [])
                drv = lambda ls: sum(1 for x in ls if x.startswith(("traffic_lane.road", "traffic_lane.slow_road")))  # noqa: E731
                # which way one-way traffic flows: decided by the template side / road body side, because
                # left-hand-traffic (UK/IE) templates list their forward lane under lanes_left
                side = ""
                if "template_right" in p and "template_left" not in p:
                    side = "R"
                elif "template_left" in p and "template_right" not in p:
                    side = "L"
                # road grade for route planning: 3 motorway, 2 expressway / dual carriageway, 1 wide local, 0 minor
                lanes = [x for x in ll + rr if x.startswith(("traffic_lane.road", "traffic_lane.slow_road"))]
                if any(".motorway" in x for x in lanes):
                    grade = 3
                elif any(".expressway" in x for x in lanes):
                    grade = 2
                elif len(lanes) >= 4:
                    grade = 1
                else:
                    grade = 0
                self.roadlooks[tok] = (len(ll), len(rr), off, drv(ll), drv(rr), side, grade)
        for f in self._files("def/world", "prefab"):
            for cls, name, p in sii_blocks(decode(fs.read("def/world/" + f))):
                if cls != "prefab_model" or "prefab_desc" not in p:
                    continue
                tok = str_to_token(name.split(".", 1)[1])
                self.prefab_paths.setdefault(tok, sii_str(p["prefab_desc"]))
        d_ = "def/ferry/connection"
        for f in self.fs.listdir(d_)[1]:
            if not f.endswith((".sii", ".sui")):
                continue
            for cls, name, p in sii_blocks(decode(self.fs.read(d_ + "/" + f))):
                parts = name.split(".")
                if cls == "ferry_connection" and len(parts) >= 3:
                    try:
                        mins = float(p.get("time", "60"))
                    except ValueError:
                        mins = 60.0
                    self.ferry_conns.append((str_to_token(parts[1]), str_to_token(parts[2]), mins))
        self.log("defs: %d cities, %d road looks, %d prefabs" % (
            len(self.cities), len(self.roadlooks), len(self.prefab_paths)))

    def prefab(self, tok):
        if tok in self._prefab_cache:
            return self._prefab_cache[tok]
        path = self.prefab_paths.get(tok)
        pf = None
        if path:
            data = self.fs.read(path)
            if data:
                try:
                    pf = parse_ppd(data)
                except Exception as e:  # noqa: BLE001
                    self.log("  ! prefab %s: %s" % (path, e))
        self._prefab_cache[tok] = pf
        return pf


def load_locale(fs, lang="en_us"):
    """key -> text from every locale/<lang>/*.sii|sui (later mounts override)."""
    out = {}
    pat = re.compile(r'key\[\]:\s*"([^"]*)"[^\n]*\n\s*val\[\]:\s*"((?:[^"\\]|\\.)*)"')
    for f in fs.listdir("locale/" + lang)[1]:
        if not f.endswith((".sii", ".sui")):
            continue
        d = fs.read("locale/%s/%s" % (lang, f))
        if not d:
            continue
        for k, v in pat.findall(decode(d)):
            out[k] = v
    return out


# ----------------------------------------------------------------- prefabs ----

class Prefab:
    pass


def parse_ppd(d):
    ver = I32.unpack_from(d, 0)[0]
    if ver < 0x15:
        raise ValueError("ppd version %d too old" % ver)
    pf = Prefab()
    node_count = I32.unpack_from(d, 4)[0]
    nav_count = I32.unpack_from(d, 8)[0]
    o = 0x08
    o += 0x0C
    spawn_count = I32.unpack_from(d, o)[0]
    o += 0x0C
    map_count = I32.unpack_from(d, o)[0]
    o += 0x04
    trig_count = I32.unpack_from(d, o)[0]
    if ver > 0x15:
        o += 0x04
    o += 0x08
    node_off = I32.unpack_from(d, o)[0]
    nav_off = I32.unpack_from(d, o + 4)[0]
    o += 0x10
    spawn_off = I32.unpack_from(d, o)[0]
    o += 0x10
    map_off = I32.unpack_from(d, o)[0]
    trig_off = I32.unpack_from(d, o + 4)[0]
    pf.version = ver
    pf.nav_count, pf.nav_off = nav_count, nav_off
    pf.nodes = []
    for i in range(node_count):
        b = node_off + i * 0x68
        x = F32.unpack_from(d, b + 0x10)[0]
        z = F32.unpack_from(d, b + 0x18)[0]
        rx = F32.unpack_from(d, b + 0x1C)[0]
        rz = F32.unpack_from(d, b + 0x24)[0]
        lanes = 0
        p = b + 0x24
        for _ in range(16):
            p += 4
            if I32.unpack_from(d, p)[0] != -1:
                lanes += 1
        pf.nodes.append((x, z, rx, rz, lanes))
    pf.spawns = []
    sbs = 0x24 if ver >= 24 else 0x20
    for i in range(spawn_count):
        b = spawn_off + i * sbs
        pf.spawns.append((F32.unpack_from(d, b)[0], F32.unpack_from(d, b + 8)[0],
                          U32.unpack_from(d, b + 0x1C)[0]))
    pf.mappoints = []
    for i in range(map_count):
        b = map_off + i * 0x30
        look = d[b + 1]
        lane_type = look & 0x0F
        lane_off_f = look >> 4
        cn = struct.unpack_from("<b", d, b + 4)[0]
        lane_count = {0: 1, 1: 2, 2: 4, 3: 6, 4: 8, 5: 5, 6: 7, 8: 3, 13: -1, 14: -2}.get(lane_type, 1)
        lane_off = {1: 1, 2: 2, 3: 5, 4: 10, 5: 15, 6: 20, 7: 25}.get(lane_off_f, 0)
        ctrl = {1: 0, 2: 1, 4: 2, 8: 3, 16: 4, 32: 5}.get(cn, -1)
        nav_flags = d[b + 5]
        x = F32.unpack_from(d, b + 8)[0]
        z = F32.unpack_from(d, b + 0x10)[0]
        ncount = I32.unpack_from(d, b + 0x14 + 4 * 6)[0]
        nb = [I32.unpack_from(d, b + 0x14 + 4 * k)[0] for k in range(max(0, min(ncount, 6)))]
        pf.mappoints.append({"x": x, "z": z, "lanes": lane_count, "off": lane_off,
                             "hidden": bool(nav_flags & 2), "ctrl": ctrl, "nb": nb})
    pf.triggers = []
    for i in range(trig_count):
        b = trig_off + i * 0x30
        if b + 0x30 > len(d):
            break
        pf.triggers.append((U32.unpack_from(d, b)[0], U64.unpack_from(d, b + 4)[0],
                            F32.unpack_from(d, b + 0x1C)[0], F32.unpack_from(d, b + 0x24)[0]))
    pf.raw = d
    pf.navcurves = parse_navcurves(pf, d) if nav_count else []
    return pf


def parse_navcurves(pf, d):
    """Nav curve block (0x80 bytes): layout per TruckLib docs."""
    out = []
    for i in range(pf.nav_count):
        b = pf.nav_off + i * 0x80
        if b + 0x80 > len(d):
            break
        sx, sy, sz = struct.unpack_from("<fff", d, b + 0x08)
        ex, ey, ez = struct.unpack_from("<fff", d, b + 0x14)
        srx, sry, srz = struct.unpack_from("<fff", d, b + 0x20)
        erx, ery, erz = struct.unpack_from("<fff", d, b + 0x2C)
        flags = U32.unpack_from(d, b)[0]
        nxt = [I32.unpack_from(d, b + 0x50 + 4 * k)[0] for k in range(4)]
        prv = [I32.unpack_from(d, b + 0x60 + 4 * k)[0] for k in range(4)]
        out.append({"flags": flags, "s": (sx, sz), "e": (ex, ez), "sr": (srx, srz), "er": (erx, erz),
                    "next": nxt, "prev": prv})
    return out


# ----------------------------------------------------------------- sectors ----

class Sector:
    __slots__ = ("roads", "prefabs", "cities", "companies", "ferries", "overlays", "nodes", "ok", "version")


def _quad(d, o):
    mc = U16.unpack_from(d, o)[0]
    p = o + 2 + 0x0A * mc
    cc = U16.unpack_from(d, p)[0]
    p += 2 + 4 * cc + 4
    sc = I32.unpack_from(d, p)[0]
    p += 4 + 4 * sc
    oc = I32.unpack_from(d, p)[0]
    p += 4 + 0x10 * oc
    nc = I32.unpack_from(d, p)[0]
    return p + 4 + 0x10 * nc - o


VEG = 0x14

T_TERRAIN, T_BUILDING, T_ROAD, T_PREFAB, T_MODEL, T_COMPANY, T_SERVICE, T_CUTPLANE = 1, 2, 3, 4, 5, 6, 7, 8
T_CITY, T_MAPOVERLAY, T_FERRY, T_GARAGE, T_TRIGGER, T_FUELPUMP, T_ROADSIDE = 12, 18, 19, 22, 34, 35, 36
T_BUSSTOP, T_TRAFFICRULE, T_BEZIER, T_TRAJECTORY, T_MAPAREA, T_CURVE, T_CUTSCENE, T_VISAREA = \
    37, 38, 39, 41, 42, 44, 46, 48


def parse_sector(d, defs):
    sec = Sector()
    sec.roads, sec.prefabs, sec.cities, sec.nodes, sec.ok = [], [], [], {}, False
    sec.companies = []
    sec.ferries = []
    sec.overlays = []
    ver = I32.unpack_from(d, 0)[0]
    sec.version = ver
    if ver < 884:
        return sec
    count = U32.unpack_from(d, 0x10)[0]
    o = 0x14
    for _ in range(count):
        typ = U32.unpack_from(d, o)[0]
        f = o + 0x34
        uid = U64.unpack_from(d, o + 4)[0]
        x = F32.unpack_from(d, o + 0x0C)[0]
        z = F32.unpack_from(d, o + 0x14)[0]
        if typ == T_TERRAIN:
            toff = 0xEE if ver >= 903 else 0xEA
            n = I32.unpack_from(d, f + 0x05 + toff)[0]
            e = f + 0x05 + toff + 4 + VEG * n
            e += _quad(d, e)
            e += _quad(d, e)
            e += 0x20
        elif typ == T_BUILDING:
            n = I32.unpack_from(d, f + 0x05 + 0x2C)[0]
            e = f + 0x05 + 0x2C + 4 + 4 * n
        elif typ == T_ROAD:
            hidden = bool(d[f + 3] & 2)
            secret = bool(d[f + 2] & 1)
            look = U64.unpack_from(d, f + 9)[0]
            p = f + 9 + 8 + (0xB4 if ver >= 895 else 0xA4)
            su = U64.unpack_from(d, p)[0]
            eu = U64.unpack_from(d, p + 8)[0]
            e = p + 8 + 8 + 4
            if look in defs.roadlooks:
                sec.roads.append((uid, su, eu, look, secret, d[f + 6], hidden))
        elif typ == T_PREFAB:
            hidden = bool(d[f + 2] & 2)
            secret = bool((d[f] >> 5) & 1)
            ptok = U64.unpack_from(d, f + 5)[0]
            p = f + 5 + 0x10
            apc = I32.unpack_from(d, p)[0]
            p += 4 + 8 * apc
            nc = I32.unpack_from(d, p)[0]
            p += 4
            nodes = [U64.unpack_from(d, p + 8 * k)[0] for k in range(nc)]
            p += 8 * nc
            ci = I32.unpack_from(d, p)[0]
            p += 4 + 8 * ci + 8
            origin = d[p]
            e = p + 2 + nc * 0x0C + 8
            sec.prefabs.append((uid, ptok, nodes, origin, secret, d[f + 1], hidden))
        elif typ == T_MODEL:
            if ver < 895:
                raise ValueError("model item layout for sector v%d not supported" % ver)
            n = I32.unpack_from(d, f + 0x05 + 0x18)[0]
            e = f + 0x05 + 0x18 + 4 + 8 * n + 0x24
        elif typ == T_COMPANY:
            if ver >= 903:
                sec.companies.append((U64.unpack_from(d, f + 0x15)[0], U64.unpack_from(d, f + 5)[0], x, z))
            else:
                sec.companies.append((U64.unpack_from(d, f + 5)[0], U64.unpack_from(d, f + 0xD)[0], x, z))
            p = f + 0x25
            n = I32.unpack_from(d, p)[0]
            if ver >= 900:
                e = p + 4 + 0x0C * n
            else:
                for _k in range(5):
                    p += 4 + 8 * n
                    n = I32.unpack_from(d, p)[0]
                e = p + 4 + 8 * n
        elif typ in (T_SERVICE, T_FUELPUMP):
            n = I32.unpack_from(d, f + 0x05 + 0x10)[0]
            e = f + 0x05 + 0x10 + 4 + 8 * n
        elif typ == T_GARAGE:
            n = I32.unpack_from(d, f + 0x05 + 0x1C)[0]
            e = f + 0x05 + 0x1C + 4 + 8 * n
        elif typ == T_CUTPLANE:
            n = I32.unpack_from(d, f + 5)[0]
            e = f + 5 + 4 + 8 * n
        elif typ == T_CITY:
            hidden = bool(d[f] & 1)
            ctok = U64.unpack_from(d, f + 5)[0]
            e = f + 0x1D
            if not hidden and ctok in defs.cities:
                sec.cities.append((ctok, x, z))
        elif typ == T_MAPOVERLAY:
            tok = U64.unpack_from(d, f + 5)[0]
            if ver >= 898:
                is_parking = (d[f] & 0x0F) == 1
            else:
                is_parking = bool(d[f + 2] & 1) and tok == 0
            if is_parking and d[f] != 255:
                sec.overlays.append((3, x, z))
            e = f + 0x15
        elif typ == T_FERRY:
            sec.ferries.append((U64.unpack_from(d, f + 5)[0], U64.unpack_from(d, f + 0xD)[0],
                                U64.unpack_from(d, f + 0x15)[0], d[f] != 0, x, z))
            e = f + 0x29
        elif typ == T_TRIGGER:
            e = _trigger(d, f)
        elif typ == T_ROADSIDE:
            e = _roadside(d, f) if ver >= 906 else _roadside875(d, f)
        elif typ == T_BUSSTOP:
            e = f + 0x1D
        elif typ == T_TRAFFICRULE:
            tc = I32.unpack_from(d, f + 5)[0]
            p = f + 5 + 4 + 8 * tc
            nc = I32.unpack_from(d, p)[0]
            e = p + 4 + 8 * nc + 0x0C
        elif typ == T_BEZIER:
            n = I32.unpack_from(d, f + 0x05 + 0xF1)[0]
            e = f + 0x05 + 0xF1 + 4 + VEG * n
            e += _quad(d, e)
        elif typ == T_TRAJECTORY:
            nc = I32.unpack_from(d, f + 5)[0]
            if ver >= 895:
                p = f + 5 + 4 + 8 * nc + 8
                rr = I32.unpack_from(d, p)[0]
                p += 4 + 0x1C * rr
                cp = I32.unpack_from(d, p)[0]
                p += 4 + 0x10 * cp
                tg = I32.unpack_from(d, p)[0]
                e = p + 4 + 8 * tg
            else:
                p = f + 5 + 4 + 8 * nc + 0x0C
                rr = I32.unpack_from(d, p)[0]
                p += 4 + 0x1C * rr
                cp = I32.unpack_from(d, p)[0]
                e = p + 4 + 0x10 * cp + 4
        elif typ == T_MAPAREA:
            nc = I32.unpack_from(d, f + 5)[0]
            e = f + 5 + 4 + 8 * nc + 4
        elif typ == T_CURVE and ver < 907:
            hc = I32.unpack_from(d, f + 0x05 + 0x6C)[0]
            e = f + 0x05 + 0x6C + 4 + 4 * hc
        elif typ == T_CURVE:
            mask = I32.unpack_from(d, f + 0x05 + 0x20 + 0x04)[0]
            p = f + 0x05 + 0x20 + 0x04 + 4
            for _k in range(bin(mask & 0xFFFFFFFF).count("1")):
                p += 0x4C
                hc = I32.unpack_from(d, p)[0]
                p += 4 + 4 * hc + 0x14
            e = p
        elif typ == T_CUTSCENE:
            e = _cutscene(d, f)
        elif typ == T_VISAREA:
            if ver < 891:
                raise ValueError("visibility area for sector v%d not supported" % ver)
            n = I32.unpack_from(d, f + 0x05 + 0x10)[0]
            e = f + 0x05 + 0x10 + 4 + 8 * n
        else:
            raise ValueError("unknown item type %d at 0x%x" % (typ, o))
        o = e
    nc = I32.unpack_from(d, o)[0]
    for i in range(nc):
        b = o + 4 + 0x38 * i
        nuid = U64.unpack_from(d, b)[0]
        nx = I32.unpack_from(d, b + 8)[0] / 256.0
        nz = I32.unpack_from(d, b + 0x10)[0] / 256.0
        rx = F32.unpack_from(d, b + 0x14)[0]
        rz = F32.unpack_from(d, b + 0x1C)[0]
        rot = math.fmod(math.pi - math.atan2(rz, rx), math.pi) * 2
        sec.nodes[nuid] = (nx, nz, rot)
    o += 0x38 * nc + 4
    if ver >= 891:
        n = I32.unpack_from(d, o)[0]
        o += 4 + 8 * n
    sec.ok = (o == len(d))
    return sec


def _trigger(d, f):
    tc = I32.unpack_from(d, f + 5)[0]
    p = f + 5 + 4 + 8 * tc
    nc = I32.unpack_from(d, p)[0]
    p += 4 + 8 * nc
    ac = I32.unpack_from(d, p)[0]
    p += 4
    for _ in range(ac):
        p += 8
        ho = I32.unpack_from(d, p)[0]
        p += 4
        if ho < 0:
            continue
        p += 4 * ho
        pc = I32.unpack_from(d, p)[0]
        p += 4
        for _j in range(pc):
            ln = I32.unpack_from(d, p)[0]
            p += 8 + ln
        tt = I32.unpack_from(d, p)[0]
        p += 4 + tt * 8 + 8
    if nc == 1:
        p += 4
    return p


def _roadside(d, f):
    p = f + 0x05 + 0x20
    bc = struct.unpack_from("<b", d, p)[0]
    p += 1 + bc * 0x18
    otl = I32.unpack_from(d, p)[0]
    p += 8
    if otl != 0:
        p += otl
        boc = I32.unpack_from(d, p)[0]
        p += 4
        for _ in range(boc):
            p += 8
            fl = d[p]
            p += 1
            if fl & 1:
                p += 2
            if fl & 2:
                p += 8
        soc = I32.unpack_from(d, p)[0]
        p += 4
        for _ in range(soc):
            p += 0x0C
            ac = I32.unpack_from(d, p)[0]
            p += 4
            for _j in range(ac):
                it = I16.unpack_from(d, p)[0]
                p += 6
                if it == 5:
                    tl = I32.unpack_from(d, p)[0]
                    p += 8 + tl
                elif it == 6:
                    p += 8
                elif it == 1:
                    p += 1
                else:
                    p += 4
    return p


def _roadside875(d, f):
    p = f + 0x05 + 0x20
    bc = struct.unpack_from("<b", d, p)[0]
    p += 1 + bc * 0x18
    otl = I32.unpack_from(d, p)[0]
    if otl != 0:
        p += 4 + otl
    soc = I32.unpack_from(d, p + 4)[0]
    p += 8
    for _ in range(soc):
        p += 0x0C
        ac = I32.unpack_from(d, p)[0]
        p += 4
        for _j in range(ac):
            it = I16.unpack_from(d, p)[0]
            p += 6
            if it == 5:
                tl = I32.unpack_from(d, p)[0]
                p += 8 + tl
            elif it == 6:
                p += 8
            elif it == 1:
                p += 1
            else:
                p += 4
    return p


def _cutscene(d, f):
    tc = I32.unpack_from(d, f + 5)[0]
    p = f + 5 + 4 + 8 * tc + 8
    ac = I32.unpack_from(d, p)[0]
    p += 4
    for _ in range(ac):
        npc = I32.unpack_from(d, p)[0]
        p += 4 + 4 * npc
        spc = I32.unpack_from(d, p)[0]
        p += 4
        for _j in range(spc):
            tl = I32.unpack_from(d, p)[0]
            p += 8 + tl
        tt = I32.unpack_from(d, p)[0]
        p += 4 + 8 * tt + 8
    return p
