"""Algorithmic simulation of the T77 strict nappe-membership rule (no JDK).

Reproduces, column per column, the fill logic of StructureTerrainPrep.LiquidJob:
- seeds are the scanned liquid columns (pre-existing nappe),
- a dry candidate below the level is filled ONLY when a bounded escape BFS
  proves its whole sub-level land component sealed inside the repair bounds
  (unknown chunk / boundary / budget => OPEN),
- fills never exceed the nappe level, filled columns never become walls.

Fixture 1 mirrors ci/StructureRegressionChecks.basin: 89x89 box, walls/floor
stone, interior air, tower water 241..246 at (-43,-43), one block at (-10,241,-10),
protected 5x5 footprint, island ring at max(|x|,|z|)==3.
Expected: exactly 45_120 blocks restored, footprint dry, ring intact.

Fixture 2 (the 09/27 regression): ocean surface at 62 next to an open plain at
61 inside a large repair region. Expected: ZERO fills, OPEN rejection.
"""

WALL = 'wall'
FLOOR = 'floor'
AIR = 'air'
WATER = 'water'


def basin_case():
    water = {}   # (x,z) -> top water level (pre-existing nappe only)
    solid = {}   # (x,z) -> top solid Y
    for x in range(-44, 45):
        for z in range(-44, 45):
            ring = max(abs(x), abs(z)) == 3
            wall = abs(x) == 44 or abs(z) == 44 or ring
            solid[x, z] = 246 if wall else 240
    water[-43, -43] = 246
    water[-10, -10] = 241
    # initial scan marks pre-existing liquid columns as barriers
    surf = set(water)

    def protected(x, z):
        return abs(x) <= 2 and abs(z) <= 2

    def in_bounds(x, z):   # x4 repair bounds = +/-208 around 0
        return -208 <= x <= 208 and -208 <= z <= 208
    return water, solid, surf, protected, in_bounds, 246


def floodplain_case():
    water, solid = {}, {}
    for x in range(-300, 301):
        for z in range(-300, 301):
            if x < -20:        # ocean (water surface 62, deep floor ignored: surface is water)
                water[x, z] = 62
            else:              # open plain one block below sea level
                solid[x, z] = 61
    surf = set(water)
    protected = lambda x, z: False
    in_bounds = lambda x, z: -300 <= x <= 300 and -300 <= z <= 300
    return water, solid, surf, protected, in_bounds, 62


NEIGH = ((1, 0), (-1, 0), (0, 1), (0, -1))
ESCAPE_MAX = 200_000


def run(name, water, solid, surf, protected, in_bounds, expect_fill, expect_cols=None):
    basin_memo, open_memo = {}, {}
    fills = 0
    queue = [(x, lvl, z, False) for (x, z), lvl in water.items()]
    mark = {(x, lvl, z) for (x, z), lvl in water.items()}

    def escape(x0, z0, lvl):
        seen = {(x0, z0)}
        frontier = [(x0, z0)]
        explored = 1
        while frontier:
            cx, cz = frontier.pop()
            for dx, dz in NEIGH:
                nx, nz = cx + dx, cz + dz
                if (nx, nz) in seen:
                    continue
                if not in_bounds(nx, nz):
                    return False, seen                     # OPEN: boundary
                if (nx, nz) in surf:
                    continue                               # pre-existing nappe = wall
                ov = open_memo.get((nx, nz))
                if ov == lvl:
                    return False, seen                     # OPEN: transitivity
                if protected(nx, nz):
                    continue
                top = water.get((nx, nz))
                if top is None:
                    top = solid.get((nx, nz), 61)
                if top >= lvl:
                    continue                               # rising ground = wall
                if basin_memo.get((nx, nz)) != lvl:
                    explored += 1
                    if explored > ESCAPE_MAX:
                        return False, seen                 # OPEN: budget cap
                seen.add((nx, nz))
                frontier.append((nx, nz))
        return True, seen                                  # ENCLOSED

    while queue:
        x, lvl, z, parent_lava = queue.pop(0)
        for dx, dz in NEIGH:
            nx, nz = x + dx, z + dz
            if (nx, lvl, nz) in mark:
                continue
            if not in_bounds(nx, nz) or protected(nx, nz):
                continue
            # source traversal through pre-existing water
            if water.get((nx, nz)) == lvl:
                mark.add((nx, lvl, nz))
                queue.append((nx, lvl, nz, False))
                continue
            wtop = water.get((nx, nz))          # tracked after fills too
            stop_ = solid.get((nx, nz))
            if stop_ is None and wtop is None:
                continue                                   # no floor
            if wtop is not None and wtop >= lvl:
                continue                                   # already at/above this level
            top = max(t for t in (wtop, stop_) if t is not None)
            if top >= lvl:
                continue
            ck = (nx, nz)
            ov = open_memo.get(ck)
            if ov == lvl:
                continue
            bv = basin_memo.get(ck)
            if bv != lvl:
                ok, seen = escape(nx, nz, lvl)
                if ok:
                    for c in seen:
                        basin_memo[c] = lvl
                else:
                    for c in seen:
                        open_memo[c] = lvl
                    continue
            # tryFillColumn: fill pocket top+1..lvl (a column already filled to a
            # lower level only receives the missing slice, like topSolidAt/free-scan)
            need = lvl - top
            fills += need
            water[nx, nz] = lvl              # after-fill state, still NOT a wall (no surf)
            mark.add((nx, lvl, nz))
            queue.append((nx, lvl, nz, False))

    filled_cols = len([m for m in mark if m[1] == expect_fill or True]) - len(water)
    print(f'{name}: fills={fills}, proofs_basin={len(basin_memo)}, open_cols={len(open_memo)}')
    assert fills == expect_fill, f'{name}: fills {fills} != {expect_fill}'
    if expect_cols is not None:
        print(f'{name}: nouvelles colonnes marquees ~{filled_cols}')
    print(f'{name}: OK')


if __name__ == '__main__':
    # The CI fixture asserts the FINAL state: 45,120 source cells, of which 7
    # pre-existed (seed tower 6 + lone block 1). New fills expected: 45,113.
    run("basin", *basin_case()[:5], expect_fill=45_120 - 7)
    run("floodplain", *floodplain_case()[:5], expect_fill=0)
