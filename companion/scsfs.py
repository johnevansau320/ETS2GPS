"""Minimal reader for SCS HashFS (.scs v1/v2) archives and zip-based mods.

Format knowledge ported from the MIT-licensed ts-map project (dariowouters/ts-map).
"""
import os
import struct
import zipfile
import zlib

M = (1 << 64) - 1
K0 = 0xC3A5C85C97CB3127
K1 = 0xB492B66FBE98F273
K2 = 0x9AE16A3B2F90404F
K3 = 0xC949D7C7509E6557


def _rot(v, s):
    return v if s == 0 else ((v >> s) | (v << (64 - s))) & M


def _f64(s, p):
    return struct.unpack_from("<Q", s, p)[0]


def _f32(s, p):
    return struct.unpack_from("<I", s, p)[0]


def _h128(u, v):
    mul = 0x9DDFEA08EB382D69
    a = ((u ^ v) * mul) & M
    a ^= a >> 47
    b = ((v ^ a) * mul) & M
    b ^= b >> 47
    return (b * mul) & M


def _smix(v):
    return v ^ (v >> 47)


def _weak(w, x, y, z, a, b):
    a = (a + w) & M
    b = _rot((b + a + z) & M, 21)
    c = a
    a = (a + x + y) & M
    b = (b + _rot(a, 44)) & M
    return (a + z) & M, (b + c) & M


def _weak_s(s, p, a, b):
    return _weak(_f64(s, p), _f64(s, p + 8), _f64(s, p + 16), _f64(s, p + 24), a, b)


def city_hash64(s):
    if isinstance(s, str):
        s = s.encode("utf-8")
    n = len(s)
    if n <= 16:
        if n > 8:
            a = _f64(s, 0)
            b = _f64(s, n - 8)
            return _h128(a, _rot((b + n) & M, n)) ^ b
        if n >= 4:
            a = _f32(s, 0)
            return _h128((n + (a << 3)) & M, _f32(s, n - 4))
        if n > 0:
            y = s[0] + (s[n >> 1] << 8)
            z = n + (s[n - 1] << 2)
            return (_smix(((y * K2) & M) ^ ((z * K3) & M)) * K2) & M
        return K2
    if n <= 32:
        a = (_f64(s, 0) * K1) & M
        b = _f64(s, 8)
        c = (_f64(s, n - 8) * K2) & M
        d = (_f64(s, n - 16) * K0) & M
        return _h128((_rot((a - b) & M, 43) + _rot(c, 30) + d) & M,
                     (a + _rot(b ^ K3, 20) - c + n) & M)
    if n <= 64:
        z = _f64(s, 24)
        a = (_f64(s, 0) + (n + _f64(s, n - 16)) * K0) & M
        b = _rot((a + z) & M, 52)
        c = _rot(a, 37)
        a = (a + _f64(s, 8)) & M
        c = (c + _rot(a, 7)) & M
        a = (a + _f64(s, 16)) & M
        vf = (a + z) & M
        vs = (b + _rot(a, 31) + c) & M
        a = (_f64(s, 16) + _f64(s, n - 32)) & M
        z = _f64(s, n - 8)
        b = _rot((a + z) & M, 52)
        c = _rot(a, 37)
        a = (a + _f64(s, n - 24)) & M
        c = (c + _rot(a, 7)) & M
        a = (a + _f64(s, n - 16)) & M
        wf = (a + z) & M
        ws = (b + _rot(a, 31) + c) & M
        r = _smix(((vf + ws) * K2 + (wf + vs) * K0) & M)
        return (_smix((r * K0 + vs) & M) * K2) & M
    x = _f64(s, n - 40)
    y = (_f64(s, n - 16) + _f64(s, n - 56)) & M
    z = _h128((_f64(s, n - 48) + n) & M, _f64(s, n - 24))
    v = _weak_s(s, n - 64, n, z)
    w = _weak_s(s, n - 32, (y + K1) & M, x)
    x = (x * K1 + _f64(s, 0)) & M
    pos = 0
    ln = (n - 1) & ~63
    while True:
        x = (_rot((x + y + v[0] + _f64(s, pos + 8)) & M, 37) * K1) & M
        y = (_rot((y + v[1] + _f64(s, pos + 48)) & M, 42) * K1) & M
        x ^= w[1]
        y = (y + v[0] + _f64(s, pos + 40)) & M
        z = (_rot((z + w[0]) & M, 33) * K1) & M
        v = _weak_s(s, pos, (v[1] * K1) & M, (x + w[0]) & M)
        w = _weak_s(s, pos + 32, (z + w[1]) & M, (y + _f64(s, pos + 16)) & M)
        z, x = x, z
        pos += 64
        ln -= 64
        if ln == 0:
            break
    return _h128((_h128(v[0], w[0]) + _smix(y) * K1 + z) & M,
                 (_h128(v[1], w[1]) + x) & M)


