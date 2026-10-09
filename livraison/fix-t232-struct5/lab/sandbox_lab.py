#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# T232 — lab serveur sandbox : boot gradle runServer (exclusions obligatoires),
# RCON param (sim/view), pregen court, scenario single/queue/burst, mesure
# search + paste + total. 1 structure = 1 mn max ; 1 test serveur = 15 mn max.
import os, re, sys, json, time, socket, struct, subprocess, threading
from pathlib import Path

DEFAULT_TIMEOUT_S = 600   # 1 structure : 1 mn max pour le joueur, 10 mn harnais
WATCHDOG_TEST_S = 900     # 1 test serveur = 15 mn max

class Rcon:
    def __init__(self, host, port, pw):
        self.host, self.port, self.pw = host, int(port), pw
        self.sock = None
        self._id = 0
    def connect(self, retries=30, delay=2.0):
        last = None
        for _ in range(retries):
            try:
                self.sock = socket.create_connection((self.host, self.port), timeout=10)
                self.sock.settimeout(30)
                self._send(3, self.pw)
                t, rid, body = self._recv()
                if rid == -1: raise RuntimeError('RCON auth refusee')
                return
            except Exception as e:
                last = e
                time.sleep(delay)
        raise RuntimeError(f'RCON injoignable: {last}')
    def _send(self, typ, payload):
        self._id += 1
        data = struct.pack('<ii', self._id, typ) + payload.encode('utf-8') + b'\x00\x00'
        self.sock.sendall(struct.pack('<i', len(data)) + data)
        return self._id
    def _recv(self):
        def rd(n):
            b = b''
            while len(b) < n:
                c = self.sock.recv(n - len(b))
                if not c: raise RuntimeError('connexion coupee')
                b += c
            return b
        ln = struct.unpack('<i', rd(4))[0]
        body = rd(ln)
        rid, typ = struct.unpack('<ii', body[:8])
        return typ, rid, body[8:-2].decode('utf-8', 'replace')
    def cmd(self, c):
        self._send(2, c)
        _t, _r, body = self._recv()
        return body
    def cmd_retry(self, c, tries=5, delay=3):
        # les commandes rcon tombent transitoirement quand le thread principal
        # sature juste apres boot (pregen); on retente apres reconnexion.
        for i in range(tries):
            try:
                return self.cmd(c)
            except Exception:
                if i == tries - 1:
                    raise
                time.sleep(delay)
                try: self.close()
                except Exception: pass
                self.connect(retries=10, delay=3)
    def close(self):
        try:
            if self.sock: self.sock.close()
        except Exception: pass

class Server:
    def __init__(self, project, run_dir):
        self.project = Path(project)
        self.run = Path(run_dir)
        self.proc = None
        self.log_lines = []
        self.log_path = self.run / 'logs' / 'latest.log'
    def set_props(self, **kv):
        p = self.run / 'server.properties'
        text = p.read_text() if p.exists() else ''
        for k, v in kv.items():
            if re.search(rf'(?m)^{re.escape(k)}=.*$', text):
                text = re.sub(rf'(?m)^{re.escape(k)}=.*$', f'{k}={v}', text)
            else:
                text += f'\n{k}={v}\n'
        p.write_text(text)
    def start(self, vm_extra=''):
        env = os.environ.copy()
        # lancement JAVA DIRECT (pas de gradle : l'env. sandbox interdit les
        # forks de JVM). boot_server.sh est genere par rebuild_lab.sh et porte
        # -Xmx2200m en ligne de commande ; les props du test (-Ddlb.*) passent
        # par DLB_VM_EXTRA (substituees au bon endroit du script : sinon les
        # valeurs default du script ecrasent celles du test — t07/t08/t09/t10).
        env['DLB_VM_EXTRA'] = vm_extra.strip()
        self.proc = subprocess.Popen(
            ['bash', str(self.project / 'boot_server.sh')],
            cwd=self.project, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, start_new_session=True)
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()
    def _read(self):
        try:
            for line in self.proc.stdout:
                self.log_lines.append(line)
        except Exception: pass
    def wait_done(self, timeout=420):
        t0 = time.time()
        while time.time() - t0 < timeout:
            joined = ''.join(self.log_lines[-200:])
            if 'Done (' in joined:
                return True
            if self.proc.poll() is not None:
                return False
            time.sleep(2)
        return False
    def log_since_boot(self):
        try:
            if self.log_path.exists():
                return self.log_path.read_text(errors='replace')
        except Exception: pass
        return ''.join(self.log_lines)
    def stop(self, rcon=None):
        try:
            if rcon: rcon.cmd('stop')
        except Exception: pass
        t0 = time.time()
        while self.proc and self.proc.poll() is None and time.time() - t0 < 60:
            time.sleep(1)
        if self.proc and self.proc.poll() is None:
            try:
                self.proc.kill()
            except Exception: pass

