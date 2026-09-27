# Journal autonome — Deep Lucky Block (reprise 27/09)

Format : date | hypothèse | mesure | conclusion | décision.

## 2026-09-27 — Reprise de session

- Base : workspace synchronisé sur origin/main (8ab74b4, fichiers utilisateur de
  17:07–17:09). Les 7 fichiers suivis sous mod_project/src sont identiques au
  distant (diff vide). Tailles : TerrainPrep 5650, Struct5 2169, Struct4 1576,
  Lake 715 lignes — cohérent avec le prompt de reprise.
  Décision : partir de ces fichiers, ignorer les anciens documents manquants
  (livraison/, ANALYSE_LOG… absents du workspace).
- Infra CI : le workflow java21-build.yml ne se déclenche que sur push de
  l'ANCIENNE branche arena/01a0da6a et si le message contient `[server-check]`.
  Mesure : vérifié dans le YAML. Décision : branche arena/01a0e366 ajoutée au
  déclencheur push. Les [DLBVERIFY] fixtures (bassin fermé 45 120 blocs,
  frontière lazy) sont le garde-fou de la règle d'eau.

## 2026-09-27 — T77 eau : règle d'appartenance prouvée à une nappe

- Hypothèse : l'inondation (46 539 colonnes / 239 715 blocs / 34,3 s) vient du
  BFS WorldEdit de sliceRefill qui REMPLIT toute colonne terrestre sous le
  niveau de l'eau et propage depuis chaque remplissage — toute la plaine
  inondable connectée à l'océan est noyée. Confirmé par lecture du code et par
  la doc WorldEdit (« fixwater fills any pit to the highest source level »,
  sémantique volontairement régionale, pas topologique).
- Correctif : porte mémo (LIQ_OPEN/LIQ_BASIN par colonne au niveau exact) devant
  chaque remplissage ; preuve d'enclave par BFS d'évasion borné (heightmap
  MOTION_BLOCKING uniquement, budget de tick, chunks inconnus = OUVERT hors
  anneau, attente ring-proche avec abandon). ENCLAVE → remplissage à hauteur de
  la nappe, jamais au-dessus (tryFillColumn = ancien bloc inline, exactions
  re-vérifiées). OUVERT → rejet définitif au niveau.
- Vérif fixture : bassin 87×87 fermé → 7 520 colonnes enclavées × 6 = 45 120 =
  le `restored == 45120` attendu. Frontière lazy : canal murs hauts au niveau,
  chunk cx+3 hors atteinte → conserve.
- Mesure locale : tree-sitter 0 erreur, 0 signature perdue, accolades équilibrées.
  NE PROUVE ni compilation, ni comportement, ni temps → CI.

## 2026-09-27 — T76 ancrage : repli sur le sol médian mesuré

- Hypothèse : baseY mesuré sur UNE colonne centrale peut être faux (65 contre
  médiane d'emprise 49 quand la sélection retombe sur un candidat brut).
- Correctif : dans doPaste, après bbox et AVANT prepZone, groundStats(stride 4)
  sur l'emprise ; si ≥16 colonnes mesurées et |médiane−baseY| > 4 (= seuil
  MAX_POST_SMOOTH_DELTA_Y existant) → translation verticale unique de baseY,
  fp, rp, min, max. Offset appliqué une seule fois (aval inchangé :
  selectedGroundY=baseY, foundationBaseY=baseY+oy).
- Mesure locale : idem, 0 erreur AST. Validation comportementale → CI.
