"""Locate the ETS2 install, active mods and mount them in game load order."""
import ctypes
import fnmatch
import glob
import json
import os
import re
import urllib.request
from ctypes import wintypes

from scsfs import FileSystem

SERVER = "http://127.0.0.1:31377"
APPID = "227300"
SKIP = ("base_vehicle", "dlc_flags_", "dlc_lunar", "dlc_christmas", "dlc_halloween", "dlc_valentine",
        "dlc_pink_ribbon", "dlc_prehistoric", "dlc_fantasy", "dlc_space", "dlc_toys", "dlc_pirates",
        "dlc_rocket", "dlc_streetart", "dlc_super_stripes", "dlc_lines")


def _known_folder(guid):
    try:
        class GUID(ctypes.Structure):
            _fields_ = [("a", wintypes.DWORD), ("b", wintypes.WORD), ("c", wintypes.WORD), ("d", ctypes.c_ubyte * 8)]
        import uuid
        u = uuid.UUID(guid)
        g = GUID(u.time_low, u.time_mid, u.time_hi_version,
                 (ctypes.c_ubyte * 8)(*u.bytes[8:]))
        out = ctypes.c_wchar_p()
        ctypes.windll.shell32.SHGetKnownFolderPath(ctypes.byref(g), 0, None, ctypes.byref(out))
        return out.value
    except Exception:  # noqa: BLE001
        return None


def documents_dir():
    d = _known_folder("FDD39AD0-238F-46AF-ADB4-6C85480369C7")
    cands = [d] if d else []
    cands += [os.path.expanduser("~/Documents"), os.path.expanduser("~/OneDrive/Documents")]
    for c in cands:
        if c and os.path.isdir(os.path.join(c, "Euro Truck Simulator 2")):
            return os.path.join(c, "Euro Truck Simulator 2")
    return None


def steam_libraries():
    libs = []
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam")
        root = winreg.QueryValueEx(k, "SteamPath")[0]
        libs.append(root)
        vdf = os.path.join(root, "steamapps", "libraryfolders.vdf")
        if os.path.exists(vdf):
            for m in re.finditer(r'"path"\s+"([^"]+)"', open(vdf, encoding="utf-8", errors="ignore").read()):
                libs.append(m.group(1).replace("\\\\", "\\"))
    except Exception:  # noqa: BLE001
        pass
    for d in "CDEFGH":
        libs.append(d + r":\SteamLibrary")
        libs.append(d + r":\Steam")
    out = []
    for l in libs:
        l = os.path.normpath(l)
        if l not in out and os.path.isdir(l):
            out.append(l)
    return out


def find_game_dir(explicit=None):
    if explicit:
        return explicit
    for lib in steam_libraries():
        g = os.path.join(lib, "steamapps", "common", "Euro Truck Simulator 2")
        if os.path.isdir(g):
            return g
    raise SystemExit("Could not find Euro Truck Simulator 2; pass --game-dir")


def http_json(path, timeout=3):
    with urllib.request.urlopen(SERVER + path, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def active_mod_packages(log):
    """Mods from the TruckSim GPS server, lowest priority first (= mount order)."""
    try:
        st = http_json("/api/game/state?game=ets2")
        prof = st.get("activeProfile")
        if not prof:
            profs = http_json("/api/game/profiles?game=ets2").get("profiles", [])
            if not profs:
                return None
            prof = profs[0]
            log("  game not running; using first profile %s" % prof.get("name"))
        else:
            log("  active profile: %s" % prof.get("name"))
        q = "/api/game/profile-mods?game=ets2&id=%s&type=%s" % (prof["id"], prof["type"])
        mods = http_json(q).get("mods", [])
        return [m for m in reversed(mods)]
    except Exception as e:  # noqa: BLE001
        log("  TruckSim GPS server not reachable (%s)" % e)
        return None


def _game_version(fs):
    try:
        v = http_json("/api/ets2/telemetry").get("game", {}).get("gameProductVersion")
        if v:
            return v
    except Exception:  # noqa: BLE001
        pass
    d = fs.read("version.txt")
    return d.decode("utf-8", "ignore").strip() if d else ""


def resolve_package(pkg, docs, libs, game_version):
    """Return a list of paths (mount order) for one profile mod entry."""
    wid = pkg.get("workshopId")
    if wid:
        for lib in libs:
            d = os.path.join(lib, "steamapps", "workshop", "content", APPID, str(wid))
            if os.path.isdir(d):
                return _workshop_paths(d, game_version)
        return []
    name = pkg.get("package", "")
    if docs:
        for ext in (".scs", ".zip"):
            p = os.path.join(docs, "mod", name + ext)
            if os.path.exists(p):
                return [p]
        p = os.path.join(docs, "mod", name)
        if os.path.isdir(p):
            return [p]
    return []


def _find_named(d, base):
    for ext in (".scs", ".zip", ""):
        p = os.path.join(d, base + ext)
        if os.path.exists(p):
            return p
    return None


def _vkey(v):
    return tuple(int(x) for x in re.findall(r"\d+", v)[:3])


def _workshop_paths(d, game_version):
    vs = os.path.join(d, "versions.sii")
    if not os.path.exists(vs):
        return [os.path.join(d, f) for f in sorted(os.listdir(d)) if f.endswith((".scs", ".zip"))]
    txt = open(vs, encoding="utf-8", errors="ignore").read()
    universal, versioned = [], []
    for b in re.findall(r"package_version_info\s*:\s*\S+\s*\{(.*?)\}", txt, re.S):
        name = re.search(r'package_name:\s*"([^"]+)"', b).group(1)
        compat = re.findall(r'compatible_versions\[\]:\s*"([^"]+)"', b)
        p = _find_named(d, name)
        if not p:
            continue
        if not compat:
            universal.append(p)
        elif game_version:
            if any(fnmatch.fnmatch(game_version, c) or fnmatch.fnmatch(game_version + ".", c) for c in compat):
                versioned.append((0, p))
        else:  # unknown game version: take the newest package
            versioned.append((max(_vkey(c.replace("*", "0")) for c in compat), p))
    if versioned and not game_version:
        versioned = [max(versioned)]
    return universal + [p for _, p in versioned]


def mount_everything(game_dir, log, use_mods=True, extra_mods=()):
    fs = FileSystem()
    files = sorted(glob.glob(os.path.join(game_dir, "*.scs")))
    n = 0
    for f in files:
        base = os.path.basename(f)
        if base.startswith(SKIP):
            continue
        if fs.mount(f):
            n += 1
    log("mounted %d game archives" % n)
    version = _game_version(fs)
    log("game version: %s" % version)
    mods = []
    if use_mods:
        pk = active_mod_packages(log)
        docs = documents_dir()
        libs = steam_libraries()
        if pk is None:
            log("  falling back to every .scs/.zip/folder in Documents/mod (alphabetical)")
            mdir = os.path.join(docs, "mod") if docs else None
            if mdir and os.path.isdir(mdir):
                mods += [os.path.join(mdir, x) for x in sorted(os.listdir(mdir))]
        else:
            for p in pk:
                paths = resolve_package(p, docs, libs, version)
                if not paths:
                    log("  ! mod not found on disk: %s" % (p.get("displayName") or p.get("package")))
                mods += paths
    mods += list(extra_mods)
    for m in mods:
        if fs.mount(m):
            log("  + mod %s" % os.path.basename(m))
    return fs, version
