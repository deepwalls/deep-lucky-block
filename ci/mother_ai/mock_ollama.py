#!/usr/bin/env python3
"""T292 : FAUX serveur Ollama local pour tester la chaine IA de la mere de bout
en bout SANS dependance lourde. Implemente le protocole reel utilise par
MotherAISnowDirector :

  GET  /api/tags        -> 200 {"models":[{"name":"qwen2.5:3b"}]}
  POST /api/generate    -> 200 {"response":"<json plan>","done":true}

Comportements pilotables via variables d'environnement (pour tester tous les
regimes du mod : plan valide, fences markdown, JSON casse, latence, HTTP 500) :
  MOCK_MODE       ok | fenced | broken | http500      (defaut ok)
  MOCK_DELAY_MS   latence fixe avant chaque /generate   (defaut 0 ; le probe
                  /api/tags reste immediat, comme un vrai Ollama charge qui
                  repond vite aux tags mais mouline sur le modele)
Le plan renvoye en mode ok/fenced applique radius=3 et booste TROIS chunks
reels lus dans le prompt (les plus enneiges) pour que l'effet soit mesurable
sur snowDensity() cote test.

Usage : python3 mock_ollama.py [port]  (defaut 11434, logs sur stderr)
"""
import json
import os
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODE = os.environ.get('MOCK_MODE', 'ok')
DELAY_MS = int(os.environ.get('MOCK_DELAY_MS', '0'))
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 11434


def build_plan(prompt):
    """Lit les lignes 'cx cz medianY minY maxY snowy% eau% glace% escarpe' du
    prompt et produit un plan qui booste les 3 chunks les plus enneiges,
    attenue le moins enneige, radius=3 : effet mesurable garanti."""
    rows = []
    for line in prompt.splitlines():
        parts = line.split()
        if len(parts) == 9 and parts[0].lstrip('-').isdigit():
            cx, cz = int(parts[0]), int(parts[1])
            rows.append((int(parts[5]), cx, cz))
    rows.sort(reverse=True)
    boosts = [[cx, cz, 1.25] for _, cx, cz in rows[:3]]
    if rows:
        boosts.append([rows[-1][1], rows[-1][2], 0.75])
    return {'radius': 3, 'boost': boosts}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(f'[mock] {args[0] if args else fmt}', file=sys.stderr)

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == '/api/tags':
            self._json(200, {'models': [{'name': 'qwen2.5:3b'}]})
        else:
            self._json(404, {'error': 'not found'})

    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(n).decode('utf-8', 'replace')
        if DELAY_MS:
            time.sleep(DELAY_MS / 1000.0)
        if self.path != '/api/generate':
            self._json(404, {'error': 'not found'})
            return
        try:
            req = json.loads(body)
        except Exception:
            req = {}
        prompt = req.get('prompt', '')
        print(f'[mock] generate: model={req.get("model")} format={req.get("format")} stream={req.get("stream")} prompt={len(prompt)} chars', file=sys.stderr)
        if MODE == 'http500':
            self._json(500, {'error': 'modele simule en panne'})
            return
        plan = build_plan(prompt)
        if MODE == 'ok':
            inner = json.dumps(plan)
        elif MODE == 'fenced':
            inner = 'Voici mon plan :\n```json\n' + json.dumps(plan, indent=2) + '\n```'
        elif MODE == 'broken':
            inner = '{"radius": tres grand, "boost": [}'
        else:
            inner = json.dumps(plan)
        print(f'[mock] reponse ({MODE}) : radius={plan["radius"]} boosts={len(plan["boost"])}', file=sys.stderr)
        self._json(200, {'model': req.get('model', 'mock'), 'created_at': '2026-10-26T00:00:00Z',
                         'response': inner, 'done': True,
                         'total_duration': DELAY_MS * 1000000 + 5000000})


if __name__ == '__main__':
    srv = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
    print(f'[mock] Ollama simulé sur 127.0.0.1:{PORT} mode={MODE} delay={DELAY_MS}ms', file=sys.stderr)
    srv.serve_forever()
