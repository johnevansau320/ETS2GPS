"""Reference implementation of maneuver generation (ported to Kotlin in the Android app)."""
import math
import sys

import verify

S = 19.0  # game distance scale


def bearing(dx, dz):
    return math.degrees(math.atan2(dx, -dz)) % 360.0


def norm180(a):
    a = (a + 180.0) % 360.0 - 180.0
    return a


def tangent(pts, at_end, win=6.0):
    """Bearing of the first/last `win` metres of a polyline."""
    if len(pts) < 2:
        return 0.0
    if at_end:
        p = pts[-1]
        acc = 0.0
        for i in range(len(pts) - 2, -1, -1):
            q = pts[i]
            acc = math.hypot(p[0] - q[0], p[1] - q[1])
            if acc >= win or i == 0:
                return bearing(p[0] - q[0], p[1] - q[1])
            # keep walking
        return 0.0
    p = pts[0]
    acc = 0.0
    for i in range(1, len(pts)):
        q = pts[i]
        acc = math.hypot(q[0] - p[0], q[1] - p[1])
        if acc >= win or i == len(pts) - 1:
            return bearing(q[0] - p[0], q[1] - p[1])
    return 0.0


def maneuvers(r, route):
    """route: list of directed-edge indices into r.dir"""
    m = r.m
    cum = [0.0]
    for i in route:
        cum.append(cum[-1] + r.dir[i][5])
    out = []
    for k, de in enumerate(route):
        a, b, pts, sp, jid, ln = r.dir[de]
        if not jid or k == 0 or k == len(route) - 1:
            continue
        # alternatives: all junction edges of the same prefab leaving the entry node
        alts = [j for j in r.out.get(a, []) if r.dir[j][4] == jid and r.dir[j][1] != r.dir[route[k - 1]][0]]
        # exits other than the way we came
        if len(alts) < 2:
            continue
        tin = tangent(pts, False)
        angle = norm180(tangent(pts, True) - tin)
        angs = sorted((norm180(tangent(r.dir[j][2], True) - tangent(r.dir[j][2], False)) for j in alts), reverse=True)
        rb = m["junc"].get(jid, (0, 0))[1]
        straightest = min(angs, key=abs)
        if rb:
            n = angs.index(angle) + 1 if angle in angs else 1
            out.append((cum[k], "roundabout", n, angle))
            continue
        aa = abs(angle)
        if aa < 22:
            if angle == straightest:
                continue
            out.append((cum[k], "keep right" if angle > 0 else "keep left", 0, angle))
        elif aa < 55:
            out.append((cum[k], "bear right" if angle > 0 else "bear left", 0, angle))
        elif aa < 135:
            out.append((cum[k], "turn right" if angle > 0 else "turn left", 0, angle))
        else:
            out.append((cum[k], "u-turn", 0, angle))
    out.append((cum[-1], "arrive", 0, 0))
    return out


def main():
    m = verify.load(sys.argv[1])
    cit = {c[0]: c for c in m["cities"]}
    r = verify.Router(m)
    scc = verify.main_scc(r)

    def snap(c):
        return min(scc, key=lambda i: (m["nodes"][i][0] - c[2]) ** 2 + (m["nodes"][i][1] - c[3]) ** 2)
    na, nb = snap(cit[sys.argv[2]]), snap(cit[sys.argv[3]])
    path, cost = r.route(na, nb)
    ms = maneuvers(r, path)
    total = sum(r.dir[i][5] for i in path)
    print("route %d edges, %.1f km world (%.0f km game), %d maneuvers" % (len(path), total / 1000, total * S / 1000, len(ms)))
    prev = 0
    for d, t, n, ang in ms:
        print("  %6.0f m (+%4.0f)  %-12s %s  angle %.0f" % (d, d - prev, t, n or "", ang))
        prev = d


if __name__ == "__main__":
    main()
