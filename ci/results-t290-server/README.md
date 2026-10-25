# Campagne T290 — 10 tests serveur pour opti (25/10/2026)

## Protocole
- Runtime sandbox (export `ci/runtime/parts` : mod-project + caches gradle + JDK
  21) : build LOCAL des sources T290 (`gradlew build --offline` reussi avec
  swap). Aucune dependance externe.
- Harness `campaign_v2.py` (forkee de la probe validee ; inclus RCON natif
  python : auth + commandes) : 10 sessions serveur headless Identiques :
  seed 123456789, monde frais a chaque run, view/sim-distance 4,
  spawnChunkRadius 16 autour de (1200 64 0), `dlbtest dragon` au point
  (1200 90 0), budget recherche 900 s, decorate 900 s.
- Machine sandbox : 2 CPU / 4 Go RAM (+4 Go swap) — profile plus faible que
  la machine cliente (qui affichait ~19 s de « Can't keep up » en jeu).

## Resultats (results.json / summary.txt)
| Indicateur | Moyenne | Min | Max |
|---|---|---|---|
| CKU max par run (ticks perdus) | **51,7 s** (1034 ticks) | 23,3 s (466) | **68,0 s** (1360) |
| CKU cumule par run | 67,3 s | 27,2 s | 79,5 s |
| NB d'evenements CKU | 2,6 | 2 | 3 |
| Recherche (selection seche, tir final) | 292 ms | 238 ms | 374 ms |
| Wall total session | 18 min 35 s (dispersion 0,63 %) | — | — |
| Site / corners | 10/10 (1511,151) chargé | | |

## Ou le temps se perd (lecture des logs)
1. **Pic CKU #1 : regeneration des spawn chunks** (au setworldspawn) puis
   **attente de chunks** de l'emprise au paste (marqueur LAGPROBE
   « pre-chargement des chunks 79/99 », tick 3,9-4,0 s) — le gros CKU
   (~42-68 s) eclate la recherche.
2. **13-15 min de silence total** apres les logs terrain : generation
   vanilla SYNCHRONE des chunks manquants de l'emprise (les 20 restants hors
   keeper), pendant laquelle le ticker attend. C'est la composante qui
   explique les totals > 900 s ici (sur machine cliente plus rapide c'est ce
   qui fait le « mange la barre de generation » au paste).
3. **Le chantier propre (smooth → glacis → fusion → degel → sweep/audit
   neige) = ~4 s** pour 118 chunks — les phases mesurées « en N ms »
   (141+91+46+35+20 ≈ 333 ms + sous-phases) sont NEGLIGEABLES. Le code
   terrain T288/T289 n'est PAS le goulot.

## Consequences opti prioritaires (suite logique, hors portee de ce tour)
- A) Paralleliser/prolonger le PRELOAD des chunks manquants AVANT le paste
  (la vague 79/99 = resume ; les 20 qui restent = generation lente) : demande
  plus agressive hors-tick + remediation asynchrone (ticket keeper prolonge).
- B) Repartir la pose structure (paste) sur plusieurs ticks avec
  budget ms/tick (le paste est le plus gros bloc monolithique restant).
- C) Lancer la campagne A/B (defaut vs variantes -Ddlb.*) comme en T248
  (meme harness) pour chiffrer chaque candidat.

## Hygiene
Run autonome reproductible : `sh /tmp/rt/launch_v2.sh 10` (runtime installe
dans /tmp/rt du sandbox). Les serveurs fantomes qui gardaient 25565/25575
(le serveur MC est forke par le DAEMON gradle : killpg(gradlew) ne suffit
pas) sont tues entre runs via signature `serverRunVmArgs.txt` + attente de
port libre (cf. `kill_server_hard()` du script).
