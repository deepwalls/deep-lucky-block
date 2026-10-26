#!/usr/bin/env python3
"""Banc learn-and-retry T291+ : mesure fine d'UNE pose de dragon, monde fige.

Usage : python3 ci/bench_dragon.py <iteration_name> [--fresh]

- --fresh : supprime le monde + snapshot, regenere tout (mesure incluant worldgen).
- defaut  : restaure le snapshot (monde VIERGE pre-genere autour de la zone) puis
  mesure la meme pose, reptitions comparables au chunk pres.

Sorties :
  /tmp/bench/<iteration>/run.log  (stdout serveur complet)
  /tmp/bench/<iteration>/report.txt (attribution : phases en N ms, trous de
  silence > 600 ms entre lignes DLB, DLB-PERF, CKU, total cmd->post-process)

Le flag -Ddlb.debug=true est pose via JAVA_TOOL_OPTIONS et le serveur est lance
avec --no-daemon (le daemon Gradle survivant n'herite pas des env du client :
cause historique de l'invisibilite des logs DebugLog dans la campagne T290).
"""
import json, os, queue, re, shutil, signal, socket, struct, subprocess, sys, threading, time
from pathlib import Path

PROJECT = Path('/tmp/rt/mod-project')
RUN = PROJECT / 'run'
WORLD = 'dlbbench'
SNAP = Path('/tmp/bench/world_snapshot')
OUT_BASE = Path('/tmp/bench')
PWD = 'bench123'
FRESH = '--fresh' in sys.argv
ITER = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith('-') else 'iter'
OUT = OUT_BASE / ITER
OUT.mkdir(parents=True, exist_ok=True)


def kill_strays():
    for pat in ('serverRunVmArgs.txt',):
        for p in os.popen("pgrep -f '%s'" % pat).read().split():
            try:
                os.kill(int(p), signal.SIGKILL)
            except Exception:
                pass
    time.sleep(2)


def rcon(cmd, timeout=60):
    with socket.create_connection(('127.0.0.1', 25575), timeout=15) as s:
        s.settimeout(timeout)
        def exact(n):
            d = b''
            while len(d) < n:
                p = s.recv(n - len(d))
                if not p:
                    raise EOFError('closed')
                d += p
            return d
        def req(i, k, t):
            b = struct.pack('<ii', i, k) + t.encode() + b'\0\0'
            s.sendall(struct.pack('<i', len(b)) + b)
            size = struct.unpack('<i', exact(4))[0]
            body = exact(size)
            return struct.unpack('<i', body[:4])[0], body[8:-2].decode(errors='replace')
        a, _ = req(1, 3, PWD)
        if a == -1:
            raise RuntimeError('auth refusee')
        return req(2, 2, cmd)[1]


def write_props():
    (RUN / 'server.properties').write_text('\n'.join([
        'server-ip=127.0.0.1', 'server-port=25565', 'online-mode=false',
        'enable-rcon=true', 'rcon.port=25575', 'rcon.password=' + PWD,
        'level-name=' + WORLD, 'level-seed=123456789', 'view-distance=4',
        'simulation-distance=4', 'spawn-protection=0', 'max-tick-time=-1',
        'gamemode=creative', 'difficulty=peaceful']))


def boot():
    lines = queue.Queue()
    log = []
    env = dict(os.environ)
    env['GRADLE_USER_HOME'] = '/tmp/rt/.gradle'
    env['JAVA_HOME'] = '/tmp/jdk'
    env['JAVA_TOOL_OPTIONS'] = '-Xmx2g -XX:ActiveProcessorCount=2 -Ddlb.debug=true'
    proc = subprocess.Popen(['./gradlew', 'runServer', '--offline', '--console=plain',
                             '--max-workers=2', '--no-daemon'],
                            cwd=PROJECT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, start_new_session=True, env=env)

    def consume():
        for l in proc.stdout:
            lines.put(l)
            log.append(l.rstrip())
    threading.Thread(target=consume, daemon=True).start()
    return proc, lines, log