class _HashArchive:
    def __init__(self, path):
        self.path = path
        self.f = open(path, "rb")
        self.version = 0
        self.entries = {}  # hash -> (offset, size, csize, is_dir, comp_method)
        self._parse()

    def _parse(self):
        f = self.f
        hdr = f.read(0x14)
        magic, ver, salt, method, count, start = struct.unpack("<IHHIII", hdr)
        if magic != 0x23534353 or method != 0x59544943:
            raise ValueError("not a CITY hashfs archive")
        self.version = ver
        if ver == 1:
            f.seek(start)
            raw = f.read(count * 0x20)
            for i in range(count):
                h, off, flags, crc, size, csize = struct.unpack_from("<QQIIII", raw, i * 0x20)
                self.entries[h] = (off, size, csize, bool(flags & 1), 1 if (flags & 2) else 0)
        elif ver == 2:
            f.seek(0x10)
            et_size, md_count, md_size = struct.unpack("<III", f.read(12))
            et_off, md_off = struct.unpack("<qq", f.read(16))
            f.seek(et_off)
            ent = zlib.decompress(f.read(et_size))
            f.seek(md_off)
            md = zlib.decompress(f.read(md_size))
            for i in range(count):
                o = i * 0x10
                h = struct.unpack_from("<Q", ent, o)[0]
                mstart = struct.unpack_from("<i", ent, o + 8)[0]
                mcount = struct.unpack_from("<H", ent, o + 12)[0]
                flags = struct.unpack_from("<I", ent, o + 12)[0]
                is_dir = bool((flags >> 16) & 1)
                for j in range(mcount):
                    m0 = struct.unpack_from("<I", md, (mstart + j) * 4)[0]
                    typ = m0 >> 24
                    if typ in (0x80, 0x81, 0x82, 0x83, 0x84):
                        ro = (m0 & 0xFFFFFF) * 4
                        d0, d1, d2, d3 = struct.unpack_from("<IIII", md, ro)
                        csize = d0 & 0xFFFFFFF
                        method = d0 >> 28
                        size = d1 & 0xFFFFFFF
                        self.entries[h] = (d3 * 16, size, csize, is_dir, method)
        else:
            raise ValueError("unsupported hashfs version %d" % ver)

    def read(self, h):
        off, size, csize, _, method = self.entries[h]
        self.f.seek(off)
        data = self.f.read(csize)
        if size == csize:
            return data
        if method == 1:
            return zlib.decompress(data)
        raise ValueError("unsupported compression %d" % method)


class _ZipArchive:
    def __init__(self, path):
        self.path = path
        self.version = 0
        self.z = zipfile.ZipFile(path)
        self.names = {}
        for n in self.z.namelist():
            self.names[city_hash64(n.strip("/"))] = n
        self.entries = {h: (0, 0, 0, False, 0) for h in self.names}

    def read(self, h):
        return self.z.read(self.names[h])


def _list_dirs_from_names(names):
    """Build {dirpath: (subdirs, files)} from a list of zip member names."""
    out = {}
    for n in names:
        parts = n.strip("/").split("/")
        for i in range(len(parts)):
            d = "/".join(parts[:i])
            sub, fl = out.setdefault(d, (set(), set()))
            if i == len(parts) - 1 and not n.endswith("/"):
                fl.add(parts[i])
            else:
                sub.add(parts[i])
    return out


class _DirArchive:
    """An unpacked mod folder."""

    def __init__(self, path):
        self.path = path
        self.version = 0
        self.names = {}
        self.rel = []
        for root, dirs, files in os.walk(path):
            for fn in files:
                full = os.path.join(root, fn)
                rel = os.path.relpath(full, path).replace("\\", "/")
                self.names[city_hash64(rel)] = full
                self.rel.append(rel)
        self.entries = {h: (0, 0, 0, False, 0) for h in self.names}

    def read(self, h):
        with open(self.names[h], "rb") as f:
            return f.read()


class FileSystem:
    """Union file system: later-mounted archives override earlier ones."""

    def __init__(self):
        self.files = {}  # hash -> archive
        self.dirs = {}  # hash -> (subdirs, files)
        self.mounted = []

    def mount(self, path):
        try:
            if os.path.isdir(path):
                arc = _DirArchive(path)
            else:
                with open(path, "rb") as fh:
                    magic = fh.read(4)
                arc = _HashArchive(path) if magic == b"SCS#" else _ZipArchive(path)
        except Exception as e:  # noqa: BLE001
            print("  ! could not mount %s: %s" % (os.path.basename(path), e))
            return False
        if isinstance(arc, (_ZipArchive, _DirArchive)):
            names = arc.z.namelist() if isinstance(arc, _ZipArchive) else arc.rel
            for d, (sub, fl) in _list_dirs_from_names(names).items():
                s, f = self.dirs.setdefault(city_hash64(d), (set(), set()))
                s |= sub
                f |= fl
        for h, (_, _, _, is_dir, _) in arc.entries.items():
            if is_dir:
                try:
                    d = arc.read(h)
                except Exception:  # noqa: BLE001
                    continue
                sub, fl = self.dirs.setdefault(h, (set(), set()))
                self._parse_dir(d, arc.version, sub, fl)
            else:
                self.files[h] = arc
        self.mounted.append(path)
        return True

    @staticmethod
    def _parse_dir(d, version, sub, fl):
        if version == 1:
            for line in d.decode("utf-8", "ignore").split("\n"):
                if line.startswith("*"):
                    sub.add(line[1:])
                elif line:
                    fl.add(line)
            return
        if len(d) < 4:
            return
        n = struct.unpack_from("<I", d, 0)[0]
        p = 4 + n
        for j in range(n):
            ln = d[4 + j]
            name = d[p:p + ln].decode("utf-8", "ignore")
            p += ln
            if name.startswith("/"):
                sub.add(name[1:])
            else:
                fl.add(name)

    def exists(self, path):
        return city_hash64(path.strip("/")) in self.files

    def read(self, path):
        h = city_hash64(path.strip("/"))
        arc = self.files.get(h)
        return None if arc is None else arc.read(h)

    def listdir(self, path):
        e = self.dirs.get(city_hash64(path.strip("/")))
        return (sorted(e[0]), sorted(e[1])) if e else ([], [])
