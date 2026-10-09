"""Load etsmap.bin, route between two cities with the same algorithm the app uses, render a PNG."""
import heapq
import math
import struct
import sys
import zlib


def load(path):
    d = zlib.decompress(open(path, "rb").read())
    o = 0
    magic, ver, _, minx, minz, maxx, maxz = struct.unpack_from("<4sHH4f", d, o)
    o += 24
    n = struct.unpack_from("<I", d, o)[0]
    o += 4
    nodes = [struct.unpack_from("<ff", d, o + 8 * i) for i in range(n)]
    o += 8 * n
    jn = struct.unpack_from("<I", d, o)[0]
    o += 4
    junc = {}
    for _ in range(jn):
        jid, cnt, rb = struct.unpack_from("<IBB", d, o)
        o += 6
        junc[jid] = (cnt, rb)
    en = struct.unpack_from("<I", d, o)[0]
    o += 4
    edges = []
    for _ in range(en):
        a, b, fl, cls, sp, wq, jid, npts = struct.unpack_from("<IIBBBBIB", d, o)
        o += 17
        qx = int(math.floor(struct.unpack("<f", struct.pack("<f", nodes[a][0]))[0] * 4 + 0.5))
        qz = int(math.floor(struct.unpack("<f", struct.pack("<f", nodes[a][1]))[0] * 4 + 0.5))
        pts = []
        for k in range(npts):
            dx, dz = struct.unpack_from("<hh", d, o)
            o += 4
            qx += dx
            qz += dz
            pts.append((qx / 4.0, qz / 4.0))
        edges.append((a, b, fl, cls, sp * 5, pts, jid, wq / 4.0))
    cn = struct.unpack_from("<I", d, o)[0]
    o += 4
    cities = []
    for _ in range(cn):
        ss = []
        for _k in range(2):
            ln = d[o]
            ss.append(d[o + 1:o + 1 + ln].decode("utf-8"))
            o += 1 + ln
        x, z = struct.unpack_from("<ff", d, o)
        o += 8
        cities.append((ss[0], ss[1], x, z))
    return {"bbox": (minx, minz, maxx, maxz), "nodes": nodes, "edges": edges, "junc": junc,
            "cities": cities}


def plen(pts):
    return sum(math.hypot(pts[i + 1][0] - pts[i][0], pts[i + 1][1] - pts[i][1]) for i in range(len(pts) - 1))


class Router:
    def __init__(self, m):
        self.m = m
        self.dir = []  # directed edges: (a, b, pts, speed, jid, cost)
        for a, b, fl, cls, sp, pts, jid, wd in m["edges"]:
            ln = plen(pts)
            self.dir.append((a, b, pts, sp, jid, ln))
            if fl & 1:
                self.dir.append((b, a, pts[::-1], sp, jid, ln))
        self.out = {}
        for i, e in enumerate(self.dir):
            self.out.setdefault(e[0], []).append(i)

    def cost(self, i):
        e = self.dir[i]
        c = e[5] / (max(e[3], 10) / 3.6)
        if e[4]:
            c += 4.0  # junction time penalty
        return c

    def route(self, src_node, dst_node):
        dist = {}
        prev = {}
        pq = []
        for i in self.out.get(src_node, []):
            c = self.cost(i)
            dist[i] = c
            prev[i] = -1
            heapq.heappush(pq, (c, i))
        goal = None
        while pq:
            c, i = heapq.heappop(pq)
            if c > dist.get(i, 1e30):
                continue
            e = self.dir[i]
            if e[1] == dst_node:
                goal = i
                break
            for j in self.out.get(e[1], []):
                ej = self.dir[j]
                if ej[1] == e[0] and ej[2] and abs(ej[2][0][0] - e[2][-1][0]) < 0.5 and \
                        abs(ej[2][-1][0] - e[2][0][0]) < 0.5 and not (e[4] or ej[4]):
                    continue  # no immediate U-turn on the same road
                nc = c + self.cost(j)
                if nc < dist.get(j, 1e30):
                    dist[j] = nc
                    prev[j] = i
                    heapq.heappush(pq, (nc, j))
        if goal is None:
            return None
        path = []
        i = goal
        while i != -1:
            path.append(i)
            i = prev[i]
        return path[::-1], dist[goal]

    def nearest_node(self, x, z):
        best, bd = -1, 1e30
        for i, (nx, nz) in enumerate(self.m["nodes"]):
            d = (nx - x) ** 2 + (nz - z) ** 2
            if d < bd:
                best, bd = i, d
        return best


# ------------------------------------------------------------------ rendering --

