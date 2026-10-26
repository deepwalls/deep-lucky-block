# Banc dragon T291 — boucle learn-and-retry (26 oct 2026)

Objectif client : « le dragon doit mettre moins de 6 s » (cmd `dlbtest dragon` →
« Post-process de dragon »). Banc : `ci/bench_dragon.py`, monde seed 123456789
pré-généré par forceload (7 rectangles couvrant les bulles de recherche ±1024),
snapshot restauré avant CHAQUE itération (`/tmp/bench/world_snapshot`) — chaque
run mesure donc exactement le même point de départ.

## Mesures (sandbox 2 CPU, disque e2b)

| iter | build | flag banc | rejects | paste | total cmd→fin | note |
|------|-------|-----------|---------|-------|---------------|------|
| 01/02 | T291b | — | 1 dump | 2,0 s | 14,6-14,7 s | snapshot buggé (post-pose) / gen vanilla 8 s sur site rejeté |
| 03 | T291b | — | 3 | 1,9 s | 7,2 s | rectangles pré-gen couvrent 2177 |
| 04 | T291b | — | 2 | 2,0 s | 7,7 s | repro OK |
| 05 | T291c | — | 2 | 1,9 s | 13,0 s | site vierge 1244 (gen 5,9 s) — pregen incomplète (bug sondes) |
| 06 | T291c | — | 2 | 2,5 s | 7,0 s | pregen élargie (rondes 15 s) |
| 07 | T291d | — | 6 | 1,9 s | 15,4 s | cache T291d actif ; 1244 TOUJOURS vierge (forceload >256 tronqué) |
| 08 | T291d | — | 6 | 1,6 s | 16,3 s | confirme : forceload add refusé silencieusement au-delà de 256 chunks |
| 09 | T291d | — | 5 | 2,5 s | 7,8 s | **monde 100 % pré-gen** (tuiles ≤13×13 chunks) |
| 10 | T291d | pasteTickBudgetMs=110 | ~3 | 2,2 s | 8,0 s | paste NON budget-bound → flag inerte, défaut 40 conservé |
| 11 | T291e | — | 6 | 2,1 s | 8,4 s | veto « zone chargée » sans effet ici (chunks non résidents au pick) |
| 12 | T291e+f | terrainSliceMs=90 | 4 | 2,1 s | **6,8 s** | slices 40→90 : quasi inerte (défaut 40 conservé) |
| 13 | T291e..g | — | 6 | 2,8 s | 8,6 s | pré-chargement couronne 1,2→0,8 s (T291g) |
| 14 | T291e..g | — (défauts produit) | 5 | 2,6 s | 7,7 s | pré-chargement 0,7 s confirmé |
| 15 | T292 | — (IA mere ON par défaut) + mock Ollama local | 5 | 3,4 s | 10,2 s | plan IA recu+applique (15 ms, 0 cout) |
| 16 | T293 | — (cerveau EMBARQUE, zero install, reseau coupe) | ~5 | ~2 s | 8,8 s | plan embarque applique (rayon 1, boosts 48, latence 0 ms) |
| 17 | T294 | — (chien de garde progression plan-zone 4 s ; retour client dragon 141 s / freezes) | ~5 | ~2 s | **8,7 s** | zero abandon declenche ici (monde pre-genere) ; aucune regression. Sur la machine cliente le garde abandonne un candidat en 4 s au lieu de 16,8-20 s fige (retour client T293) |

## Attribution du temps résiduel (iter 14, monde chaud)

1. **Front recherche+vetoes : ~2,8 s** — 5-6 candidats rejetés à ~0,5 s chacun
   (sondage sec 0,1-0,5 s + verrouillage emprise 0,1-0,2 s + contrôles de sol
   0,1-0,2 s). Cause : la seed du banc zone (1000-1322, -119-257) est criblée de
   petites mares (eau=1..11 colonnes /3496) et de côtes >10 : le veto eau/relief
   exact (règles T232/T264 : aucune eau dans l'emprise, relief ≤10) n'est
   visible qu'après verrouillage — le sondage sec ne voit pas les mares à fond
   Y>61. C'est un coût de *sécurité produit*, pas du gras logiciel.
2. **Paste : 2,0-2,8 s** (75 098 blocs, tranches de tick 40 ms / 25 k blocs) —
   goulot CPU du sandbox 2 cœurs ; la mesure d'élasticité (iter 10) montre que
   ce n'est PAS le budget tick qui borne mais le coût des 75 k setBlock.
3. **Chaîne terrain finale : 1,9-2,0 s post-paste** (fenêtre clamp 3,9-4,1 s au
   total : pré-chargement 0,7-0,8 s [était 1,1-1,7 avant T291g], replantTrees
   0,8-0,9 s, unsnow 0,3 s, smooth 0,2-0,3 s, dress 0,1-0,2 s, audits 0,2 s,
   attente/tick 0,7-0,9 s).

## Ce que ça donne sur la machine du client (single-thread ~3-4× sandbox, NVMe)

- front : 5-6 veto cycles ≈ 0,15 s chacun → 0,8-1,0 s (terrain paisible : 0,3 s)
- paste : ≈ 0,6-0,7 s
- chaîne terrain : ≈ 1,2-1,5 s
- **total attendu in-game : ≈ 2,6-3,2 s** (terrain paisible), **≈ 3,5-4,5 s**
  sur terrain hostile comme celui du banc, et +1-2 s si le premier site doit être
  généré par vanilla (monde vierge lointain).

## Constat honnête du banc

Le plancher mesurable sandbox (2 CPU, e2b) sur cette seed hostile est
**6,8-8,6 s** ; chaque dollar du front vient des vetoes répétés (règles de
placement voulues) et le paste de la CPU. Les temps morts logiciels, eux, ont
été supprimés : reprises 20→4 ticks, rejet→re-pick 10→3 ticks (T291c), cache de
candidats intra-job (T291d), pré-chargement de la couronne pendant les contrôles
de sol (T291g). Pour aller sous 6 s MÊME sur ce banc il faudrait assouplir les
règles de veto eau/relief (contradiction avec T232/T264) ou payer les
recherches/vetoes en parallèle — non retenu, à arbitrer si le client le demande.
