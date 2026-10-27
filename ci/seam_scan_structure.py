#!/usr/bin/env python3
"""Verification croisee T298 : pose <structure> a <x y z> sur le monde banc
snapshotte, puis compte les lignes strictes TERRAIN apparues hors emprise.

Usage : python3 ci/seam_scan_structure.py <structure> <x> <y> <z> [nom]

Reutilise le snapshot /tmp/bench/world_snapshot (monde vierge pre-genere) ;
compare les murs |dh|>=4 (serie >= 6) pre/post hors emprise connue
(l'emprise reelle est lue dans le log post-pose : ligne « emprise
enregistree : <nom> (<W>x<H> a X,Z) »).
"""
import os, re, shutil, subprocess, sys, threading, time, queue
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
import analyze_seams as A
from bench_dragon import kill_strays, rcon, write_props, boot, wait_line, wait_any, stop, RUN, WORLD, SNAP

STRUCT = sys.argv[1]
PX, PY, PZ = int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
NAME = sys.argv[5] if len(sys.argv) > 5 else ('seam-' + STRUCT)
OUT = os.path.join('/tmp/bench', NAME)
os.makedirs(OUT, exist_ok=True)


def all_walls(world, x0, z0, x1, z1):
    H = {}
    reg = os.path.join(world, 'region')
    for rx in range(min(0, x0) >> 9, (x1 >> 9) + 1):
        for rz in range(min(0, z0) >> 9, (z1 >> 9) + 1):
            f = os.path.join(reg, 'r.%d.%d.mca' % (rx, rz))
            if not os.path.exists(f):
                continue
            for (lx, lz), raw in A.iter_region_chunks(f):
                chx, chz = (rx << 5) + lx, (rz << 5) + lz
                hm, _ = A.chunk_heightmap(raw)
                if hm is None:
                    continue
                for i in range(256):
                    bx, bz = (chx << 4) + (i % 16), (chz << 4) + (i // 16)
                    if x0 <= bx <= x1 and z0 <= bz <= z1:
                        H[(bx, bz)] = hm[i]
    runs = []
    for x in range(x0, x1):
        col = []
        for z in range(z0, z1 + 1):
            a, b = H.get((x, z)), H.get((x + 1, z))
            if a is not None and b is not None and abs(a - b) >= 4:
                col.append(z)
        s = None
        e = None
        for z in col:
            if s is None:
                s = e = z
            elif z == e + 1:
                e = z
            else:
                if e - s + 1 >= 6:
                    runs.append(('V', x, e - s + 1, s, e))
                s = e = z
        if s is not None and e - s + 1 >= 6:
            runs.append(('V', x, e - s + 1, s, e))
    for z in range(z0, z1):
        row = []
        for x in range(x0, x1 + 1):
            a, b = H.get((x, z)), H.get((x, z + 1))
            if a is not None and b is not None and abs(a - b) >= 4:
                row.append(x)
        s = None
        e = None
        for x in row:
            if s is None:
                s = e = x
            elif x == e + 1:
                e = x
            else:
                if e - s + 1 >= 6:
                    runs.append(('H', z, e - s + 1, s, e))
                s = e = x
        if s is not None and e - s + 1 >= 6:
            runs.append(('H', z, e - s + 1, s, e))
    return runs


def overlap_emprise(run, em, margin=8):
    if em is None:
        return False
    kind, line, ln, s, e = run
    ex0, ez0, ex1, ez1 = em
    if kind == 'V':
        return ex0 - margin <= line <= ex1 + margin and e >= ez0 - margin and s <= ez1 + margin
    return ez0 - margin <= line <= ez1 + margin and e >= ex0 - margin and s <= ex1 + margin


def main():
    kill_strays()
    bench_world = RUN / WORLD
    if not SNAP.exists():
        print('snapshot absent : lancer d''abord bench_dragon.py iter-xx --fresh')
        return 2
    subprocess.run(['rm', '-rf', str(bench_world)], check=False)
    shutil.copytree(SNAP / WORLD, bench_world)
    write_props()
    # murs PRE sur le snapshot dans la fenetre structure+marge genereuse
    x0, z0 = PX - 160, PZ - 160
    x1, z1 = PX + 160, PZ + 160
    pre = all_walls(SNAP / WORLD, x0, z0, x1, z1)
    print('murs PRE (snapshot) : %d' % len(pre), flush=True)

    proc, lines, log = boot()
    emprise = None
    try:
        wait_line(lines, 'Done (', 600)
        time.sleep(3)
        for _ in range(10):
            try:
                rcon('time query daytime', timeout=10)
                break
            except Exception:
                time.sleep(2)
        t_cmd = time.time()
        rcon('execute positioned %d %d %d run dlbtest %s' % (PX, PY, PZ, STRUCT), timeout=30)
        print('%s lance en %d %d %d' % (STRUCT, PX, PY, PZ), flush=True)
        rec = []
        # NB : wait_any attend TOUTES les needles — une seule variante ici,
        # la 2e est couverte par un second attente courte en fallback.
        try:
            wait_any(lines, ['Post-process de ' + STRUCT], 900, rec)
        except TimeoutError:
            wait_any(lines, ['decorate + post de ' + STRUCT], 120, rec)
        t_done = time.time()
        print('post-process vu en %.1f s' % (t_done - t_cmd), flush=True)
        # emprise reelle : derniere ligne « emprise enregistree » du log
        for l in rec + log:
            m = re.search(r'emprise enregistree : \S+ \((\d+)x(\d+) a (-?\d+),(-?\d+)\)', l)
            if m:
                w, h, ex, ez = map(int, m.groups())
                emprise = (ex, ez, ex + w - 1, ez + h - 1)
        rcon('save-all flush', timeout=120)
        time.sleep(2)
    except Exception as e:
        print('SEAM-SCAN FAIL :', e, flush=True)
    finally:
        stop(proc)
    log_path = os.path.join(OUT, 'run.log')
    open(log_path, 'w').write('\n'.join(log))

    post = all_walls(bench_world, x0, z0, x1, z1)
    print('murs POST : %d' % len(post), flush=True)
    new = [r for r in post if r not in pre]
    out_terr = [r for r in new if not overlap_emprise(r, emprise)]
    print('emprise lue : %s' % (emprise,), flush=True)
    print('APPARUS post-pose : %d (dont hors emprise+marge8 : %d)' % (len(new), len(out_terr)), flush=True)
    for r in out_terr[:15]:
        print('  TERRAIN-MUR', r, flush=True)
    rep = open(os.path.join(OUT, 'seam-report.txt'), 'w')
    rep.write('structure=%s pos=%d,%d,%d emprise=%s\n' % (STRUCT, PX, PY, PZ, emprise))
    rep.write('pre=%d post=%d apparus=%d hors_emprise=%d\n' % (len(pre), len(post), len(new), len(out_terr)))
    for r in out_terr:
        rep.write('TERRAIN-MUR %s\n' % (r,))
    for r in new:
        if overlap_emprise(r, emprise):
            rep.write('EMPRISE/SILHOUETTE %s\n' % (r,))
    rep.close()
    print('rapport : %s' % os.path.join(OUT, 'seam-report.txt'), flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