def write_png(path, w, h, rgb):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += rgb[y * w * 3:(y + 1) * w * 3]

    def chunk(t, data):
        c = struct.pack(">I", len(data)) + t + data
        return c + struct.pack(">I", zlib.crc32(t + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b"")
    open(path, "wb").write(png)


class Canvas:
    def __init__(self, w, h, cx, cz, scale):
        self.w, self.h, self.cx, self.cz, self.s = w, h, cx, cz, scale
        self.buf = bytearray(b"\x20\x24\x2a" * (w * h))

    def px(self, x, z):
        return int((x - self.cx) * self.s + self.w / 2), int((z - self.cz) * self.s + self.h / 2)

    def dot(self, x, y, r, c):
        for yy in range(y - r, y + r + 1):
            if 0 <= yy < self.h:
                for xx in range(x - r, x + r + 1):
                    if 0 <= xx < self.w:
                        i = (yy * self.w + xx) * 3
                        self.buf[i:i + 3] = bytes(c)

    def line(self, p0, p1, r, c):
        x0, y0 = self.px(*p0)
        x1, y1 = self.px(*p1)
        if max(x0, x1) < 0 or min(x0, x1) >= self.w or max(y0, y1) < 0 or min(y0, y1) >= self.h:
            return
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for k in range(n + 1):
            self.dot(x0 + (x1 - x0) * k // n, y0 + (y1 - y0) * k // n, r, c)


def render(m, route_pts, path, cx, cz, span=6000, size=900):
    cv = Canvas(size, size, cx, cz, size / span)
    for a, b, fl, cls, sp, pts, jid, wd in m["edges"]:
        if not pts or abs(pts[0][0] - cx) > span or abs(pts[0][1] - cz) > span:
            continue
        r = max(0, int(wd * cv.s / 2))
        col = (200, 200, 200) if cls < 3 else (150, 150, 155)
        for i in range(len(pts) - 1):
            cv.line(pts[i], pts[i + 1], r, col)
    for i in range(len(route_pts) - 1):
        cv.line(route_pts[i], route_pts[i + 1], 1, (60, 160, 255))
    for cid, name, x, z in m["cities"]:
        px, py = cv.px(x, z)
        cv.dot(px, py, 3, (255, 90, 90))
    write_png(path, size, size, cv.buf)


def main():
    m = load(sys.argv[1])
    cit = {c[0]: c for c in m["cities"]}
    print(len(m["nodes"]), "nodes", len(m["edges"]), "edges", len(cit), "cities")
    r = Router(m)
    a, b = sys.argv[2], sys.argv[3]
    ca, cb = cit[a], cit[b]
    scc = main_scc(r)
    def snap(c):
        return min(scc, key=lambda i: (m["nodes"][i][0] - c[2]) ** 2 + (m["nodes"][i][1] - c[3]) ** 2)
    na, nb = snap(ca), snap(cb)
    res = r.route(na, nb)
    if not res:
        print("NO ROUTE")
        return
    path, cost = res
    pts = []
    for i in path:
        pts += r.dir[i][2]
    length = sum(r.dir[i][5] for i in path)
    print("%s -> %s: %d edges, %.0f km, %.0f min" % (a, b, len(path), length / 1000, cost / 60))
    cx = (ca[2] + cb[2]) / 2
    cz = (ca[3] + cb[3]) / 2
    render(m, pts, sys.argv[4], cx, cz, span=max(abs(ca[2] - cb[2]), abs(ca[3] - cb[3])) * 1.3 + 2000, size=1000)




def main_scc(r):
    """Largest strongly connected component (node ids) of the directed graph (iterative Kosaraju)."""
    n = len(r.m["nodes"])
    fwd = [[] for _ in range(n)]
    rev = [[] for _ in range(n)]
    for e in r.dir:
        fwd[e[0]].append(e[1])
        rev[e[1]].append(e[0])
    seen = [False] * n
    order = []
    for s in range(n):
        if seen[s] or not fwd[s]:
            continue
        seen[s] = True
        st = [(s, 0)]
        while st:
            u, i = st.pop()
            if i < len(fwd[u]):
                st.append((u, i + 1))
                v = fwd[u][i]
                if not seen[v]:
                    seen[v] = True
                    st.append((v, 0))
            else:
                order.append(u)
    comp = [-1] * n
    best = (0, -1)
    cid = 0
    for s in reversed(order):
        if comp[s] != -1:
            continue
        comp[s] = cid
        st = [s]
        cnt = 0
        while st:
            u = st.pop()
            cnt += 1
            for v in rev[u]:
                if comp[v] == -1:
                    comp[v] = cid
                    st.append(v)
        if cnt > best[0]:
            best = (cnt, cid)
        cid += 1
    return {i for i in range(n) if comp[i] == best[1]}


if __name__ == "__main__":
    main()
