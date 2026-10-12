"""Build a compact road network (etsmap.bin) from the installed ETS2 map(s) and active mods.

    python build_map.py                      # auto-detect game, profile mods via TruckSim GPS server
    python build_map.py --no-mods            # base game + DLC only
    python build_map.py --game-dir "D:\\Steam\\...\\Euro Truck Simulator 2" -o etsmap.bin
"""
import argparse
import math
import os
import struct
import sys
import time
import zlib

from gamefiles import find_game_dir, mount_everything
from mapparse import Defs, load_locale, parse_sector, str_to_token, token_to_str

LANE_W = 4.5
# POI types: 1 fuel, 2 service, 3 parking / rest area, 4 weigh station, 5 truck dealer, 6 garage, 7 recruitment, 8 hotel
SPAWN_POI = {3: 1, 4: 2, 5: 3, 6: 4, 7: 5, 8: 8, 15: 6, 16: 7}
POI_NAMES = {1: 'fuel', 2: 'service', 3: 'parking', 4: 'weigh', 5: 'dealer', 6: 'garage', 7: 'recruit', 8: 'hotel'}
HUD_PARKING = str_to_token("hud_parking")
# FORMAT_VERSION changes whenever the builder's output could change (it triggers an automatic rebuild);
# FILE_VERSION is the binary layout the phone app must understand and only changes with the layout.
FORMAT_VERSION = 15
FILE_VERSION = 8


def f32(v):
    return struct.unpack("<f", struct.pack("<f", v))[0]


def q4(v):
    return int(math.floor(v * 4 + 0.5))


def log(*a):
    print(*a, flush=True)


# ------------------------------------------------------------------ geometry --

def rotate_point(x, z, ang, rx, rz):
    s, c = math.sin(ang), math.cos(ang)
    nx, nz = x - rx, z - rz
    return (nx * c - nz * s + rx, nx * s + nz * c + rz)


def hermite(s, p0, p1, t0, t1):
    s2, s3 = s * s, s * s * s
    return ((2 * s3 - 3 * s2 + 1) * p0 + (-2 * s3 + 3 * s2) * p1 + (s3 - 2 * s2 + s) * t0 + (s3 - s2) * t1)


def road_points(sn, en):
    sx, sz, srot = sn
    ex, ez, erot = en
    radius = math.hypot(ex - sx, ez - sz)
    tsx = math.cos(-(math.pi * 0.5 - srot)) * radius
    tsz = math.sin(-(math.pi * 0.5 - srot)) * radius
    tex = math.cos(-(math.pi * 0.5 - erot)) * radius
    tez = math.sin(-(math.pi * 0.5 - erot)) * radius
    n = max(3, min(24, int(radius / 18) + 2))
    pts = []
    for i in range(n):
        s = i / (n - 1)
        pts.append((hermite(s, sx, ex, tsx, tex), hermite(s, sz, ez, tsz, tez)))
    return pts


def quat_dir(w, x, y, z):
    """Forward (-Z) direction of a Y-rotation quaternion in the XZ plane."""
    theta = 2.0 * math.atan2(y, w)
    return (-math.sin(theta), -math.cos(theta))


def curve_points(c, steps):
    (sx, sz), (ex, ez) = c["s"], c["e"]
    ln = c["len"]
    sdx, sdz = c["sdir"]
    edx, edz = c["edir"]
    pts = []
    for i in range(steps):
        s = i / (steps - 1)
        pts.append((hermite(s, sx, ex, sdx * ln, edx * ln), hermite(s, sz, ez, sdz * ln, edz * ln)))
    return pts


def parse_curves(pf):
    d = pf.raw
    stride = 0x84 if pf.version >= 25 else 0x80
    curves = []
    for i in range(pf.nav_count):
        r = pf.nav_off + i * stride
        if r + stride > len(d):
            return None
        sx, sy, sz = struct.unpack_from("<fff", d, r + 0x10)
        ex, ey, ez = struct.unpack_from("<fff", d, r + 0x1C)
        sq = struct.unpack_from("<ffff", d, r + 0x28)
        eq = struct.unpack_from("<ffff", d, r + 0x38)
        ln = struct.unpack_from("<f", d, r + 0x48)[0]
        nxt = list(struct.unpack_from("<4i", d, r + 0x4C))
        nc, pc = struct.unpack_from("<2i", d, r + 0x6C)
        if any(v >= pf.nav_count for v in nxt) or not (0 <= nc <= 4) or not (0 <= pc <= 4):
            return None
        curves.append({"s": (sx, sz), "e": (ex, ez), "sdir": quat_dir(*sq), "edir": quat_dir(*eq),
                       "len": max(ln, 0.01), "next": [v for v in nxt if v >= 0], "pc": pc})
    return curves


