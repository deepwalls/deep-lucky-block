"""Analyse serveur des « lignes strictes » (plainte client T294 : le smooth
inter-chunks n'est pas visible, on voit des lignes strictes).

Lit les fichiers region d'un monde (apres pose d'une structure) et mesure la
continuite du terrain :

  A) transitions verticales |dh| aux FRANCHISSEMENTS de bord de chunk (x ou z
     multiple de 16) vs transitions interieures -- un lissage tronque aux
     frontieres de chunk laisse des marches plus raides SUR ces lignes ;
  B) idem aux bords du RECTANGLE de la zone traitee (la ligne droite la plus
     visible serait la) ;
  C) couverture neige : colonnes SNOW_LAYER par 16-colonnes (bande chunk) en
     travers des bords -- une retombee brutale dessine le rectangle.

Usage :
  python3 ci/analyze_seams.py <world_dir> <cx> <cz> [--radius R]
  cx,cz = centre de la zone (blocs), R = demi-cote balaye en blocs (defaut 96)

Sortie : statistiques + verdicts sur stdout (lignes DLB-SEAM pour grep).
Besoin : anvil-parser (pip) ; repli : mini-lecteur NBT interne (heightmaps
MOTION_BLOCKING_NO_LEAVES seulement, sans couche neige).
"""
import math
import os
import re
import struct
import sys
import zlib

RADIUS = 96
SNOW_CHECK = True


# ---------------------------------------------------------------- NBT minimal
def _nbt_read_tag(buf, pos, tag):
    if tag == 0:
        return None, pos
    if tag == 1:
        return buf[pos], pos + 1
    if tag == 2:
        return struct.unpack('>h', buf[pos:pos + 2])[0], pos + 2
    if tag in (3, 4):
        fmt = '>i' if tag == 3 else '>q'
        return struct.unpack(fmt, buf[pos:pos + struct.calcsize(fmt)])[0], pos + struct.calcsize(fmt)
    if tag == 5:
        return struct.unpack('>f', buf[pos:pos + 4])[0], pos + 4
    if tag == 6:
        return struct.unpack('>d', buf[pos:pos + 8])[0], pos + 8
    if tag == 7:
        n = struct.unpack('>i', buf[pos:pos + 4])[0]
        return buf[pos + 4:pos + 4 + n], pos + 4 + n
    if tag == 8:
        n = struct.unpack('>H', buf[pos:pos + 2])[0]
        return buf[pos + 2:pos + 2 + n].decode('utf-8'), pos + 2 + n
    if tag == 9:
        sub = buf[pos]; n = struct.unpack('>i', buf[pos + 1:pos + 5])[0]
        pos += 5
        out = []
        for _ in range(n):
            v, pos = _nbt_read_tag(buf, pos, sub)
            out.append(v)
        return out, pos
    if tag == 10:
        out = {}
        while True:
            t = buf[pos]; pos += 1
            if t == 0:
                return out, pos
            n = struct.unpack('>H', buf[pos:pos + 2])[0]
            name = buf[pos + 2:pos + 2 + n].decode('utf-8')
            pos += 2 + n
            out[name], pos = _nbt_read_tag(buf, pos, t)
    if tag == 11:
        n = struct.unpack('>i', buf[pos:pos + 4])[0]
        return list(struct.unpack('>%di' % n, buf[pos + 4:pos + 4 + 4 * n])), pos + 4 + 4 * n
    if tag == 12:
        n = struct.unpack('>i', buf[pos:pos + 4])[0]
        return list(struct.unpack('>%dq' % n, buf[pos + 4:pos + 4 + 8 * n])), pos + 4 + 8 * n
    raise ValueError('tag %d' % tag)


def nbt_parse(buf):
    tag = buf[0]
    ln = struct.unpack('>H', buf[1:3])[0]
    name = buf[3:3 + ln].decode('utf-8')
    val, _ = _nbt_read_tag(buf, 3 + ln, tag)
    return name, val


