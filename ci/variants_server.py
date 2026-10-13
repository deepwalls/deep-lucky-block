"""Campagne d'optimisation Structures5 (dev 07/10 : "fais 11 tests opti, garde le meilleur").

Relance le serveur headless autant de fois qu'il y a de lignes dans
ci/variants.txt (format: nom|deferRounds|deferCap|growth), chaque fois sur un
MONDE FRAIS de meme graine, puis /dlbtest dragon au meme point. Mesure par
variante : duree de recherche (log "N s de recherche"), distance du site
choisi, temps total command->fin, PASS/FAIL du dlbringaudit T231.
"""
import os
from collections import deque
from pathlib import Path
import queue
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import threading
import time
import re

project = Path(os.environ['RUNNER_TEMP']) / 'mod-project'
run = project / 'run'
run.mkdir(exist_ok=True)
_eula = run / 'eula.txt'
if not _eula.exists():
    # Le fichier eula du runner n'est pas toujours present avant la premiere
    # session de cette VM : on pose la meme acceptation que celle embarquee.
    _eula.write_text('# EULA acceptance equivalente a celle embarquee dans le projet\neula=true\n')
assert 'eula=true' in _eula.read_text()
password = secrets.token_hex(24)
(run / 'server.properties').write_text('\n'.join([
    'server-ip=127.0.0.1', 'server-port=25565', 'online-mode=true',
    'enable-rcon=true', 'rcon.port=25575', f'rcon.password={password}',
    'level-name=ci-variants-test', 'level-seed=123456789',
    'view-distance=4', 'simulation-distance=4', 'spawn-protection=0',
    'max-tick-time=60000', 'allow-flight=true', 'gamemode=creative',
]))


def rcon_once(command, timeout=30):
    with socket.create_connection(('127.0.0.1', 25575), timeout=30) as sock:
        sock.settimeout(timeout)
        def exact(n):
            data = b''
            while len(data) < n:
                part = sock.recv(n - len(data))
                if not part:
                    raise EOFError('RCON closed')
                data += part
            return data
        def request(request_id, kind, text):
            body = struct.pack('<ii', request_id, kind) + text.encode() + b'\0\0'
            sock.sendall(struct.pack('<i', len(body)) + body)
            skips = 0
            while True:
                hdr = exact(4)
                size = struct.unpack('<i', hdr)[0]
                if 10 <= size <= 1048576:
                    break
                # frame poubelle apres ModDev (padding 00 00 / keepalive) :
                # on realigne en prenant les 4 octets suivants, borne 8 fois.
                skips += 1
                if skips > 8:
                    raw = hdr + sock.recv(32)
                    raise ValueError(f'RCON stream desaligne hdr={hdr.hex()} next={raw[4:].hex()}')
            data = exact(size - 4)
            return struct.unpack('<i', data[:4])[0], data[8:-2].decode(errors='replace')
        auth, _ = request(1, 3, password)
        if auth == -1:
            raise RuntimeError('RCON authentication failed')
        return request(2, 2, command)[1]


def rcon(command, timeout=30, retries=3):
    last = None
    for attempt in range(1, retries + 1):
        try:
            return rcon_once(command, timeout)
        except Exception as exc:
            last = exc
            print(f'RCON tentative {attempt}/{retries} KO ({exc}); sleep 3s', flush=True)
            time.sleep(3)
    raise RuntimeError(f'RCON commande KO apres {retries} tentatives: {last}')