def wait_line(lines, contains, timeout, harvest=None):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            l = lines.get(timeout=1)
        except queue.Empty:
            continue
        if harvest is not None:
            harvest.append(l.rstrip())
        if contains in l:
            return l.rstrip()
    raise TimeoutError(contains + f' ({timeout}s)')


def wait_any(lines, needles, timeout, harvest=None):
    pending = list(needles)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and pending:
        try:
            l = lines.get(timeout=1)
        except queue.Empty:
            continue
        if harvest is not None:
            harvest.append(l.rstrip())
        for n in list(pending):
            if n in l:
                pending.remove(n)
    if pending:
        raise TimeoutError(str(pending))
    return


def stop(proc):
    try:
        rcon('stop', timeout=20)
    except Exception:
        pass
    try:
        proc.wait(timeout=60)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except Exception:
            pass
    kill_strays()


def ts_of(line):
    m = re.match(r'\[(\d+):(\d+):(\d+)(?:\.(\d+))?\]', line.strip())
    if not m:
        m = re.match(r'\[\d+Oct\d+ (\d+):(\d+):(\d+)(?:\.(\d+))?\]', line.strip())
    if not m:
        return None
    h, mi, s, ms = int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4)
    return h * 3600 + mi * 60 + s + (int(ms) / 1000.0 if ms else 0.0)


def analyze(log, t_cmd, t_done):
    modlines = []  # (ts, line) pour lignes mod/logging utiles
    for l in log:
        if any(k in l for k in ('DLB', 'STRUCT', 'deep', "Can't keep up")):
            t = ts_of(l)
            if t is not None:
                modlines.append((t, l.strip()))
    out = []
    out.append('== trous de silence (>0.6 s) entre lignes utiles ==')
    prev = None
    for t, l in modlines:
        if prev is not None and t - prev > 0.6:
            out.append(f'  +{t - prev:6.1f} s  -> {l[:120]}')
        prev = t
    out.append('== phases « en N ms » (top 12) ==')
    ms = []
    for l in log:
        for m in re.finditer(r' en (\d+) ms', l):
            ms.append((int(m.group(1)), l.strip()[:130]))
    for v, l in sorted(ms, reverse=True)[:12]:
        out.append(f'  {v:6d} ms  {l}')
    out.append('== CKU ==')
    for l in log:
        if "Can't keep up" in l:
            out.append('  ' + l.strip()[:150])
    out.append('== DLB-PERF ==')
    for l in log:
        if 'DLB-PERF' in l:
            out.append('  ' + l.strip())
    if t_cmd is not None and t_done is not None:
        out.append(f'== TOTAL dragon cmd -> post-process : {t_done - t_cmd:.1f} s ==')
    return '\n'.join(out)


def generate_region(lines):
    """Pre-genere la region des candidats par forceload (generation vanilla,
    verifiee par sondes 'if loaded' sur une grille, puis sauvegarde)."""
    rects = ('1400 40 1630 270', '900 60 1130 300', '1100 -150 1350 90', '2100 -180 2250 -60', '2100 60 2250 270', '1130 150 1400 380', '2000 -220 2320 -40')
    for rng in rects:
        x1, z1, x2, z2 = [int(v) for v in rng.split()]
        rcon(f'forceload add {x1} {z1} {x2} {z2}', timeout=60)
    # sondes sur une grille grossiere couvrant les trois rectangles
    probes = []
    for rng in rects:
        x1, z1, x2, z2 = [int(v) for v in rng.split()]
        for gx in range(x1 + 32, x2, 96):
            for gz in range(z1 + 32, z2, 96):
                probes.append((gx, gz))
    # Les sondes 'say' ne renvoient RIEN dans la reponse RCON -- on lit donc
    # les marqueurs WLMISS dans le journal serveur entre deux positions (fix
    # iter 08 : la reponse vide etait lue 'loaded' et le snapshot capturait un
    # monde partiellement genere -> 6 s de worldgen pendant la pose).
    log_path = RUN / 'logs' / 'latest.log'
    deadline = time.monotonic() + 1200
    while time.monotonic() < deadline:
        pos0 = log_path.stat().st_size if log_path.exists() else 0
        for cx, cz in probes:
            try:
                rcon(f'execute unless loaded {cx} 64 {cz} run say WLMISS', timeout=20)
            except Exception:
                pass
        time.sleep(3)
        chunk = log_path.read_text(errors='replace')[pos0:] if log_path.exists() else ''
        missing = chunk.count('WLMISS')
        print(f'sondes chargement : {missing} chunk(s) en gen sur {len(probes)}', flush=True)
        if missing == 0:
            break
        time.sleep(5)
    else:
        print('ATTENTION : deadline de generation atteinte, snapshot REFUSE (monde incomplet) -- relancer --fresh', flush=True)
        rcon('forceload remove all', timeout=60)
        rcon('stop', timeout=20)
        sys.exit(2)
    rcon('forceload remove all', timeout=60)
    time.sleep(2)
    rcon('save-all flush', timeout=180)
    time.sleep(2)