def find_cycle(curves):
    """The curve indices of one closed loop in the nav-curve graph, or None."""
    color = [0] * len(curves)
    for s0 in range(len(curves)):
        if color[s0]:
            continue
        stack = [(s0, 0)]
        color[s0] = 1
        path = [s0]
        while stack:
            u, i = stack.pop()
            nxt = curves[u]["next"]
            if i < len(nxt):
                stack.append((u, i + 1))
                v = nxt[i]
                if color[v] == 1:
                    return path[path.index(v):]
                if color[v] == 0:
                    color[v] = 1
                    stack.append((v, 0))
                    path.append(v)
            else:
                color[u] = 2
                path.pop()
    return None


def _circle3(a, b, c):
    ax, ay = a
    bx, by = b
    cx, cy = c
    d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    if abs(d) < 1e-6:
        return None
    ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
    uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
    return ux, uy, math.hypot(ax - ux, ay - uy)


NOT_ROUNDABOUT_WORDS = ("gas", "plaza", "parking", "depot", "service", "station", "tshape", "truckstop")


def has_ring(curves):
    """Prefab-level: do at least three curved lanes share one circle (centre and radius) covering >= 70 % of it?"""
    arcs = []
    for c in curves:
        pts = curve_points(c, 7)
        if len(pts) < 3:
            continue
        h0 = math.atan2(pts[1][1] - pts[0][1], pts[1][0] - pts[0][0])
        h1 = math.atan2(pts[-1][1] - pts[-2][1], pts[-1][0] - pts[-2][0])
        if abs((h1 - h0 + math.pi) % (2 * math.pi) - math.pi) < math.radians(20):
            continue
        cc = _circle3(pts[0], pts[len(pts) // 2], pts[-1])
        if cc and 5 <= cc[2] <= 70:
            arcs.append((cc[0], cc[1], cc[2], pts))
    for cx, cz, r, _ in arcs:
        grp = [a for a in arcs if math.hypot(a[0] - cx, a[1] - cz) < 4 and abs(a[2] - r) < 4]
        if len(grp) < 3:
            continue
        bins = set()
        for a in grp:
            for q in a[3]:
                bins.add(int(((math.atan2(q[1] - cz, q[0] - cx) + math.pi) / (2 * math.pi)) * 24) % 24)
        if len(bins) / 24.0 >= 0.7:
            return True
    return False


def is_roundabout(unodes, origin, paths, is_connected):
    """Is this *placed* junction a roundabout?

    The same prefab can be a roundabout where all arms have roads and a plain T-junction where one arm is unused,
    and prefabs with a ring of U-turn lanes look like rings in the nav data without being roundabouts. What really
    distinguishes a roundabout: with at least three connected arms, traffic carrying straight on between two
    opposite arms is forced to bend around a central island (a sideways bulge), whereas at an ordinary
    intersection that path is straight.
    """
    nn = len(unodes)
    connected = {i for i in range(nn) if is_connected(unodes[(i - origin) % nn])}
    if len(connected) < 3:
        return False
    seen_straight = False
    for (a, b), (_length, pts) in paths.items():
        if a not in connected or b not in connected or len(pts) < 3:
            continue
        h0 = math.atan2(pts[1][1] - pts[0][1], pts[1][0] - pts[0][0])
        h1 = math.atan2(pts[-1][1] - pts[-2][1], pts[-1][0] - pts[-2][0])
        if abs((h1 - h0 + math.pi) % (2 * math.pi) - math.pi) > math.radians(25):
            continue  # not carrying straight on
        (x0, z0), (x1, z1) = pts[0], pts[-1]
        chord = math.hypot(x1 - x0, z1 - z0)
        if chord < 12:
            continue
        bulge = max(abs((q[0] - x0) * (z1 - z0) - (q[1] - z0) * (x1 - x0)) / chord for q in pts)
        if bulge < 4.0:
            return False  # one straight-through path is genuinely straight: an ordinary intersection
        seen_straight = True
    return seen_straight


def nearest_node(pf, p):
    """Prefab node whose cross-section (line through the node, perpendicular to its direction) the point lies on.

    Plain nearest-distance fails for wide nodes (a 6-lane node's outer lane can be nearer another node)."""
    best, bs = -1, 1e30
    for i, (x, z, rx, rz, lanes) in enumerate(pf.nodes):
        dx, dz = p[0] - x, p[1] - z
        ln = math.hypot(rx, rz) or 1.0
        along = abs(dx * rx + dz * rz) / ln
        lat = abs(-dx * rz + dz * rx) / ln
        half = max(lanes, 1) * 2.25 + 1.5
        score = along * 4.0 + max(0.0, lat - half) + 0.01 * (dx * dx + dz * dz) ** 0.5
        if score < bs:
            best, bs = i, score
    return best


def prefab_paths(pf, curves):
    """Chains of nav curves from a prefab entry node to an exit node -> {(a,b): (length, local pts)}."""
    best = {}
    starts = [i for i, c in enumerate(curves) if c["pc"] == 0]
    budget = [4000]

    def dfs(i, chain, seen):
        if budget[0] <= 0:
            return
        budget[0] -= 1
        c = curves[i]
        chain.append(i)
        seen.add(i)
        if not c["next"]:
            a = nearest_node(pf, curves[chain[0]]["s"])
            b = nearest_node(pf, c["e"])
            if a != b and a >= 0 and b >= 0:
                length = sum(curves[k]["len"] for k in chain)
                key = (a, b)
                if key not in best or length < best[key][0]:
                    best[key] = (length, list(chain))
        else:
            for n in c["next"]:
                if n not in seen:
                    dfs(n, chain, seen)
        chain.pop()
        seen.discard(i)

    for s in starts:
        dfs(s, [], set())
    out = {}
    for key, (length, chain) in best.items():
        pts = []
        for k in chain:
            c = curves[k]
            seg = curve_points(c, max(2, min(10, int(c["len"] / 5) + 2)))
            pts.extend(seg if not pts else seg[1:])
        out[key] = (length, pts)
    return out


def prefab_lanes(pf, curves):
    """Lane connections of a prefab: {(entry node, exit node): (lanes at the entry, bitmask of lanes that reach the exit)}.

    Every start curve (no predecessor) is one lane entering the junction; lanes are numbered from the left of
    the direction of travel. Which lanes can reach which exit is read from the chains of next-curves."""
    starts = [i for i, c in enumerate(curves) if c["pc"] == 0]
    by_node = {}
    for i in starts:
        a = nearest_node(pf, curves[i]["s"])
        if a >= 0:
            by_node.setdefault(a, []).append(i)
    lane_of = {}
    count = {}
    for a, idxs in by_node.items():
        dx, dz = curves[idxs[0]]["sdir"]
        rx, rz = -dz, dx  # right-hand side of the travel direction
        lat = sorted({round((curves[i]["s"][0] * rx + curves[i]["s"][1] * rz) * 2) / 2 for i in idxs})
        count[a] = min(len(lat), 15)
        for i in idxs:
            v = round((curves[i]["s"][0] * rx + curves[i]["s"][1] * rz) * 2) / 2
            lane_of[i] = min(lat.index(v), 14)
    out = {}
    for i in starts:
        if i not in lane_of:
            continue
        a = nearest_node(pf, curves[i]["s"])
        budget = [300]
        seen = set()
        stack = [i]
        ends = set()
        while stack and budget[0] > 0:
            budget[0] -= 1
            u = stack.pop()
            if u in seen:
                continue
            seen.add(u)
            c = curves[u]
            if not c["next"]:
                b = nearest_node(pf, c["e"])
                if b >= 0 and b != a:
                    ends.add(b)
            else:
                stack.extend(c["next"])
        for b in ends:
            n, m = out.get((a, b), (count[a], 0))
            out[(a, b)] = (n, m | (1 << lane_of[i]))
    return out


# ---------------------------------------------------------------------- main --

def collect_sectors(fs, defs):
    mbds = [f for f in fs.listdir("map")[1] if f.endswith(".mbd")]
    names = [m[:-4] for m in mbds]
    if not names:
        raise SystemExit("no map (.mbd) found in the mounted archives")
    if len(names) > 1:
        # keep the map provided by the highest-priority archive (mods mount last)
        from scsfs import city_hash64
        order = {p: i for i, p in enumerate(fs.mounted)}
        def prio(n):
            arc = fs.files.get(city_hash64("map/%s.mbd" % n))
            return order.get(arc.path, -1) if arc else -1
        ets = [n for n in names if n != "usa"] or names
        keep = max(ets, key=prio)
        log("maps found: %s -> using '%s'" % (", ".join(names), keep))
        names = [keep]
    nodes, roads, prefabs, cities = {}, {}, {}, {}
    companies = []
    ferries = []
    overlays = []
    skipped = bad = 0
    for name in names:
        files = [f for f in fs.listdir("map/" + name)[1] if f.endswith(".base")]
        log("parsing %d sectors of map '%s'" % (len(files), name))
        for f in files:
            d = fs.read("map/%s/%s" % (name, f))
            try:
                s = parse_sector(d, defs)
            except Exception as e:  # noqa: BLE001
                log("  ! %s: %s" % (f, e))
                bad += 1
                continue
            if s.version < 884:
                skipped += 1
                continue
            if not s.ok:
                bad += 1
                log("  ! %s: did not parse to end of file; ignoring" % f)
                continue
            for k, v in s.nodes.items():
                nodes.setdefault(k, v)
            for r in s.roads:
                roads[r[0]] = r
            for p in s.prefabs:
                prefabs[p[0]] = p
            for c in s.cities:
                cities.setdefault(c[0], c)
            companies.extend(s.companies)
            ferries.extend(s.ferries)
            overlays.extend(s.overlays)
    if skipped:
        log("  ! %d sectors use an unsupported (very old) format and were skipped" % skipped)
    collect_sectors.companies = companies
    collect_sectors.ferries = ferries
    collect_sectors.overlays = overlays
    return nodes, list(roads.values()), list(prefabs.values()), list(cities.values())


def build(fs, out_path):
    t0 = time.time()
    defs = Defs(fs, log)
    nodes, roads, prefabs, cities = collect_sectors(fs, defs)
    log("%d nodes, %d roads, %d prefabs, %d cities" % (len(nodes), len(roads), len(prefabs), len(cities)))

    graph = []      # (uid_a, uid_b, flags, cls, speed, pts, jid)
    junctions = {}  # jid -> (node_count, is_roundabout)
    oneway = []     # (graph index, road look) for one-way road edges
    pois = []       # (type, x, z)
    ring_cache = {}
    jct_cands = []  # placed junctions whose roundabout status is decided once the graph is complete
    rb_debug = []
    lanes_of = {}   # (uid_a, uid_b, jid) -> (lanes at the entry, mask of lanes reaching the exit)

    for uid, su, eu, look, secret, _dlc, hid in roads:
        if hid and os.environ.get("ETS2_NO_HIDDEN"):
            continue
        sn, en = nodes.get(su), nodes.get(eu)
        if not sn or not en:
            continue
        left, right, off, dl, dr, side, grade = defs.roadlooks[look]
        if dl + dr == 0:
            continue  # rails, trams, pedestrian-only
        total = left + right
        width = off + LANE_W * total
        pts = road_points(sn, en)
        if secret:
            continue
        wq = min(255, int(max(width, 4.5) * 4))
        dt = dl + dr
        cls = 2 if dt >= 6 else 1 if dt >= 4 else 0
        speed = (110, 90, 60)[2 - cls] if cls else 50
        fwd, bwd = dr > 0, dl > 0
        if fwd != bwd and side:  # one-way: template side decides the direction
            fwd, bwd = side == "R", side == "L"
        if fwd and bwd:
            graph.append((su, eu, 1 | (grade << 1) | (16 if hid else 0), cls, speed, pts, 0, wq))
        elif fwd:
            oneway.append((len(graph), look))
            graph.append((su, eu, (grade << 1) | (16 if hid else 0), cls, speed, pts, 0, wq))
        elif bwd:
            oneway.append((len(graph), look))
            graph.append((eu, su, (grade << 1) | (16 if hid else 0), cls, speed, pts[::-1], 0, wq))

    npref = nonav = 0
    for uid, ptok, unodes, origin, secret, _dlc, hid in prefabs:
        if hid and os.environ.get("ETS2_NO_HIDDEN"):
            continue
        pf = defs.prefab(ptok)
        if pf is None or not unodes or origin >= len(pf.nodes):
            continue
        onode = nodes.get(unodes[0])
        if not onode:
            continue
        mpo = pf.nodes[origin]
        rot = onode[2] - math.pi - math.atan2(mpo[3], mpo[2]) + math.pi / 2
        sx, sz = onode[0] - mpo[0], onode[1] - mpo[1]

        def tf(p, sx=sx, sz=sz, rot=rot, onode=onode):
            return rotate_point(sx + p[0], sz + p[1], rot, onode[0], onode[1])

        npref += 1
        if not hid and not secret:
            for sp in pf.spawns:
                t = SPAWN_POI.get(sp[2])
                if t:
                    pois.append((t, *tf((sp[0], sp[1]))))
            seen_trig = set()
            for tid, act, tx_, tz_ in pf.triggers:
                if act == HUD_PARKING and tid not in seen_trig:
                    seen_trig.add(tid)
                    pois.append((3, *tf((tx_, tz_))))
        # routing: nav curve chains between prefab nodes
        if pf.nav_count:
            curves = parse_curves(pf)
            if curves:
                paths = prefab_paths(pf, curves)
                plane = prefab_lanes(pf, curves)
                name = ""
                path = defs.prefab_paths.get(ptok, "").lower()
                rb = False
                # unique id per placed junction (the game's item uids collide when truncated to 32 bits)
                jid_ = len(junctions) + 1
                if ptok not in ring_cache:
                    ring_cache[ptok] = ((has_ring(curves) or find_cycle(curves) is not None)
                                        and not any(w in path for w in NOT_ROUNDABOUT_WORDS))
                if ring_cache[ptok]:
                    jct_cands.append((jid_, unodes, origin, paths, path, onode))
                junctions[jid_] = (len(pf.nodes), rb)
                nn = len(unodes)
                for (a, b), (length, lpts) in paths.items():
                    if nn != len(pf.nodes):
                        continue
                    # item node k corresponds to prefab node (origin + k) % n
                    ua, ub = unodes[(a - origin) % nn], unodes[(b - origin) % nn]
                    pts = [tf(p) for p in lpts]
                    graph.append((ua, ub, 16 if hid else 0, 3, 40, pts, jid_, 30))  # flag bit 4 = hidden item
                    if (a, b) in plane:
                        lanes_of[(ua, ub, jid_)] = plane[(a, b)]
            else:
                nonav += 1
    log("prefabs used: %d (%d with unreadable nav data); %d graph edges" % (npref, nonav, len(graph)))

    build.last = {"graph": graph}
    # Self-check one-way directions: at a node that junction paths only enter (or only leave), the road
    # attached to it must leave (or enter). Look types that disagree overall are flipped.
    out_p, in_p = {}, {}
    for e in graph:
        if e[6]:
            out_p[e[0]] = out_p.get(e[0], 0) + 1
            in_p[e[1]] = in_p.get(e[1], 0) + 1

    def vote(n, enters):
        o, i = out_p.get(n, 0), in_p.get(n, 0)
        if o and not i:
            return 1 if enters else -1
        if i and not o:
            return -1 if enters else 1
        return 0

    score = {}
    for gi, look in oneway:
        a, b = graph[gi][0], graph[gi][1]
        score[look] = score.get(look, 0) + vote(b, True) + vote(a, False)
    flip = {k for k, v in score.items() if v <= -3}
    for gi, look in oneway:
        if look in flip:
            a, b, fl, cls, sp, pts, jid, wq = graph[gi]
            graph[gi] = (b, a, fl, cls, sp, pts[::-1], jid, wq)
    if flip:
        log("flipped one-way direction for %d road types: %s" % (
            len(flip), ", ".join(sorted(token_to_str(k) for k in flip))[:200]))

    # Roundabout status per placed junction. An arm only counts as connected if a drivable road, or another
    # junction, attaches to it (unused arms of a prefab often touch decorative road items).
    road_nodes, jids_at = set(), {}
    for e in graph:
        if e[6] == 0:
            road_nodes.add(e[0])
            road_nodes.add(e[1])
        else:
            jids_at.setdefault(e[0], set()).add(e[6])
            jids_at.setdefault(e[1], set()).add(e[6])
    for uid, unodes, origin, paths, path, onode in jct_cands:
        def conn(u, uid=uid):
            return u in road_nodes or bool(jids_at.get(u, set()) - {uid})
        if os.environ.get("ETS2_TRACE") == str(uid):
            nn_ = len(unodes)
            log("TRACE", path, "arms connected:", [i for i in range(nn_) if conn(unodes[(i - origin) % nn_])], "of", nn_)
            for (a_, b_), (ln_, pts_) in paths.items():
                if len(pts_) >= 3:
                    h0_ = math.atan2(pts_[1][1] - pts_[0][1], pts_[1][0] - pts_[0][0]); h1_ = math.atan2(pts_[-1][1] - pts_[-2][1], pts_[-1][0] - pts_[-2][0])
                    dh_ = abs((h1_ - h0_ + math.pi) % (2 * math.pi) - math.pi)
                    (x0_, z0_), (x1_, z1_) = pts_[0], pts_[-1]
                    ch_ = math.hypot(x1_ - x0_, z1_ - z0_)
                    bl_ = max(abs((q[0] - x0_) * (z1_ - z0_) - (q[1] - z0_) * (x1_ - x0_)) / ch_ for q in pts_) if ch_ > 1 else 0
                    log("   path", a_, "->", b_, "turn %.0f deg" % math.degrees(dh_), "chord %.0f" % ch_, "bulge %.1f" % bl_)
        if is_roundabout(unodes, origin, paths, conn):
            junctions[uid] = (junctions[uid][0], True)
            if os.environ.get("ETS2_DEBUG"):
                rb_debug.append((uid, path, round(onode[0]), round(onode[1])))

    # ferries / trains: link the road node of one port to the road node of the connected port
    pf_by_uid = {p[0]: p for p in prefabs}
    ports = {}
    for ptok, link, fnode, train, fx, fz in collect_sectors.ferries:
        best = None
        lp = pf_by_uid.get(link)
        if lp:
            cand = [u for u in lp[2] if u in nodes]
            if cand:
                best = min(cand, key=lambda u: (nodes[u][0] - fx) ** 2 + (nodes[u][1] - fz) ** 2)
        if best is not None:
            ports.setdefault(ptok, []).append(best)
    nf = 0
    for a_tok, b_tok, mins in defs.ferry_conns:
        for na in ports.get(a_tok, [])[:1]:
            for nb in ports.get(b_tok, [])[:1]:
                (ax, az, _), (bx, bz, _) = nodes[na], nodes[nb]
                length = math.hypot(bx - ax, bz - az)
                secs = max(60.0, mins * 60.0 / 19.0)  # game minutes -> real seconds
                kmh = max(5, min(250, int(length / secs * 3.6)))
                graph.append((na, nb, 1 | 8, 1, kmh, [(ax, az), (bx, bz)], 0, 8))  # bit 3 = ferry / train link
                nf += 1
    if rb_debug:
        import json
        json.dump(rb_debug, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "rb_debug.json"), "w"))
    log("%d ferry/train links (of %d connections)" % (nf, len(defs.ferry_conns)))

    # compact nodes: only those referenced by graph edges
    idx = {}
    nx = []
    for e in graph:
        for u in (e[0], e[1]):
            if u not in idx and u in nodes:
                idx[u] = len(nx)
                nx.append((nodes[u][0], nodes[u][1]))
    graph = [e for e in graph if e[0] in idx and e[1] in idx]

    minx = min(p[0] for p in nx)
    maxx = max(p[0] for p in nx)
    minz = min(p[1] for p in nx)
    maxz = max(p[1] for p in nx)

    buf = bytearray()
    buf += struct.pack("<4sHH4f", b"ETSM", FILE_VERSION, 0, minx, minz, maxx, maxz)
    buf += struct.pack("<I", len(nx))
    for x, z in nx:
        buf += struct.pack("<ff", x, z)
    jl = sorted(junctions.items())
    buf += struct.pack("<I", len(jl))
    for jid, (cnt, rb) in jl:
        buf += struct.pack("<IBB", jid & 0xFFFFFFFF, cnt, 1 if rb else 0)
    buf += struct.pack("<I", len(graph))
    for a, b, flags, cls, speed, pts, jid, wq in graph:
        pts = pts[:255]
        buf += struct.pack("<IIBBBBIB", idx[a], idx[b], flags, cls, speed // 5, wq, jid & 0xFFFFFFFF, len(pts))
        qx, qz = q4(f32(nx[idx[a]][0])), q4(f32(nx[idx[a]][1]))
        for x, z in pts:
            px, pz = q4(x), q4(z)
            dx = max(-32768, min(32767, px - qx))
            dz = max(-32768, min(32767, pz - qz))
            buf += struct.pack("<hh", dx, dz)
            qx += dx
            qz += dz
    cl = []
    loc = load_locale(fs)
    seen = set()
    for tok, x, z in cities:
        c = defs.cities.get(tok)
        if c and tok not in seen:
            seen.add(tok)
            name = loc.get(c["loc"]) or c["name"]
            cl.append((c["id"], name.replace("�", ""), x, z))
    buf += struct.pack("<I", len(cl))
    for cid, name, x, z in cl:
        for s in (cid, name):
            b = s.encode("utf-8")[:255]
            buf += struct.pack("<B", len(b)) + b
        buf += struct.pack("<ff", x, z)
    comp = {}
    for cotok, ctok, x, z in collect_sectors.companies:
        comp.setdefault((cotok, ctok, round(x), round(z)), (token_to_str(cotok), token_to_str(ctok), x, z))
    buf += struct.pack("<I", len(comp))
    for cid, city, x, z in comp.values():
        for s_ in (cid, city):
            b = s_.encode("utf-8")[:255]
            buf += struct.pack("<B", len(b)) + b
        buf += struct.pack("<ff", x, z)
    log("%d company locations" % len(comp))
    pois += collect_sectors.overlays
    grid, uniq = {}, []
    for t, x, z in pois:
        key = (t, int(x // 30), int(z // 30))
        near = [(t, key[1] + dx, key[2] + dz) for dx in (-1, 0, 1) for dz in (-1, 0, 1)]
        if any(any(abs(x - ux) < 30 and abs(z - uz) < 30 for ux, uz in grid.get(k, [])) for k in near):
            continue
        grid.setdefault(key, []).append((x, z))
        uniq.append((t, x, z))
    buf += struct.pack("<I", len(uniq))
    for t, x, z in uniq:
        buf += struct.pack("<Bff", t, x, z)
    # lane table (file version 8): junction edge index, lanes at the entry, mask of lanes that take this path
    lane_rows = []
    for gi, e in enumerate(graph):
        li = lanes_of.get((e[0], e[1], e[6])) if e[6] else None
        if li and li[0] >= 2:
            lane_rows.append((gi, li[0], li[1]))
    buf += struct.pack("<I", len(lane_rows))
    for gi, n_, m_ in lane_rows:
        buf += struct.pack("<IBH", gi, n_, m_)
    log("lane data for %d junction paths" % len(lane_rows))
    counts = {}
    for t, _, _ in uniq:
        counts[t] = counts.get(t, 0) + 1
    log("POIs: %s" % ", ".join("%s=%d" % (POI_NAMES.get(t, t), c) for t, c in sorted(counts.items())))
    data = zlib.compress(bytes(buf), 9)
    with open(out_path, "wb") as f:
        f.write(data)
    log("wrote %s: %.1f MB (raw %.1f MB) in %.0fs; %d nodes, %d edges, %d cities"
        % (out_path, len(data) / 1e6, len(buf) / 1e6, time.time() - t0, len(nx), len(graph), len(cl)))
    return out_path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--game-dir")
    ap.add_argument("--no-mods", action="store_true")
    ap.add_argument("--mod", action="append", default=[], help="extra mod file/folder (highest priority)")
    ap.add_argument("-o", "--out", default="etsmap.bin")
    a = ap.parse_args()
    gd = find_game_dir(a.game_dir)
    log("game: %s" % gd)
    fs, _ = mount_everything(gd, log, use_mods=not a.no_mods, extra_mods=a.mod)
    build(fs, a.out)


if __name__ == "__main__":
    main()
