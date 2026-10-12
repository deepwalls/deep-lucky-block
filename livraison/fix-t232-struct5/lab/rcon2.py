#!/usr/bin/env python3
"""Client RCON minimal pour le lab DLB (multi-frames, retries auth).

Usage: rcon2.py "<commande>" [timeout_reponse_s]
Retourne la reponse du serveur (concat des frames). Exit 2 sur timeout.
"""
import socket, struct, sys, time

HOST, PORT, PASS = "127.0.0.1", 25575, "lablab"
ID_CMD, ID_AUTH = 2, 3

def pkt(rid, ptype, body):
    data = struct.pack("<ii", rid, ptype) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data

def read_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("socket closed")
        buf += chunk
    return buf

def read_pkt(sock):
    (size,) = struct.unpack("<i", read_exact(sock, 4))
    data = read_exact(sock, size)
    rid, ptype = struct.unpack("<ii", data[:8])
    return rid, ptype, data[8:-2].decode("utf-8", "replace")

def main():
    cmd = sys.argv[1]
    wait = float(sys.argv[2]) if len(sys.argv) > 2 else 8.0
    last_err = None
    for attempt in range(3):
        try:
            s = socket.create_connection((HOST, PORT), timeout=4)
            s.sendall(pkt(1, ID_AUTH, PASS))
            rid, ptype, _ = read_pkt(s)
            if rid == -1:
                raise RuntimeError("auth refused")
            s.sendall(pkt(2, ID_CMD, cmd))
            out, deadline = [], time.time() + wait
            s.settimeout(1.0)
            while time.time() < deadline:
                try:
                    rid2, ptype2, body2 = read_pkt(s)
                    if body2:
                        out.append(body2)
                    if len(out) > 64:
                        break
                except socket.timeout:
                    if out:
                        break
                    continue
            print("".join(out) if out else "(no response)")
            s.close()
            return
        except Exception as e:  # noqa: BLE001
            last_err = e
            time.sleep(1.5)
    print(f"RCON FAIL: {last_err}")
    sys.exit(2)

if __name__ == "__main__":
    main()