def main():
    kill_strays()
    bench_world = RUN / WORLD
    if FRESH and bench_world.exists():
        for d in (WORLD, WORLD + '_nether', WORLD + '_the_end'):
            p = RUN / d
            if p.exists():
                shutil.rmtree(p)
        if SNAP.exists():
            shutil.rmtree(SNAP)
    snapshot_ok = SNAP.exists()

    if snapshot_ok:
        rs = subprocess.run(['rm', '-rf', str(bench_world)] + [str(RUN / d) for d in
                            (WORLD + '_nether', WORLD + '_the_end')], capture_output=True)
        shutil.copytree(SNAP / WORLD, bench_world)
        for d in (WORLD + '_nether', WORLD + '_the_end'):
            if (SNAP / d).exists():
                shutil.copytree(SNAP / d, RUN / d)
        print(f'snapshot restaure : {SNAP}', flush=True)

    write_props()
    proc, lines, log = boot()
    t_cmd = t_done = None
    try:
        wait_line(lines, 'Done (', 600)
        time.sleep(3)
        # warmup rcon
        for _ in range(10):
            try:
                rcon('time query daytime', timeout=10)
                break
            except Exception:
                time.sleep(2)

        if not snapshot_ok:
            print('pas de snapshot : generation de la region...', flush=True)
            generate_region(lines)
            # snapshot du monde VIERGE (chunks generes, aucune structure) :
            # les mesures ulterieures partent d'un etat identique.
            rcon('save-all flush', timeout=120)
            time.sleep(2)
            stop(proc)
            SNAP.mkdir(parents=True, exist_ok=True)
            for d in (WORLD, WORLD + '_nether', WORLD + '_the_end'):
                p = RUN / d
                if p.exists():
                    shutil.copytree(p, SNAP / d)
            print(f'snapshot cree (monde vierge regenere) : {SNAP}', flush=True)
            proc, lines, log = boot()
            wait_line(lines, 'Done (', 600)
            time.sleep(3)
            for _ in range(10):
                try:
                    rcon('time query daytime', timeout=10)
                    break
                except Exception:
                    time.sleep(2)

        # ==== MESURE ====
        rec = []
        t0 = time.time()
        t_cmd = time.time()
        rcon('execute positioned 1200 90 0 run dlbtest dragon', timeout=30)
        print('dragon lance', flush=True)
        wait_any(lines, ['Post-process de dragon', 'decorate + post de dragon'], 600, rec)
        t_done = time.time()
        total_wall = t_done - t0
        print(f'post-process vu en {t_done - t_cmd:.1f} s (wall session {total_wall:.1f} s)', flush=True)
        time.sleep(2)
    except Exception as e:
        print('BENCH FAIL :', e, flush=True)
    finally:
        stop(proc)

    (OUT / 'run.log').write_text('\n'.join(log))
    report = analyze(log, t_cmd, t_done)
    (OUT / 'report.txt').write_text(report + '\n')
    print('\n' + report, flush=True)


if __name__ == '__main__':
    main()
