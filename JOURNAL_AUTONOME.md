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

## 2026-09-27 — Mesure CI #1 (run 36331359511) : ECHEC, blocage basin(onDemand=true)

- Mesure : compilation Java 21 OK. dlbverify jamais terminé (240 s) : fixLiquids
  en boucle de retry « tour 39..43/6 », « 1360 demandes de chargement »,
  ~609 chunks épinglés. Constat clé : les 4 commits utilisateur n'ont JAMAIS eu
  de run [server-check] (le workflow ne suivait que l'ancienne branche) — la
  tempête préexistait au ×4.
- Causes : (1) branche retry onDemand sans borne de tours (retry.add
  inconditionnel, refillRound partagé) ; (2) ma preuve d'évasion re-demandait
  les chunks manquants → ma contribution au cycle.
- Décision : (A) l'évasion ne demande jamais de chunk (inconnu = OUVERT,
  jamais de génération pour une preuve) ; (B) retry onDemand borné à
  FIXLIQ_PENDING_ROUNDS=6 puis stalled, même discipline que la branche request().
  Relance run #3.

## 2026-09-27 — Mesure CI #2 (run 36332468110) : ECHEC fixture basin, cause trouvee

- Mesure : build OK, session OK, FAIL « Unfilled basin at -43,241,-42 » = ZERO
  remplissage. Relecture : la porte garait `packed` (colonne d'eau) au lieu de
  `nk` (candidat sec) ; la preuve demarrait sur la nappe et concludeBasin
  remplissait... la colonne d'eau (refusee « deja pleine »). Zéro remplissage.
- Causes associees : type de liquide parent perdu ; colonnes remplies marquees
  LIQ_SURF (bloquaient une preuve a niveau superieur) ; anneau inForceRing
  gonfle au x4 (tempete) ; frontiere froide onDemand non bordee par le halo.
- Correctifs (commit 324b53f) : garer nk + verifyLava ; fills hors LIQ_SURF ;
  fx calcule AVANT expansion x4 ; scan onDemand = chunks charges seuls ;
  retry onDemand borne au halo seedMargin et a 6 tours.
- Mesure sim (ci/sim_t77.py, rejoue l'algo colonne par colonne) : bassin
  ferme = 45 113 nouveaux blocs (+7 preexistants = 45 120 = assertion exacte
  de la fixture CI) ; plaine ouverte = 0 remplissage, 902 colonnes OUVERTES.
- AST tree-sitter : 0 erreur, 0 signature perdue. Reste a mesurer en CI
  ( compilation reelle + fixtures + temps ) : BLOQUE par expiration GH_TOKEN.

## 2026-09-27 — Mesure CI #3 (run 36334260227, commit 9b7e7e0) : T77+T76 VALIDES, Everest seul echoue

- Fixtures : ALL PASS — water onDemand=false 45120 blocs (exact), water
  onDemand=true 45120, lazy water (frontiere froide epinglée, halos intacts),
  smooth, lake (sink=10), tickets, async, loot. « Targeted regression
  checks: ALL PASS ».
- Latences structure par structure (de « demandee » au marqueur de fin,
  decoration comprise ; budget 30 s / 120 s) :
  | structure | avant (79d3edd) | run #4 | budget |
  |---|---|---|---|
  | citadel | 26,30 s | 21,10 s PASS | 30 |
  | observatory | 22,00 s | 20,80 s PASS | 30 |
  | dragon | 27,90 s | 27,00 s PASS | 30 |
  | circus | 7,90 s | 6,10 s PASS | 30 |
  | ship | 9,70 s | 7,20 s PASS | 30 |
  | everest | 35,39 s | 34,89 s FAIL | 30 |
  | crimsonlake | 101,80 s | 76,90 s PASS | 120 |
- Postes [DLB-PERF] Everest (total 34,8 s) : pre-chargement chunks 24,5 s
  (70 %, generation moteur — workers CPU 24,36 s), balayage naturels
  flottants 8,0 s (23 %), fixLiquids 1,7 s, hole filler 0,6 s, pose 0,0 s.
  GC 400 ms, heap 1213→709 MiB — ni GC ni memoire en cause.
- Constat : seul le balayage etendu est du calcul mod-side significatif.
  Consigne utilisateur : Everest = creuse, sans terrassement, ne doit pas
  subir les passes lourdes. Correction T78 : spawnEverest saute
  sweepFloatingNaturalPass (conserve sealUndergroundGaps 0,6 s,
  finalTerrainPass, fixLiquids 1,7 s). Attendu ~26,9 s → budget tenu.
- AST Structures4 : 0 erreur, accolades equilibrees. Run #4 = 1 donnee ;
  il faut 2 mesures meme seed/coords pour conclure un gain (regle).
