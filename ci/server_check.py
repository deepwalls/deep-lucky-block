"""Single headless server session with bounded structure timings and retained logs."""
import os
from collections import deque
from pathlib import Path
import queue
import secrets
import signal
import socket
import struct
import subprocess
import threading
import time

project = Path(os.environ['RUNNER_TEMP']) / 'mod-project'
run = project / 'run'
run.mkdir(exist_ok=True)
password = secrets.token_hex(24)
# Keep the EULA acceptance shipped in the project; do not silently accept a new one.
assert 'eula=true' in (run / 'eula.txt').read_text()
(run / 'server.properties').write_text('\n'.join([
    'server-ip=127.0.0.1', 'server-port=25565', 'online-mode=true',
    'enable-rcon=true', 'rcon.port=25575', f'rcon.password={password}',
    'level-name=ci-structure-test', 'level-seed=123456789',
    'view-distance=4', 'simulation-distance=4', 'spawn-protection=0',
    'max-tick-time=60000', 'allow-flight=true', 'gamemode=creative',
]))


def rcon(command, timeout=15):
    with socket.create_connection(('127.0.0.1', 25575), timeout=15) as sock:
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
            size = struct.unpack('<i', exact(4))[0]
            if not 10 <= size <= 1048576:
                raise ValueError('Invalid RCON packet')
            data = exact(size)
            return struct.unpack('<i', data[:4])[0], data[8:-2].decode(errors='replace')
        auth, _ = request(1, 3, password)
        if auth == -1:
            raise RuntimeError('RCON authentication failed')
        return request(2, 2, command)[1]


def annotate(text, failure=False):
    # The annotations API truncates large messages; preserve phase diagnostics in chunks.
    for start in range(0, max(1, len(text)), 3000):
        part = text[start:start + 3000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print(f'::{"error" if failure else "notice"} title=Server verification::{part}', flush=True)


lines = queue.Queue()
log_path = Path(os.environ['RUNNER_TEMP']) / 'server-check.log'
proc = subprocess.Popen(['./gradlew', 'runServer', '--console=plain', '--max-workers=2'],
                        cwd=project, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True, start_new_session=True)


def consume():
    with log_path.open('w') as log:
        for line in proc.stdout:
            log.write(line)
            log.flush()
            lines.put(line)


threading.Thread(target=consume, daemon=True).start()
summary = []
phases = []
recent = deque(maxlen=25)


def wait_for(marker, timeout):
    pending = set(marker if isinstance(marker, list) else [marker])
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            line = lines.get(timeout=1)
        except queue.Empty:
            if proc.poll() is not None:
                raise RuntimeError(f'Server exited: {proc.returncode}')
            continue
        recent.append(line.strip())
        if '[DLBVERIFY] FAIL' in line:
            raise RuntimeError(line.strip())
        if any(key in line for key in ['prepZone', 'fixLiquids', 'decorate', 'scatter [', 'DLB-PERF', 'DLBVERIFY', 'incomplete repair', 'DLB-LAKE', 'STRUCT4', 'Post-process', 'pre-chargement', 'DLB-CHUNKS', 'DLB-CPU']):
            phases.append(line.strip())
        pending = {item for item in pending if item not in line}
        if not pending:
            return
    raise TimeoutError(f'No completion marker within {timeout}s: {marker}')


failed = False
try:
    wait_for('Done (', 240)
    annotate('Dedicated server started successfully.')
    annotate('Running targeted water, loot and terrain fixtures.')
    rcon('dlbverify')
    wait_for('[DLBVERIFY] ALL PASS', 240)
    summary.append('Targeted regression checks: ALL PASS')
    annotate('\n'.join(line for line in phases if '[DLBVERIFY]' in line))
    for name, x in [('citadel', 0), ('observatory', 600), ('dragon', 1200),
                    ('circus', 1800), ('ship', 2400), ('everest', 3000), ('crimsonlake', 3800)]:
        while not lines.empty():
            lines.get_nowait()
        phases.clear()
        if name == 'everest':
            rcon('dlbcpubegin')
        start = time.monotonic()
        print(f'{name}: starting command-to-final-completion measurement', flush=True)
        response = rcon(f'execute positioned {x} 90 0 run dlbtest {name}', timeout=120)
        print(f'{name}: command submitted; response: {response}', flush=True)
        if name == 'everest':
            marker = ['EVEREST TERMINÉ', 'Post-process de everest terminé']
        elif name in ('circus', 'ship'):
            marker = 'Post-process de ' + ('shipdead' if name == 'ship' else name) + ' terminé'
        elif name == 'crimsonlake':
            marker = 'decorate TERMINE'
        else:
            marker = ['decorate TERMINE', 'Post-process de ' + name + ' termine']
        wait_for(marker, 360 if name == 'crimsonlake' else 240)
        duration = time.monotonic() - start
        if name == 'everest':
            # Snapshot commands do not generate chunks or alter benchmark limits.
            # Measure construction first; collect CPU diagnostics after completion.
            rcon('dlbcpuend')
            wait_for('[DLB-CPU]', 15)
        summary.append(f'{name}: {duration:.2f}s from command to completion marker ({marker})')
        limit = 120 if name == 'crimsonlake' else 30
        within_budget = duration <= limit and (name != 'crimsonlake' or duration >= 10)
        failed = failed or not within_budget
        summary.append(f'{name}: latency budget {"PASS" if within_budget else "FAIL"} (limit {limit}s)')
        print('\n'.join(summary[-2:]) + '\n' + '\n'.join(phases), flush=True)
        if not within_budget or name in ('everest', 'crimsonlake'):
            # Preserve the useful timing proof without exhausting Actions' annotation quota.
            diagnostics = [line for line in phases if any(key in line for key in (
                'pre-chargement TERMINE', 'TACHE COMPLETE', 'incomplete repair',
                'fixLiquids TERMINE', 'CLEAR COMPLETE', '[DLB-LAKE] anchor', 'EVEREST TERMINÉ', '[DLB-CPU]'))]
            annotate('\n'.join(summary[-2:]) + '\n' + '\n'.join(diagnostics), not within_budget)
except Exception as exc:
    # Drain actual current logs even when the command's RCON response times out.
    while not lines.empty():
        recent.append(lines.get_nowait().strip())
    failed = True
    annotate(str(exc) + '\n' + '\n'.join(phases[-15:] + list(recent)[-10:]), True)
finally:
    try:
        rcon('stop')
        proc.wait(timeout=45)
    except Exception:
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
    report = '\n'.join(summary) or 'No structure completed; inspect server-check.log.'
    (Path(os.environ['RUNNER_TEMP']) / 'server-summary.txt').write_text(report)
    annotate(report, failed)
raise SystemExit(1 if failed else 0)

# Full-session rerun authorized after the raw-paste and water fixes.

# Five further optimization cycles authorized; validate true asynchronous chunk requests.