# ---------------------------------------------------------------- regions MCA
def iter_region_chunks(mca_path):
    with open(mca_path, 'rb') as f:
        data = f.read()
    locs = data[:4096]
    for i in range(1024):
        off = struct.unpack('>i', b'\0' + locs[i * 4:i * 4 + 3])[0]
        sec = locs[i * 4 + 3]
        if off == 0 or sec == 0:
            continue
        p = off * 4096
        ln = struct.unpack('>i', data[p:p + 4])[0]
        comp = data[p + 4:p + 4 + ln]
        kind = data[p + 4]
        try:
            if kind == 1:      # gzip
                raw = zlib.decompress(comp[1:], zlib.MAX_WBITS | 16)
            elif kind == 2:    # zlib (defaut MC)
                raw = zlib.decompress(comp[1:])
            elif kind == 3:    # brut
                raw = comp[1:]
            else:              # 4 = lz4, 127 = externe : non gere
                continue
        except Exception:
            continue
        yield (i % 32, i // 32), raw


def chunk_heightmap(raw, kind='MOTION_BLOCKING_NO_LEAVES'):
    """Heightmap stockee (longs bit-packed 9 bits) -> liste 256 hauteurs."""
    try:
        _, root = nbt_parse(raw)
    except Exception:
        return None, None
    hm = root.get('Heightmaps', {})
    arr = hm.get(kind)
    if arr is None:
        return None, None
    # DataBitStorage vanilla (1.16+) : valuesPerLong = 64 // 9 = 7, AUCUN
    # chevauchement entre longs, valeur la plus ancienne dans les bits de
    # poids faible (LSB-first).
    bits = 9
    mask = (1 << bits) - 1
    per_long = 64 // bits
    vals = []
    for long_ in arr:
        u = long_ & 0xFFFFFFFFFFFFFFFF
        for k in range(per_long):
            vals.append((u >> (k * bits)) & mask)
            if len(vals) == 256:
                return vals[:256], root
    return (vals + [0] * (256 - len(vals)))[:256], root


def chunk_surface_blocks(raw):
    """Bloc de surface par colonne : {(lx,lz): nom court} (MOTION_BLOCKING-like,
    calcul maison : premier bloc non-air en descendant les sections)."""
    try:
        _, root = nbt_parse(raw)
    except Exception:
        return None
    sections = root.get('sections')
    if not isinstance(sections, list):
        return None
    tops = {}
    for sec in sorted(sections, key=lambda s: -s.get('Y', 0)):
        bs = sec.get('block_states') or {}
        palette = bs.get('palette')
        if not isinstance(palette, list) or not palette:
            continue
        names = [str(p.get('Name', 'minecraft:air')) for p in palette]
        data = bs.get('data')
        if data is None:
            # section mono-palette
            nm = names[0]
            if nm == 'minecraft:air':
                continue
            for lx in range(16):
                for lz in range(16):
                    tops.setdefault((lx, lz), nm)
            continue
        npl = max(len(palette), 2)
        bits = max(4, (npl - 1).bit_length())
        mask = (1 << bits) - 1
        per_long = 64 // bits
        idx_cache = [None] * 4096
        # decode lineaire : long index li, slot k -> idx = li*per_long + k
        for li, long_ in enumerate(data):
            u = long_ & 0xFFFFFFFFFFFFFFFF
            base = li * per_long
            for k in range(per_long):
                i = base + k
                if i < 4096:
                    idx_cache[i] = (u >> (k * bits)) & mask
        for i in range(4096):
            v = idx_cache[i]
            if v is None or v >= len(names):
                continue
            nm = names[v]
            if nm.endswith('air'):
                continue
            y = i >> 8
            rest = i & 255
            lx, lz = rest & 15, rest >> 4
            if (lx, lz) not in tops:
                tops[(lx, lz)] = nm
    return tops


NAT_HINTS = ('dirt', 'grass_block', 'sand', 'gravel', 'stone', 'andesite',
             'diorite', 'granite', 'snow', 'terracotta', 'clay', 'mud',
             'podzol', 'mycelium', 'coarse_dirt', 'rooted_dirt', 'deepslate',
             'tuff', 'gravel', 'sandstone')
NON_TERRAIN_EXACT = ('minecraft:water', 'minecraft:ice')


def is_terrain(name):
    if name in NON_TERRAIN_EXACT or 'air' in name:
        return False
    return any(h in name for h in NAT_HINTS)


def main():
    world = sys.argv[1]
    cx, cz = int(sys.argv[2]), int(sys.argv[3])
    rad = RADIUS
    for i, a in enumerate(sys.argv):
        if a == '--radius':
            rad = int(sys.argv[i + 1])
    region_root = os.path.join(world, 'region')
    x0, z0 = cx - rad, cz - rad
    x1, z1 = cx + rad - 1, cz + rad - 1
    rx0, rz0 = x0 >> 9, z0 >> 9
    rx1, rz1 = x1 >> 9, z1 >> 9
    heights = {}
    tops = {}
    for rx in range(rx0, rx1 + 1):
        for rz in range(rz0, rz1 + 1):
            f = os.path.join(region_root, 'r.%d.%d.mca' % (rx, rz))
            if not os.path.exists(f):
                continue
            for (lx, lz), raw in iter_region_chunks(f):
                chx, chz = (rx << 5) + lx, (rz << 5) + lz
                hm, root = chunk_heightmap(raw)
                if hm is None:
                    continue
                for i in range(256):
                    bx, bz = (chx << 4) + (i % 16), (chz << 4) + (i // 16)
                    heights[(bx, bz)] = hm[i]
                t = chunk_surface_blocks(raw)
                if t:
                    for (lx2, lz2), nm in t.items():
                        tops[((chx << 4) + lx2, (chz << 4) + lz2)] = nm
    if not heights:
        print('DLB-SEAM FAIL : aucune hauteur lue sous %s' % region_root)
        return 2

    def h(x, z):
        return heights.get((x, z))

    # A) transitions horizontales dx : |h(x+1)-h(x)|, en distinguant bord de chunk
    inner, border = [], []
    for x in range(x0, x1):
        for z in range(z0, z1 + 1):
            a, b = h(x, z), h(x + 1, z)
            if a is None or b is None:
                continue
            d = abs(a - b)
            (border if ((x + 1) & 15) == 0 else inner).append((d, x, z))
    # B) bord du rectangle zone traitee : rayon suppose 48+ lissage ; on mesure
    #    les croisements aux multiples attendus du rectangle d'origine : la
    #    structure occupe ~[cx-64, cx+64] ; on teste les anneaux r in {48,64,80,96}
    ring_stats = []
    for rtest in (32, 48, 64, 80, 96):
        vals = []
        rx0c, rx1c = cx - rtest, cx + rtest
        rz0c, rz1c = cz - rtest, cz + rtest
        for x in range(rx0c, rx1c + 1, 1):
            for zc in (rz0c, rz1c):
                a, b = h(x, zc), h(x, zc - 1)
                if a is not None and b is not None:
                    vals.append(abs(a - b))
                a, b = h(x, zc), h(x, zc + 1)
                if a is not None and b is not None:
                    vals.append(abs(a - b))
        for z in range(rz0c, rz1c + 1, 1):
            for xc in (rx0c, rx1c):
                a, b = h(xc, z), h(xc - 1, z)
                if a is not None and b is not None:
                    vals.append(abs(a - b))
                a, b = h(xc, z), h(xc + 1, z)
                if a is not None and b is not None:
                    vals.append(abs(a - b))
        if vals:
            vals.sort()
            ring_stats.append((rtest, len(vals), vals[len(vals) // 2], vals[-1]))

    def stats(v):
        if not v:
            return 'n/a'
        d = sorted(x for x, _, _ in v)
        med = d[len(d) // 2]
        p95 = d[min(len(d) - 1, int(len(d) * 0.95))]
        mx = d[-1]
        big = sum(1 for x in d if x >= 3)
        return 'n={}, med={}, p95={}, max={}, marches>=3 : {} ({:.2f}%%)'.format(
            len(d), med, p95, mx, big, 100.0 * big / len(d))

    print('DLB-SEAM A inner  (transitions dx hors bord) : ' + stats(inner))
    print('DLB-SEAM A border (transitions dx SUR bord chunk) : ' + stats(border))
    inb = sorted(x for x, _, _ in inner)
    brd = sorted(x for x, _, _ in border)
    if inb and brd:
        mi, mb = inb[len(inb) // 2], brd[len(brd) // 2]
        print('DLB-SEAM A verdict bord-chunk : mediane inner={} border={} -> {}'.format(
            mi, mb, 'LIGNE STRICTE PROBABLE (bord plus raide)' if mb > mi else 'bords aussi lisses que l\'interieur'))
        # top marches de bord avec coordonnees
        top = sorted(border, reverse=True)[:8]
        for d, x, z in top:
            print('DLB-SEAM A marche bord : dh={} en x={}->{}  z={}'.format(d, x, x + 1, z))
    print('DLB-SEAM B croisements du rectangle (rayon r, n, mediane, max) :')
    for r, n, med, mx in ring_stats:
        print('  r={:3d} n={:5d} med={} max={}'.format(r, n, med, mx))

    # C) lignes strictes : SERIES de colonnes consecutives a |dh|>=4 alignees
    #    sur un meme x (vertical) ou z (horizontal) dans et autour de la zone.
    vert = {}   # x -> liste consecutive (z triee)
    horz = {}   # z -> liste
    for x in range(x0, x1):
        for z in range(z0, z1 + 1):
            a, b = h(x, z), h(x + 1, z)
            if a is None or b is None:
                continue
            if abs(a - b) >= 4:
                vert.setdefault(x, []).append(z)
    for z in range(z0, z1):
        for x in range(x0, x1 + 1):
            a, b = h(x, z), h(x, z + 1)
            if a is None or b is None:
                continue
            if abs(a - b) >= 4:
                horz.setdefault(z, []).append(x)

    def runs(dic, trans):
        out = []
        for line, cells in dic.items():
            cells.sort()
            s = e = cells[0]
            for v in cells[1:]:
                if v == e + 1:
                    e = v
                    continue
                out.append((e - s + 1, line, s, e))
                s = e = v
            out.append((e - s + 1, line, s, e))
        return [t for t in out if t[0] >= 10]

    rv = sorted(runs(vert, True), reverse=True)
    rh = sorted(runs(horz, False), reverse=True)
    print('DLB-SEAM C lignes strictes >=10 cellules (|dh|>=4) :')
    for ln, x, s, e in rv[:10]:
        print('  VERT  x={}  z={}..{}  longueur={}'.format(x, s, e, ln))
    for ln, z, s, e in rh[:10]:
        print('  HORZ  z={}  x={}..{}  longueur={}'.format(z, s, e, ln))
    inzone_v = sum(1 for ln, x, s, e in rv if abs(x - cx) <= rad)
    print('DLB-SEAM C verdict : {} serie(s) verticales, {} horizontales >=10 cellules'.format(len(rv), len(rh)))

    # D) meme detection, mais uniquement sur paires TERRAIN des deux cotes
    #    (filtre les silhouettes de structure dont le heightmap saute
    #    legitiment au bord du batiment) ; chaque mur retenu reporte les
    #    materiaux des deux rives pour preuve.
    def pair_terrain(xa, za, xb, zb):
        a, b = tops.get((xa, za)), tops.get((xb, zb))
        return a is not None and b is not None and is_terrain(a) and is_terrain(b)

    vert_t, horz_t = {}, {}
    for x in range(x0, x1):
        for z in range(z0, z1 + 1):
            a, b = h(x, z), h(x + 1, z)
            if a is None or b is None or abs(a - b) < 4:
                continue
            if pair_terrain(x, z, x + 1, z):
                vert_t.setdefault(x, []).append(z)
    for z in range(z0, z1):
        for x in range(x0, x1 + 1):
            a, b = h(x, z), h(x, z + 1)
            if a is None or b is None or abs(a - b) < 4:
                continue
            if pair_terrain(x, z, x, z + 1):
                horz_t.setdefault(z, []).append(x)
    rvt = sorted(runs(vert_t, True), reverse=True)
    rht = sorted(runs(horz_t, False), reverse=True)
    print('DLB-SEAM D lignes TERRAIN strictes >=10 cellules (silhouettes batiment exclues) :')
    for ln, x, s, e in rvt[:10]:
        ma = tops.get((x, (s + e) // 2), '?').split(':')[-1]
        mb = tops.get((x + 1, (s + e) // 2), '?').split(':')[-1]
        print('  VERT  x={}  z={}..{}  longueur={}  rive={}/{}'.format(x, s, e, ln, ma, mb))
    for ln, z, s, e in rht[:10]:
        ma = tops.get(((s + e) // 2, z), '?').split(':')[-1]
        mb = tops.get(((s + e) // 2, z + 1), '?').split(':')[-1]
        print('  HORZ  z={}  x={}..{}  longueur={}  rive={}/{}'.format(z, s, e, ln, ma, mb))
    print('DLB-SEAM D verdict : {} serie(s) verticales, {} horizontales (terrain pur)'.format(len(rvt), len(rht)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
