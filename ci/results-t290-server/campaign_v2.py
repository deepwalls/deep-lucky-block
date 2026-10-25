"""Campagne T290 d'optimisation — 10 sessions serveur headless.
FORKEE depuis probe3.py (version validée : boot + rcon x8 OK, monde frais).
On ne touche PAS au code socket/auth/boot : on ajoute juste la boucle
10 runs, les ordres de jeu (spawn chunks + dlbtest dragon), les marqueurs
de perf ('Can't keep up', 'Pose brute', 'Zone choisie', 'en N ms',
'tier charge T245', 'selection seche T233c') et le summary.
"""
import subprocess, threading, queue, time, socket, struct, os, signal, shutil, re, json, sys

PROJECT = '/tmp/rt/mod-project'
RUN = PROJECT + '/run'
PWD = 'probe123'
RUNS = int(os.environ.get('CAMPAIGN_RUNS', '10'))
OUT = sys.argv[1] if len(sys.argv) > 1 else '/tmp/rt/results-t290-server'
os.makedirs(OUT, exist_ok=True)


def wait_port_free(timeout=120):
    for _ in range(timeout):
        free = True
        for port in (25565, 25575):
            try:
                s = socket.create_connection(('127.0.0.1', port), timeout=1)
                s.close()
                free = False
            except OSError:
                pass
        if free:
            return True
        time.sleep(1)
    return False


def kill_server_hard():
    for pat in ('serverRunVmArgs.txt', 'GradleDaemon', 'gradlew runServer'):
        subprocess.run(['pkill', '-f', pat], capture_output=True, timeout=15)
    wait_port_free()


def rcon(cmd, password, timeout=20):
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
            d = exact(size)
            return struct.unpack('<i', d[:4])[0], d[8:-2].decode(errors='replace')
        a, _ = req(1, 3, password)
        if a == -1:
            raise RuntimeError('RCON auth refusee')
        return req(2, 2, cmd)[1]


def rcon_retry(cmd, tries=5, sleep_s=3, timeout=30):
    last = None
    for i in range(tries):
        try:
            return rcon(cmd, PWD, timeout=timeout)
        except Exception as e:
            last = e
            time.sleep(sleep_s)
    raise last