TESTS = [
    # name, sim, view, vm props, scenario
    ('t01-ref-sim10-view8',   '10', '8',  '-Ddlb.chunks.workers=4 -Ddlb.chunks.pendingCap=2048 -Ddlb.debug=1', 'single'),
    ('t0x-far-safety',        '10', '8',  '-Ddlb.chunks.workers=4 -Ddlb.chunks.pendingCap=2048 -Ddlb.debug=1', 'far'),
    ('t02-sim4-view8',        '4',  '8',  '-Ddlb.chunks.workers=4 -Ddlb.chunks.pendingCap=2048 -Ddlb.debug=1', 'single'),
    ('t03-sim10-view4',       '10', '4',  '-Ddlb.chunks.workers=4 -Ddlb.chunks.pendingCap=2048 -Ddlb.debug=1', 'single'),
    ('t04-sim6-view6',        '6',  '6',  '-Ddlb.chunks.workers=4 -Ddlb.chunks.pendingCap=2048 -Ddlb.debug=1', 'single'),
    ('t05-queue3',            '6',  '6',  '-Ddlb.chunks.workers=4 -Ddlb.chunks.pendingCap=2048 -Ddlb.debug=1', 'queue3'),
    ('t06-burst5',            '6',  '6',  '-Ddlb.chunks.workers=4 -Ddlb.chunks.pendingCap=2048 -Ddlb.debug=1', 'burst5'),
    ('t07-workers1',          '6',  '6',  '-Ddlb.chunks.workers=1 -Ddlb.chunks.pendingCap=2048 -Ddlb.debug=1', 'single'),
    ('t08-workers8',          '6',  '6',  '-Ddlb.chunks.workers=8 -Ddlb.chunks.pendingCap=2048 -Ddlb.debug=1', 'single'),
    ('t09-cap512',            '6',  '6',  '-Ddlb.chunks.workers=4 -Ddlb.chunks.pendingCap=512 -Ddlb.debug=1',  'single'),
    ('t10-cap4096',           '6',  '6',  '-Ddlb.chunks.workers=4 -Ddlb.chunks.pendingCap=4096 -Ddlb.debug=1', 'single'),
    ('t11-mix-struct2',       '6',  '6',  '-Ddlb.chunks.workers=4 -Ddlb.chunks.pendingCap=2048 -Ddlb.debug=1', 'mix2'),
]

def fire(rcon, structure, x=1200, z=0):
    return rcon.cmd_retry(f'execute positioned {x} 90 {z} run dlbtest {structure}')

def wait_zone(server, markers=('Zone choisie',), timeout=DEFAULT_TIMEOUT_S):
    t0 = time.time()
    seen = set()
    while time.time() - t0 < timeout:
        txt = server.log_since_boot()
        for m in markers:
            if m in txt and m not in seen:
                seen.add(m)
        if len(seen) == len(markers):
            return time.time() - t0, txt
        time.sleep(2)
    return None, server.log_since_boot()

FRESH_WORLD = True  # un monde neuf par test (les structures restantes polluent le relief)

