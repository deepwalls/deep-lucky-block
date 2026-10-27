#!/usr/bin/env python3
"""Banc DLBVERIFY : rejoue la fixture CI (ci/StructureRegressionChecks.java)
sur le lab sandbox /tmp/rt/mod-project, monde dlbbench (seed fixe).

Usage : python3 ci/bench_verify.py <iteration_name>

Le fichier fixture est copie dans les sources du lab, le mod est recompile
par gradle (offline, caches trackes), le serveur boote, puis /dlbverify est
lance par RCON ; la sortie est archivee dans /tmp/bench/<iter>/.

Nouvelles erreurs de fixture = ligne « [DLBVERIFY] FAIL: ... ».
Succes = ligne « [DLBVERIFY] ALL PASS ».
"""
import os, shutil, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bench_dragon import kill_strays, rcon, write_props, boot, wait_line, wait_any, stop, RUN, WORLD, SNAP, PROJECT

ITER = sys.argv[1] if len(sys.argv) > 1 else 'verify'
OUT = os.path.join('/tmp/bench', ITER)
os.makedirs(OUT, exist_ok=True)
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def install_fixture():
    src = os.path.join(REPO, 'ci', 'StructureRegressionChecks.java')
    dst = os.path.join(str(PROJECT), 'src', 'main', 'java', 'deepluckyblock', 'procedures', 'StructureRegressionChecks.java')
    shutil.copyfile(src, dst)
    return dst


def main():
    kill_strays()
    dst = install_fixture()
    print('fixture copiee :', dst, flush=True)
    bench_world = RUN / WORLD
    if SNAP.exists():
        subprocess.run(['rm', '-rf', str(bench_world)] +
                       [str(RUN / d) for d in (WORLD + '_nether', WORLD + '_the_end')], check=False)
        shutil.copytree(SNAP / WORLD, bench_world)
        print('snapshot restaure (monde pre-genere, pose dragon d iter courante conservee)', flush=True)
    else:
        print('ATTENTION : pas de snapshot, le monde sera regenere (le test smooth/water vole en y=240+, independant du terrain)', flush=True)
    write_props()
    proc, lines, log = boot()
    verdict = 'NO-DATA'
    t0 = time.time()
    try:
        wait_line(lines, 'Done (', 900)
        time.sleep(3)
        for _ in range(10):
            try:
                rcon('time query daytime', timeout=10)
                break
            except Exception:
                time.sleep(2)
        rcon('dlbverify', timeout=30)
        print('dlbverify lance', flush=True)
        rec = []
        try:
            wait_any(lines, ['[DLBVERIFY] ALL PASS'], 600, rec)
            verdict = 'ALL-PASS'
        except TimeoutError:
            verdict = 'TIMEOUT'
        # echecs explicites
        fails = [l for l in rec if '[DLBVERIFY] FAIL' in l]
        passes = [l for l in rec if '[DLBVERIFY] PASS' in l or '[DLB-RINGAUDIT]' in l]
        if fails:
            verdict = 'FAIL(%d)' % len(fails)
        time.sleep(2)
    except Exception as e:
        verdict = 'CRASH: %r' % (e,)
    finally:
        try:
            stop(proc)
        except Exception:
            pass
    open(os.path.join(OUT, 'run.log'), 'w').write('\n'.join(log))
    rep = ['== DLBVERIFY %s ==' % ITER,
           'verdict : %s (wall %.1f s)' % (verdict, time.time() - t0), '']
    for l in log:
        if '[DLBVERIFY]' in l or '[DLB-RINGAUDIT]' in l:
            rep.append(l)
    open(os.path.join(OUT, 'report.txt'), 'w').write('\n'.join(rep) + '\n')
    print('\n'.join(rep[-14:]), flush=True)


if __name__ == '__main__':
    main()