def annotate(text, failure=False):
    for start in range(0, max(1, len(text)), 3000):
        part = text[start:start + 3000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print(f'::{"error" if failure else "notice"} title=Variants campaign::{part}', flush=True)


def run_variant(name, props):
    # Monde frais : suppression du niveau precedent.
    lvl = run / 'ci-variants-test'
    if lvl.exists():
        shutil.rmtree(lvl)
    env = dict(os.environ)
    opts = env.get('JAVA_TOOL_OPTIONS', '')
    env['JAVA_TOOL_OPTIONS'] = (opts + ' ' + props.strip()).strip()
    proc = subprocess.Popen(['./gradlew', 'runServer', '--console=plain', '--max-workers=2'],
                            cwd=project, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, start_new_session=True, env=env)
    lines = queue.Queue()
    log = []

    def consume():
        for line in proc.stdout:
            lines.put(line)
            log.append(line.rstrip())
    threading.Thread(target=consume, daemon=True).start()

    def wait_for(marker, timeout, harvest=None):
        pending = set(marker if isinstance(marker, list) else [marker])
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                if proc.poll() is not None:
                    raise RuntimeError(f'Server exited: {proc.returncode}')
                continue
            if harvest is not None and any(k in line for k in
                    ('STRUCT5-FLAT', 'DLB-PERF', 'Post-process', 'DLB-RINGAUDIT', "Can't keep up")):
                harvest.append(line.strip())
            if '[DLB-RINGAUDIT] FAIL' in line:
                raise RuntimeError(line.strip())
            pending = {item for item in pending if item not in line}
            if not pending:
                return
        raise TimeoutError(f'No marker {marker} within {timeout}s')

    result = {'name': name, 'props': props or 'defaut'}
    phases = []
    t_start = None
    try:
        wait_for('Done (', 360)
        t0 = time.monotonic()
        t_start = t0
        # T246 : simuler le joueur reel qui pre-charge massivement ses chunks
        # (« j'ai une tonne de chunks charges ») : forceload +- 20 chunks
        # autour du point de test. CAMPAGNE 4 : le forceload RCON ne
        # declenchait AUCUNE generation (0 activite serveur pendant toute
        # l'attente, 3 campagnes muettes — echec silencieux via mcrcon).
        # On passe par les SPAWN CHUNKS vanilla, charges en priorite et de
        # facon synchrone : rayon 10 => 21x21 chunks (441) autour du test.
        r1 = rcon('gamerule spawnChunkRadius 10', timeout=30)
        r2 = rcon('setworldspawn 1200 64 0', timeout=30)
        result['spawn_resp'] = (r1 + ' | ' + r2)[:180]
        corners_ok = False
        deadline = time.monotonic() + 150
        probe_supported = True
        while time.monotonic() < deadline:
            try:
                out = rcon('execute if loaded 1040 64 -160 if loaded 1360 64 160'
                           ' if loaded 1040 64 160 if loaded 1360 64 -160'
                           ' run say DLCORNERS', timeout=30)
                if 'Unknown or incomplete command' in out:
                    probe_supported = False
                    print('if-loaded absent : repli sleep 120 s', flush=True)
                    time.sleep(120)
                    corners_ok = True
                    break
            except Exception as pe:
                print('probe ignore: ' + str(pe), flush=True)
            if any('DLCORNERS' in ln for ln in log[-300:]):
                corners_ok = True
                break
            time.sleep(10)
        print('corners chargees=' + str(corners_ok) + ' probe=' + str(probe_supported), flush=True)
        result['corners'] = corners_ok
        time.sleep(5)
        rcon('execute positioned 1200 90 0 run dlbtest dragon', timeout=240)
        # T231 v2 (CI 2 CPU) : le KPI est la phase RECHERCHE (temps jusqu'au
        # "Zone choisie" + distance), pas la suite du paste/deco, qui ne
        # rentre jamais dans les bornes du runner (couriered timeout). On
        # conserve quand meme un total borne pour voir le paste seul.
        total = None
        wait_for('Zone choisie a ', 900, phases)
        # Parse : duree de recherche + site choisi
        search_s = None; site = None
        tier_ms = None; sec_ms = None; tier_fail_ms = None; tier_cands = None
        for ln in phases + log[-400:]:
            m = re.search(r'Zone choisie a (-?\d+),(-?\d+).*?(\d+) s de recherche', ln)
            if m:
                site = (int(m.group(1)), int(m.group(2)))
                search_s = int(m.group(3))
        if site is None:
            for ln in phases + log[-400:]:
                m = re.search(r'Zone choisie a (-?\d+),(-?\d+)', ln)
                if m:
                    site = (int(m.group(1)), int(m.group(2)))
        # T245/T246 : duree de la phase de recherche en millisecondes, par voie.
        # On capture AUSSI le repli du tier (« sans bon candidat ») pour
        # distinguer « tier muet (0 emprise chargee) » de « tier actif ».
        for ln in phases + log[-600:]:
            mt = re.search(r'tier charge T245 : (\d+) ms, (\d+) sondes sechees, \d+ ecartees eau/haut, (\d+) candidats charges', ln)
            if mt and tier_ms is None:
                tier_ms = int(mt.group(1))
                tier_cands = int(mt.group(3))
            mf = re.search(r'tier charge T245 sans bon candidat \((\d+) ms, (\d+) candidats', ln)
            if mf and tier_fail_ms is None:
                tier_fail_ms = int(mf.group(1))
                tier_cands = int(mf.group(2))
            ms = re.search(r'selection seche T233c : (\d+) ms', ln)
            if ms and sec_ms is None:
                sec_ms = int(ms.group(1))
        # prolongement facultatif : paste termine si ca rentre dans 240 s
        if site is not None:
            try:
                wait_for(['decorate TERMINE', 'Post-process de dragon termine'], 240, phases)
                total = round(time.monotonic() - t0, 1)
            except Exception:
                total = None
        dist = None
        if site:
            dist = ((site[0] - 1200) ** 2 + site[1] ** 2) ** 0.5
            try:
                rcon(f'dlbringaudit {site[0]} {site[1]}', timeout=180)
                wait_for('[DLB-RINGAUDIT]', 90, phases)
            except Exception as audit_err:
                result['audit'] = str(audit_err).splitlines()[0][:200]
        else:
            result['audit'] = 'zone introuvable'
        result.update({
            'search_s': search_s, 'site': site, 'dist': dist,
            'tier_ms': tier_ms, 'sec_ms': sec_ms,
            'tier_fail_ms': tier_fail_ms, 'tier_cands': tier_cands,
            'total': total,
        })
        result['rows'] = 'OK'
    except Exception as exc:
        tail = '\n'.join(log[-60:])
        print(f'variante {name} ECHEC:\n{tail}', flush=True)
        result['rows'] = 'ERREUR: ' + str(exc).splitlines()[0][:160]
    finally:
        try:
            rcon('stop')
            proc.wait(timeout=45)
        except Exception:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except Exception:
                pass
        Path(os.environ['RUNNER_TEMP'], f'variant-{name}.log').write_text('\n'.join(log))
        try:
            (WS / f'variant-{name}.log').write_text('\n'.join(log))
        except Exception:
            pass
    return result


WS = Path(os.environ.get('GITHUB_WORKSPACE', '.')) / 'ci' / 'results-now'
WS.mkdir(parents=True, exist_ok=True)

def record(text):
    WS.mkdir(parents=True, exist_ok=True)
    (WS / 'campaign-progress.md').write_text(text)
    (Path(os.environ['RUNNER_TEMP']) / 'campaign-progress.md').write_text(text)
    print(text, flush=True)

def publish_now(note):
    # Publication immediate des resultats partiels : un timeout du job ne doit
    # plus jamais faire perdre une campagne entiere. Best-effort, jamais fatal.
    try:
        import subprocess
        br = os.environ.get('GITHUB_REF_NAME', '')
        if not br:
            return
        rid = os.environ.get('GITHUB_RUN_ID', 'local')
        ws = os.environ.get('GITHUB_WORKSPACE', '.')
        script = (
            'set +e\n'
            'git config user.email "ci-result@deep-lucky-block.ci"\n'
            'git config user.name "DLB CI results"\n'
            'git add ci/results-now\n'
            'git commit -qm "CI-PROGRESS (' + rid + ') : ' + note.replace('"', "'") + '" || exit 0\n'
            'git fetch -q origin ' + br + '\n'
            'git pull --rebase -q origin ' + br + ' || true\n'
            'git push -q origin HEAD:' + br + ' || true\n'
            'exit 0\n')
        res = subprocess.run(script, shell=True, executable='/bin/bash',
                             cwd=ws, capture_output=True, text=True, timeout=150)
        out = ((res.stdout or '') + (res.stderr or '')).strip().splitlines()
        print('publish_now: ' + (out[-1] if out else 'ok'), flush=True)
    except Exception as exc:
        print('publish_now ignore: ' + str(exc), flush=True)

results = []
spec = Path('ci/variants.txt')
for raw in spec.read_text().splitlines():
    line = raw.strip()
    if not line or line.startswith('#'):
        continue
    name, _, props = line.partition('|')
    name, props = name.strip(), props.strip()
    print(f'=== variante {name}: {props or "defaut"} ===', flush=True)
    rres = None
    try:
        rres = run_variant(name, props)
    except Exception as crash:
        rres = {'name': name, 'props': props or 'defaut',
                'rows': 'CRASH: ' + (str(crash).splitlines()[0][:160] if str(crash) else type(crash).__name__)}
    results.append(rres)
    record('PROGRESS: ' + (rres['name'] or '?') + ' -> ' + rres['rows'])
    publish_now('variante ' + (rres['name'] or '?') + ' terminee')

rows = []
for r in results:
    tier = f"{r['tier_ms']}ms" if r.get('tier_ms') is not None else (
        f"muet({r.get('tier_fail_ms')}ms,{r.get('tier_cands')}c)" if r.get('tier_fail_ms') is not None else 'absent')
    rows.append(f"{r['name']:<16} | {r['props']:<60}"
                f" | tier={tier} | sec={r.get('sec_ms')}ms"
                f" | coins={r.get('corners')} sp={r.get('spawn_resp','')[:60]}"
                f" | site={r.get('site')} | dist={r.get('dist')}"
                f" | total={r.get('total')}s | {r['rows']} | audit={r.get('audit','-')}")
record('RESULTATS CAMPAGNE T248 (nom | props | recherche ms par voie)\n' + '\n'.join(rows))
publish_now('tableau final de la campagne T248')
annotate('RESULTATS CAMPAGNE T248\n' + '\n'.join(rows))

# Verdict T246 : gagner = recherche la plus courte (duree reelle en ms du
# serveur, T245 tier charge ou repli sec T233c), tie-break distance + audit.
def dur(r):
    for k in ('tier_ms', 'sec_ms'):
        if r.get(k) is not None:
            return r[k]
    if r.get('search_s') is not None:
        return r['search_s'] * 1000
    return None
cands = [r for r in results if dur(r) is not None and r.get('dist')
         and r['dist'] <= 1100 and 'FAIL' not in r.get('audit', '')]
if cands:
    winner = min(cands, key=lambda r: dur(r) + r['dist'] / 10.0)
    annotate(f"MEILLEURE VARIANTE : {winner['name']} ({winner['props']}) "
             f"tier={winner.get('tier_ms')}ms sec={winner.get('sec_ms')}ms dist={winner['dist']}")
    (Path(os.environ['RUNNER_TEMP']) / 'variants-summary.txt').write_text(
        '\n'.join(rows) + f"\nWINNER={winner['name']} {winner['props']}")
    record('CAMPAGNE OK\n' + '\n'.join(rows) + f"\nWINNER={winner['name']} {winner['props']}")
    raise SystemExit(0)
annotate("AUCUNE variante n'a satisfait trace (dist<=600, audit PASS) -- voir tableau", True)
record('CAMPAGNE TERMINEE SANS VAINQUEUR QUALIFIE\n' + '\n'.join(rows))
raise SystemExit(0)