def run_test(name, sim, view, props, scenario, server, outdir):
    print(f"=== {name} (sim={sim} view={view} {props} {scenario}) ===", flush=True)
    server.set_props(**{'simulation-distance': sim, 'view-distance': view,
                        'max-tick-time': '300000'})  # labo seulement : mesurer long, ne pas crasher
    if FRESH_WORLD:
        import shutil
        for w in ('world', 'world_nether', 'world_the_end'):
            shutil.rmtree(server.run / w, ignore_errors=True)
    server.start(vm_extra=props)
    result = {'name': name, 'sim': sim, 'view': view, 'props': props, 'scenario': scenario,
              'search_s': None, 'site': None, 'dist': None, 'paste_ms': None, 'total_s': None,
              'audit': '-', 'note': None}
    tstart = time.time()
    try:
        if not server.wait_done():
            result['note'] = 'boot KO'
            return result
        rc = Rcon('127.0.0.1', 25575, 'lablab')
        rc.connect(retries=20, delay=2)
        # pregen court : 16 regions 8x8 chunks autour de 1200,0 (amorti : monde conserve)
        # etalee : 1 region (8x8 chunks) / 2 s pour ne pas geler le thread
        # principal (watchdog kill reproduit quand les 16 arrivent d'un coup).
        for rx in range(16):
            cx = 59 + (rx % 4) * 8
            cz = -16 + (rx // 4) * 8
            rc.cmd_retry(f'forceload add {cx*16} {cz*16} {cx*16+128} {cz*16+128}')
            time.sleep(2)
        print("pregen demande: 16 regions de 8x8 chunks (etalee)", flush=True)
        time.sleep(150)
        t0 = time.time()
        if scenario == 'single':
            fire(rc, 'dragon')
        elif scenario == 'far':
            fire(rc, 'dragon', x=2000, z=0)   # hors pregen : vraie SAFETY
        elif scenario == 'queue3':
            for s in ('dragon', 'citadel', 'observatory'): fire(rc, s)
        elif scenario == 'burst5':
            for s in ('dragon', 'citadel', 'observatory', 'circus', 'ship'): fire(rc, s)
        elif scenario == 'mix2':
            fire(rc, 'dragon'); fire(rc, 'citadel')
        # en file/mix : attendre le NOMBRE de completions de pose
        # '[STRUCT5] <nom> en <ms>ms (<blocs> blocs' -- 'Zone choisie'
        # n'est logguee que sur le chemin de recherche visible, pas sur
        # le chemin file/differe.
        need = {'queue3': 3, 'burst5': 5, 'mix2': 2}.get(scenario, 1)
        el, txt = 0, ''
        import re as _re
        while time.time() - t0 < DEFAULT_TIMEOUT_S:
            txt = server.log_since_boot()
            completions = _re.findall(r'\[STRUCT[45]\] (\w+) (?:finie )?en (\d+)ms \(', txt)
            if len(completions) >= need:
                el = time.time() - t0
                break
            time.sleep(2)
        else:
            completions = _re.findall(r'\[STRUCT[45]\] (\w+) (?:finie )?en (\d+)ms \(', txt)
            el = None
        result['spawn_count'] = len(completions)
        result['completions'] = completions
        if need > 1:
            ts = []
            for m in _re.finditer(r'\[(\d{2}):(\d{2}):(\d{2})\.\d+\].*\[STRUCT[45]\] \w+ (?:finie )?en \d+ms \(', txt):
                ts.append(int(m.group(1))*3600 + int(m.group(2))*60 + int(m.group(3)))
            if ts:
                d0 = ts[0] - ts[0]
                result['completion_gaps_s'] = [ts[i] - ts[i-1] for i in range(1, len(ts))]
                result['total_queue_span_s'] = ts[-1] - ts[0]
        result['search_s'] = el
        m = re.findall(r'Zone choisie[^0-9-]*(\d+)[ ,](\d+)', txt)
        if m: result['site'] = f"{m[-1][0]},{m[-1][1]}"
        m2 = re.findall(r'en (\d+)ms', txt)
        if m2: result['paste_ms'] = m2[-1]
        if el is None: result['note'] = f"pas de Zone choisie en {DEFAULT_TIMEOUT_S}s"
        else: result['note'] = 'OK'
        rc.close()
    except Exception as e:
        result['note'] = f'ERREUR: {e}'
    finally:
        result['total_s'] = round(time.time() - tstart, 1)
        result['audit'] = '-'
        print(json.dumps(result, ensure_ascii=False), flush=True)
        try:
            (Path(outdir) / f'{name}.log').write_text(server.log_since_boot()[-200000:], errors='replace')
        except Exception: pass
        server.stop()
        # handoff propre : attendre que le JVM precedent soit VRAIMENT mort
        # (sinon le test suivant frappe RCON sur un serveur en cours d'arret).
        import glob, subprocess as _sp
        time.sleep(6)
        for _ in range(24):
            rc2 = _sp.run(['pgrep', '-f', 'BootstrapLaunche[r]'], capture_output=True)
            if rc2.returncode != 0:
                break
            time.sleep(5)
    return result

def main():
    conf = dict(l.split('=', 1) for l in Path(sys.argv[1]).read_text().splitlines() if '=' in l)
    for k in ('DLB_GRADLE_USER_HOME', 'DLB_JDK'):
        if k in conf: os.environ[k] = conf[k]
    project = Path(conf['project'])
    run = Path(conf.get('run', project / 'run'))
    outdir = Path(conf.get('out', 'results'))
    outdir.mkdir(parents=True, exist_ok=True)
    only = conf.get('only', '')
    server = Server(project, run)
    results = []
    for (name, sim, view, props, scenario) in TESTS:
        if only and only not in ('all',) and not any(
                name == o or name.split('-')[0] == o for o in only.split(',')):
            continue
        if time.time() - RUN_T0 > 36000:
            print("session-max atteinte, arret", flush=True)
            break
        r = run_test(name, sim, view, props, scenario, server, outdir)
        results.append(r)
        # anti-wipe : chaque resultat est persisté immediatement sur disque
        # (results.jsonl) + log brut par test ; la suite peut etre relancee
        # avec only=<tests restants> sans rien reperdre.
        try:
            with open(outdir / 'results.jsonl', 'a') as fh:
                fh.write(json.dumps(r, ensure_ascii=False) + '\n')
        except Exception:
            pass
    print("== FIN LAB T232 ==", flush=True)

RUN_T0 = time.time()
if __name__ == '__main__':
    main()
