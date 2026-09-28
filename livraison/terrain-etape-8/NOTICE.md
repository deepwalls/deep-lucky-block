# Étape 8 — Crimson Lake : sonde d'ancrage réduite au chunk central (T80)

Fichier : `CrimsonLakeStructureSpawnProcedure.java` (complet — copier-coller direct MCreator).
Base : version validée de l'étape 7 (commit 76e501c, run CI 36336086415).
Version « avant » : `avant/CrimsonLakeStructureSpawnProcedure.java`.

## Problème mesuré (annotations CI, runs 36335198901 / 36336086415)

Le lac payait **deux cycles de pré-chargement** pour le même terrain :

1. une « sonde » de **340 chunks (~23 s machine lente, ~11 s rapide)** — qui ne
   servait qu'à lire `groundY(px, pz)`, **une seule colonne centrale** (garde T7 :
   jamais de lecture sur chunk non chargé — correct et conservé) ;
2. le pré-chargement `prepZone` de la zone élargie (756 chunks, principalement
   nouveaux même si les 340 étaient déjà en cache).

## Correctif T80

La sonde ne charge plus que le chunk central et ses voisins immédiats
(`px ± 8, pz ± 8` → 9 chunks, soit 0,235 s mesuré). La lecture, l'ancrage
(`ground=47, sink=10`), la géométrie de la zone finale et tout le pipeline
suivant sont **strictement inchangés** : `prepZone` continue de charger sa
zone propre (756 chunks) comme avant.

## Validation (run CI 36337674163, 27/09)

- `PASS lake: sink=10 with template padding; mountain above roof cleared;
  sides/floor preserved` — ancrage identique à l'avant (mêmes chiffres
  ground=47 / lowest=38 / sink=10 dans le log) ;
- régressions ciblées : ALL PASS (eau T77, lazy water, smooth, tickets…) ;
- sonde mesurée : 0,235 s (9/9 chunks) contre 23,2 s pour 340 — moins d'attente
  inutile, **les mêmes chunks uniques générés au total** (la zone finale les
  recouvre via prepZone dans les deux versions).

## Limite honnête

Le gain en temps de parcours n'est **pas mesurable au-dessus du bruit de la
machine CI** (±25 % sur la génération vanilla d'un run à l'autre) : la version
T80 enlève 340 chunks d'attente *redondante* (ceux-ci étaient réutilisés par la
zone finale), donc le profit attendu est de ~0 à ~10 s selon la machine, et non
les ~23 s de la sonde brute. Le seuil 120 s reste tenu (114,1 s sur machine
lente). Le plafond structurellement nécessaire — 756 chunks de génération
vanilla — est hors de portée du code du mod.
