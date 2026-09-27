# Étape 7 — performances : Everest hors balayage lourd (T78) + decor par sections (T79)

Fichiers (complets — copier-coller direct MCreator) :
- `Structures4Procedure.java` (T78) — avant : `avant/Structures4Procedure.java`
- `StructureTerrainPrep.java` (T79, inclut aussi T77 de l'étape 5) — avant : `avant/StructureTerrainPrep.java`

Base : version validée des étapes 5/6 (commit 9b7e7e0, run CI 36334260227 vert).

## T78 — l'Everest ne subit plus le balayage des naturels flottants

Consigne (27/09) : l'Everest est une structure *creuse et sans terrassement* ;
elle ne devrait pas payer les passes de terrain lourdes. Le balayage étendu
coûtait **8,0 s en CI (run 36334260227, 23 % des 34,9 s)** — et plus de 2
minutes en jeu sur les forêts.

- `spawnEverest` ne lance plus `sweepFloatingNaturalPass` ; le re-scellement
  des trous (0,6-0,9 s), la passe finale, /fixwater+/fixlava (1,7 s) et la
  pose sont **strictement inchangés**.
- Validé en CI (runs 36335198901 et 36336086415) : l'item « balayage » disparaît
  du décompte `[DLB-PERF]` Everest ; fixtures eau ALL PASS ; comportement du
  reste du pipeline intact (dragon/citadel/… gardent leur balayage).
- Limite honnête : le balayage retirait aussi d'éventuelles lianes/feuilles
  flottant dans la boîte de la montagne ; sans lui elles restent visibles.
  C'est le choix de la consigne (« ne devrait pas s'y appliquer »).

## T79 — clearSurfaceDecor lit par sections de 16 blocs (discipline T71)

La passe traversait **43 à 89 blocs par colonne** avec un `getBlockState`
complet à chaque bloc : mesuré en jeu à **0,29 ms/colonne (11,8 s sur 40 176
colonnes, forêt)** — 14× le rythme des autres passes (trace de l'IA
précédente). Désormais, une section de 16 blocs *entièrement vide* ou *sans
aucun décor* est sautée d'un coup — exactement la discipline du balayage T71.

- Équivalence stricte : aucun `setBlock` ajouté ni retiré, ordre conservé dans
  les sections traitées ; le gain vient uniquement des lectures économisées.
- Validé en CI (run 36336086415) : fixtures eau/smooth/lake/tickets ALL PASS.
- Limite honnête : en CI, ce poste vaut ~1,1-1,2 s sur dragon (terrain plat) et
  y reste identique — **le gain vise le cas réel en jeu (forêts profondes)**
  ; la CI ne peut pas le démontrer. Aucune régression mesurée nulle part.

## Le mur restant d'Everest (verdict CI)

Après T78/T79, le travail du mod sur Everest vaut **2,6 s au total**
(fixLiquids 1,7 + scellement 0,7 + pose 0,0). Le reste — 24,5 à 33,5 s selon
la machine CI, mêmes seed/coords — est la **génération vanilla des 368 chunks**
de montagne extrême (workers CPU saturés, GC 0,3 s). Sur machine CI rapide,
Everest avec T78 passerait à ~27 s ; sur machine lente ~36 s. Aucun code du
mod ne peut changer ça sans toucher à la géométrie chargée — voir les options
remises à l'utilisateur dans le journal (chargement différé de la ceinture
naturalize ~74 chunks ≈ 6,4 s, ou statut de chunk plus léger, ou arbitrage
du budget Everest).