def run_session(idx):
    name = f'run-{idx:02d}'
    result = {'name': name, 't_start': time.time()}
    # --- monde frais (identique probe3) ---
    for d in ['dlbcampaign', 'dlbcampaign_nether', 'dlbcampaign_the_end']:
        p = os.path.join(RUN, d)
        if os.path.exists(p):
            shutil.rmtree(p)
    open(RUN + '/server.properties', 'w').write('\n'.join([
        'server-ip=127.0.0.1', 'server-port=25565', 'online-mode=false',
        'enable-rcon=true', 'rcon.port=25575', f'rcon.password={PWD}',
        'level-name=dlbcampaign', 'level-seed=123456789', 'view-distance=4',
        'simulation-distance=4', 'spawn-protection=0', 'max-tick-time=-1',
        'allow-flight=true', 'gamemode=creative', 'difficulty=peaceful']))
    env = dict(os.environ)
    env['GRADLE_USER_HOME'] = '/tmp/rt/.gradle'
    env['JAVA_HOME'] = '/tmp/jdk'
    env['JAVA_TOOL_OPTIONS'] = '-Xmx2g -XX:ActiveProcessorCount=2'
    proc = subprocess.Popen(['./gradlew', 'runServer', '--offline', '--console=plain', '--max-workers=2'],
                            cwd=PROJECT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, start_new_session=True, env=env)
    q = queue.Queue()
    log = []

    def consume():
        for l in proc.stdout:
            q.put(l)
            log.append(l.rstrip())
    threading.Thread(target=consume, daemon=True).start()

    def wait_for(marker, timeout, harvest=None):
        pending = set(marker if isinstance(marker, list) else [marker])
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                l = q.get(timeout=1)
            except queue.Empty:
                if proc.poll() is not None:
                    raise RuntimeError(f'Server exited rc={proc.returncode}')
                continue
            if harvest is not None and any(k in l for k in (
                    "Can't keep up", 'Zone choisie a ', 'Pose brute',
                    'tier charge T245', 'selection seche T233c',
                    '[DLB T289]', '[DLB T290]', 'prepZone ',
                    'Post-process de', 'decorate + post de',
                    'DLB-RINGAUDIT', ' en ')):
                harvest.append(l.strip())
            pending = {item for item in pending if item not in l}
            if not pending:
                return
        raise TimeoutError(f'No marker {marker} in {timeout}s')

    phases = []
    try:
        wait_for('Done (', 420)
        # --- warm-up RCON identique probe3 (connexions rapides OK) ---
        warm = False
        last = None
        for i in range(10):
            try:
                rcon('time query daytime', PWD)
                warm = True
                break
            except Exception as e:
                last = e
                time.sleep(2)
        if not warm:
            raise RuntimeError(f'warmup KO: {last!r}')
        t0 = time.monotonic()
        rcon_retry('gamerule spawnChunkRadius 16', tries=3)
        rcon_retry('setworldspawn 1200 64 0', tries=3)
        # attendre les chunks des coins (borne 300 s)
        corners = False
        dead = time.monotonic() + 300
        while time.monotonic() < dead:
            try:
                rcon('execute if loaded 960 64 -240 if loaded 1440 64 240'
                     ' if loaded 960 64 240 if loaded 1440 64 -240'
                     ' run say DLCORNERS', PWD, timeout=20)
                if any('DLCORNERS' in ln for ln in log[-400:]):
                    corners = True
                    break
            except Exception:
                pass
            time.sleep(8)
        result['corners'] = corners
        time.sleep(3)
        # ==== TIR ====
        rcon('execute positioned 1200 90 0 run dlbtest dragon', PWD, timeout=240)
        result['t_cmd'] = round(time.monotonic() - t0, 1)
        wait_for('Zone choisie a ', 900, phases)
        result['search_done_s'] = round(time.monotonic() - t0, 1)
        total = None
        try:
            wait_for(['Post-process de', 'decorate + post de'], 900, phases)
            total = round(time.monotonic() - t0, 1)
        except Exception as e:
            result['total_timeout'] = str(e)[:120]
        result['total_s'] = total
        site = None
        for ln in phases + log[-500:]:
            m = re.search(r'Zone choisie a (-?\d+),(-?\d+)', ln)
            if m:
                site = (int(m.group(1)), int(m.group(2)))
        result['site'] = site
        result['dist'] = round(((site[0] - 1200) ** 2 + site[1] ** 2) ** 0.5, 1) if site else None
        if site:
            try:
                rcon(f'dlbringaudit {site[0]} {site[1]}', PWD, timeout=120)
                wait_for('[DLB-RINGAUDIT]', 60, phases)
                result['audit'] = 'done'
            except Exception as e:
                result['audit'] = str(e)[:120]
    except Exception as e:
        result['fatal'] = str(e)[:300]
    finally:
        # ---- metriques log ----
        behind = []
        for l in log:
            if "Can't keep up" in l:
                m = re.search(r'Running (\d+)ms or (\d+) ticks behind', l)
                if m:
                    behind.append(int(m.group(1)))
        result['cku_count'] = len(behind)
        result['cku_max_ms'] = max(behind) if behind else 0
        result['cku_sum_ms'] = sum(behind)
        ms_hits = []
        for l in log:
            for m in re.finditer(r' en (\d+) ms', l):
                ms_hits.append(int(m.group(1)))
        result['phase_ms_top'] = sorted(set(ms_hits), reverse=True)[:15]
        tier_ms = sec_ms = pose_ms = s_s = None
        for l in log:
            mt = re.search(r'tier charge T245 : (\d+) ms', l)
            if mt and tier_ms is None:
                tier_ms = int(mt.group(1))
            msr = re.search(r'selection seche T233c : (\d+) ms', l)
            if msr and sec_ms is None:
                sec_ms = int(msr.group(1))
            mp = re.search(r'Pose brute .* en (\d+)ms', l)
            if mp:
                pose_ms = int(mp.group(1))
            m3 = re.search(r'(\d+) s de recherche', l)
            if m3:
                s_s = int(m3.group(1))
        result['tier_ms'] = tier_ms
        result['sec_ms'] = sec_ms
        result['pose_ms'] = pose_ms
        result['search_s'] = s_s
        result['wall_s'] = round(time.time() - result['t_start'], 1)
        open(os.path.join(OUT, name + '-markers.log'), 'w').write('\n'.join(phases) + '\n')
        open(os.path.join(OUT, name + '.log'), 'w').write('\n'.join(log))
        # ---- stop propre puis dur ----
        try:
            rcon('stop', PWD, timeout=20)
        except Exception:
            pass
        try:
            proc.wait(timeout=90)
        except subprocess.TimeoutExpired:
            pass
        if proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        kill_server_hard()
        time.sleep(3)
    return result


def main():
    kill_server_hard()
    results = []
    for i in range(1, RUNS + 1):
        print(f'===== SESSION {i}/{RUNS} =====', flush=True)
        try:
            r = run_session(i)
        except Exception as e:
            r = {'name': f'run-{i:02d}', 'fatal': str(e)[:300]}
        results.append(r)
        print(json.dumps(r, ensure_ascii=False), flush=True)
        open(os.path.join(OUT, 'results.json'), 'w').write(json.dumps(results, indent=1, ensure_ascii=False))
    ok = [r for r in results if r.get('total_s')]
    lines = ['NOM | site | dist | recherche_s | tier_ms | sec_ms | pose_ms | total_s | cku_n | cku_max_ms | cku_sum_ms']
    for r in results:
        lines.append(' | '.join(str(x) for x in [
            r['name'], r.get('site'), r.get('dist'), r.get('search_s'),
            r.get('tier_ms'), r.get('sec_ms'), r.get('pose_ms'),
            r.get('total_s'), r.get('cku_count'), r.get('cku_max_ms'),
            r.get('cku_sum_ms')]))
    if ok:
        tot = [r['total_s'] for r in ok]
        ck = [r['cku_max_ms'] for r in results]
        lines.append(f"TOTAL moyen={sum(tot)/len(tot):.1f}s min={min(tot)} max={max(tot)} (n={len(ok)})")
        lines.append(f"CKU max moyen={sum(ck)/len(ck):.0f}ms max={max(ck)}")
    open(os.path.join(OUT, 'summary.txt'), 'w').write('\n'.join(lines) + '\n')
    print('\n'.join(lines), flush=True)


if __name__ == '__main__':
    main()
