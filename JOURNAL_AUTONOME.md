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

## 2026-09-27 — Mesure CI #4 (run 36335198901, commit 492bc2e) : T78 ok mod-side, variance machine isolee

- T78 (Everest sans balayage) : l'item « balayage » (8,0 s au run #3) DISPARAIT
  du decompte [DLB-PERF] Everest — gain mod-side reel de 8,0 s.
- MAIS run globalement plus lent (~+30 % sur TOUTES les structures, code dragon
  identique : 27,0 s -> 32,7 s) : pre-chargement Everest 24,5 s -> 33,3 s pour
  les memes 368 chunks, memes coords/seed. Workers CPU 24,4 s -> 33,0 s : la
  generation moteur (2 vCPU) varie de ±8 s. ni GC (331 ms) ni heap.
- Everest run #5 : 35,99 s (pre-chargement 33,3 = 93 %, fixLiquids 1,7, filler
  0,9, pose 0,0). Sans AUCUN code mod, le meme site couterait 24-33 s : le
  budget 30 s n'est atteignable de facon deterministe qu'en reduisant la
  geometrie chargee (marge naturalize ~16 blocs = ~74 chunks ~ 6,4 s) ou le
  statut de chargement — arbirage geometrie/qualite reserve a l'utilisateur.
- T79 (local, AST ok) : clearSurfaceDecor saute les sections 16-blocs sans air
  ni decor (meme discipline que T71) ; equivalence stricte demontree (aucun
  setBlock ne change). Cible : l'anomalie 0,29 ms/colonne mesuree en jeu
  (11,8 s / 40 176 colonnes, foret) ; en CI le poste vaut ~1,1 s sur dragon.

## 2026-09-27 — Mesure CI #5 (run 36336086415, commit f0a6eba) : T79 compile, fixtures vertes ; mur machine confirme

- Machine lente (meme profil que run #5) : Everest 36,15 s (pre-chargement
  33,5 s = 93 %, workers 33,4 s), dragon 32,50 s, citadel 28,3, circus 8,1.
- Regressions ciblees : ALL PASS ; fixtures eau (T77), smooth, lake OK malgre
  T79. clearSurfaceDecor CI inchange (1,1 -> 1,2 s sur dragon, terrain plat) :
  le gain T79 vise le cas foret en jeu (11,8 s mesures), pas demontrable en CI.
- Deux points de mesure T78 (runs 36335198901 + 36336086415, meme seed/coords) :
  balayage disparu du decompte Everest dans les deux — gain mod-side 8,0 s
  CONFIRME ; le verdict 30 s depend desormais de la machine CI (24,5-33,5 s de
  generation vanilla pour les 368 chunks, soit ±8 s de bruit).
- Livraison terrain-etape-7 preparee (Structures4 T78 + StructureTerrainPrep
  T79, avants, NOTICE). Arbitrage geometrie/budget Everest remis a
  l'utilisateur (ceinture naturalize differee, statut chunk, ou decision seuil).

## 2026-09-27 — T80 (Crimson Lake) : sonde reduite au chunk central

- Cause mesuree (annotations run 36336086415) : le lac paye DEUX pre-
  chargements pour le meme terrain : (1) sonde 340 chunks ~23 s pour lire
  groundY(px,pz) (UNE colonne, garde T7 heightmap primee) ; (2) prepZone
  coeur 650/zone 756 ~35 s juste apres.
- Correctif : sonde = px/pz +-8 blocs (chunk central + voisins immediats via
  la marge propre de preloadBox). Lecture, ancrage (ground=47/sink=10),
  geometrie et pipeline strictement inchanges ; la fixture lake (sink=10,
  emprise epargnee) re-verifie tout cela en CI.
- Gain attendu : ~330 chunks de generation en moins dans la fenetre =
  ~12 s (machine rapide) a ~23 s (machine lente) ; seuil 120 s conserve.
- Constat annexes : les budgets T46 (coeur 20 s + anneau 8 s) sont devenus
  diagnostiques seulement (attente de couverture complete = choix mesure du
  code actuel : demarrer avec des colonnes absentes coutait plus en lectures
  serie). Everest est deja a l'optimum geometrique (marge 25% T + 16 < coeur
  T46 de 48). fillPending trie deja du centre vers l'exterieur (priorite
  coeur effective). Reste comme leviers post-T80 : regles eau strictes
  conservees, seuils conserves, harnais conserve.

## 2026-09-27 — Mesure CI #6 (run 36337674163, commit 2c7fead) : T80 integre, fixtures vertes ; Everest a 0,2 s

- Fixtures : ALL PASS, lac compris (sink=10, ancrage ground=47 IDENTIQUE avec
  la sonde 9 chunks mesuree a 0,235 s — contre 340 chunks en 23,2 s avant).
- Latences (machine moyenne-lente) : citadel 24,2 / obs 21,2 / dragon 27,4
  PASS / circus 6,5 / ship 10,3 / everest 30,19 FAIL de 0,19 s / lake 114,1 PASS.
- Arbre de causes Everest (run #7) : pre-chargement 368 chunks 26,3-27,8 s
  (~71-76 ms/chunk) + fix 1,7 + filler 0,6 + 0,7 divers ; machine #5/#6 lente
  (~91 ms/chunk) -> 36 s ; machine #4 rapide (~67 ms) -> ~28 s attendu. Verdict
  devenu LOTO DE MACHINE CI : mod-side residuel ~2,3-2,6 s.
- Fausse piste documentee : la « tache 10,2 s 100% preload » qui precede
  EVEREST TERMINÉ est le SHIP (fenetre 17:42:08->17:42:18 = ship PASS 10,3 s),
  pas un gel de sonde Everest — timestamps concordants, piste refermee sans
  correctif (verifier avant d'agir = regle).
- T80 ne retire aucune generation au total (756 chunks uniques dans les deux
  versions) : il supprime une attente redondante de 340 chunks reutilises par
  la suite ; gain reel 0-10 s selon machine, non mesurable au-dessus du bruit.
- Budget runs du message : 6 consommes (4 sessions + 2 compiles/ce rerun).

## 2026-09-27 — Mesure CI #7 (run 36338736434, commit 8f3febd) : 4e fixture verte ; bruit machine borne

- Fixtures : ALL PASS (4e session consecutive : eau T77 45120 exact, lazy
  water, smooth, lake sink=10 avec ancrage inchange, tickets, async, loot).
- Latences : machine LENTE (profil #5/#6) : citadel 28,7 / obs 25,1 / dragon
  33,0 FAIL / circus 8,0 / ship 10,4 / everest 36,19 FAIL / lake 98,6 PASS.
- Serie Everest (code T78 identique depuis 492bc2e) : 34,89 (avec balayage,
  run #4) -> 35,99 -> 36,15 -> 30,19 -> 36,19. Dragon : 27,0 -> 32,7 -> 32,5
  -> 27,4 -> 33,0. Deux classes de runners : rapide (dragon ~27, everest
  ~30) / lente (~33/~36). Le mod represente ~2,5 s sur everest et ~9 s sur
  dragon (balayage 3,6 conserve a dessein) : le reste est la generation
  vanilla, hors portee du code dans le respect des contraintes (geometrie,
  seuils, harnais, qualite).
- Budget du message epuise (8 runs). Prochaine etape proposee : arbitrage
  utilisateur sur le verdict Everest (decision par decision) OU nouvelles
  pistes in-game (le pre-chauffage T46/T51 des 60 s d'annonce rend deja le
  chargement quasi nul en jeu reel ; les gains T79 se mesurent en foret).

## 2026-09-27 — Reception du GUIDE COMPLET TERRAIN ET SURFACE (passation, v1.0)

- L'utilisateur transmet le guide d'habillage de surface (dressAndPlant), qui
  absorbe 5 documents precedents. Fichier joint « a lire !.txt » INACCESSIBLE
  dans le sable (dossier uploads absent) — signale ; le contenu du guide est
  dans le message, sauvegarde dans docs/GUIDE_COMPLET_TERRAIN_ET_SURFACE.md.
- Verification des faits vs code actuel (son avertissement §3) : le fichier fait
  5 863 lignes (guide : 5 650, soit avant T77) ; readBlock 6 / getBlockState 62
  (guide 5/63 ; T79 en a ajoute un) ; columnsOf 13 sites (conforme) ;
  verifyGrassSurface + naturalize toujours APPELEES (3 sites : prepZone 10/11,
  decorateChain post-pose, et un appel direct ligne 3920) ; dressAndPlant /
  ChunkMajorZone absents -> le programme du guide est bien a ecrire.
- Recouvrements deja faits par moi (ne pas refaire) : reco #1 verrous eau =
  T77 (jamais remplir a sec / jamais au-dessus de la nappe, valide x4 CI) ;
  reco #2 ancrage mediane mesuree = T76 (valide x4 CI) ; reco #3 Everest
  exclu = T78 (valide 2x) ; reco #7 clearSurfaceDecor = T79 (sections T71,
  valide, gain vise = foret en jeu) ; dragon = engine-bound demontre.
- Verifications API 1.21.1 (4 recherches) : WORLD_SURFACE_WG existe MAIS c'est
  un instantane de worldgen (wiki + mappings) : pour l'habillage POST-edition
  on utilisera SafeSurface (heightmap securisee, garde T7) et NON brut _WG ;
  coldEnoughToSnow(BlockPos) OK 1.21.x (change en int en 1.21.2, hors sujet) ;
  LevelAccessor.hasChunk OK ; les heightmaps d'un chunk FULL sont maintenues
  par ses setBlock (sets registeres) — donc une heightmap primee reste juste.
- Plan applique : le plan 8 commits du guide, adapte aux gardes du mod
  (ChunkKeeper.keep + TerrainChain.heartbeat + report zoneComplete +
  sliceBudgetMs adaptatif + anti-doublon — le ChunkPass du guide, avec
  server.execute, violerait I6 et T13). Push groupes, commits 1 tache = 1
  commit dans l'historique, run CI compile seul tant que zero changement visuel.

### Run #0 du programme (36340976542) — compile seule, f582547, SUCCESS
T-HABILL.1+2 scaffolding (ChunkMajorZone + isSnowyCell + caches) compile.
Aucun changement de comportement ; aucune session serveur (pas de marqueur).
Prochaine : T-HABILL.3 (reparation dette T71 dans les boucles grass/neige :
mutable par colonne + neige issue du cache par chunk), toujours zero visuel.

### Test 2/10 — T-HABILL.3 (dette T71 grass/neige) [server-check]
isSnowy (comp. chaine/colonne) remplace par isSnowyCell (cache/chunk) dans
verifyGrassColumn et guardWaterEdges. Nuance visible acceptee (guide) :
biomes enneiges sans "snow/ice/frozen" dans le nom (grove, snowy_slopes)
recuperent leur couche de neige. Fixtures a relire (smooth/water surtout).

### Test 2/10 (36341708253, d8448a4 T-HABILL.3) — fixtures ALL PASS, timings lus
- Verifications : securite colonnes, async, loot, water x2, lazy water, smooth,
  lake, tickets = ALL PASS. commit B (isSnowyCell) n'a rien casse.
- Latances (fixture 30 s) : everest 34,47 (93 % = preload vanilla 33,3 s,
  loterie machine, statu quo) ; dragon 31,6 (preload 21,7 = 69 %) ; lake 80,5
  (preload 48,2 s = 60 %, 756 chunks ~64 ms/chunk ; mieux que 98-114 avant).
- Postes d'habillage reels mesures (a viser par dressAndPlant) :
  clearSurfaceDecor 4,7 s | fixLiquids 2,8 s | rebuildTerrain 6,5 s | naturalize
  0,8 s | verifyGrassSurface hors top postes.

### Test 3/10 — T-HABILL.4 (palette, mesure seule, compile only)
samplePalette/paletteAt/fillerFor/WITNESS/PALETTE_FALLBACKS + sampleZonePalettes()
journalise AVANT toute edition en prepZone 1b/11 (invariant I1). Aucun setBlock
: compile-seule suffit. Repli attendu eleve si zone pas prechargee a ce stade.

### Test 3/10 (36342357682, T-HABILL.4) — SUCCESS (compile seule)
Palette + journal prepZone 1b/11 compiles. Prochain : T-HABILL.5 dressAndPlant
(machinerie complete, flag dressingEnabled()=false : AUCUN appel d'abord).

### Test 4/10 — T-HABILL.5 dressAndPlant (machinerie, FASTFLAG OFF, compile only)
Tout l'habillage du guide est ecrit : posNoise (sel par usage), edgeP, warping
(WARP_CELL 12, WARP_AMP 4, contrainte <=16 respectee), pickGround (indices
nommes), density/pickPlant (tables de GOUT), plantOn (canSurvive = validite),
applySnowLayer, sous-sol filler (3 blocs), dressAndPlant en startColumnPass sur
liste chunk-major. DRESSING_FASTFLAG=false => AUCUN setBlock nouveau possible.
Branchement + sauvetage plantes = etape suivante, avec [server-check].

### Test 5/10 — T-HABILL.6+7+8 BRANCHEMENT dressAndPlant [server-check]
- Greffe sauvetage dans scanTrees (bloc deja lu, 0 colonne en plus) : isRescuable
  (BushBlock filet mods, DoublePlant UPPER exclu, liquides/climbables exclus,
  naturalize-connus exclus), noteRescue plafonne 64/chunk + 20 000 total.
- Repose replantAllRescued APRES dressAndPlant (ordre guide : scatter -> arbres
  -> dressAndPlant -> sauvees ; CACTUS en tout dernier, piles 1..3 deterministes,
  doubles LOWER+UPPER en FAST_FLAG). Adaptation guide : SafeSurface au lieu du
  surfCache par chunk (nombre d'essais borne 512/espece/chunk identique).
- Câblage dressOrLegacy : remplace le couple verifyGrassSurface/naturalize dans
  decorateFinish (2 sites) ; FAST FLAG OFF = chemin historique intact (D9,
  methodes conservees). prepZone garde son verifyGrassSurface pre-paste (§66).
- Dressing FASTFLAG = true. Fixtures attendues : smooth (6-blocs intacts),
  water ×2+lazy (intact : dress saute eau/glace), async, lake, tickets.
- Journal attendu : 'dressAndPlant : A rapides / B melanges, S surfaces,
  F sous-sols, P plantes, K sautees, R replis' + 'palette : N chunks ... dominants'
  + 'repose des plantes sauvees : X replantees'.

### Test 5/10 (36343091899, T-HABILL.6-8) — fixtures ALL PASS, dressAndPlant 0.3 s
- Verifications ALL PASS (smooth/water x2+lazy/lake/tickets/async/loot).
- [DLB-PERF] dragon : 'dressAndPlant 0.3 s (1%)' ; verifyGrassSurface et
  naturalize ABSENTS des postes => bien remplaces. 0.3 s << budget 1 s.
- Latences : dragon 32,2 (preload 74 %), everest 34,7 (93 %), lake preload 44,35
  = loterie machine, zero regression liee au dressing.

### Test 6/10 — CONFIRMATION (memes seed/coords, aucun changement de code)
Methode : 2 executions identiques avant de conclure (bruit CI ~30 %).
(Le push precedent n'a pas declenche de run visible -- relance explicite.)

### Test 6/10 (36344227069, 77b1dd7) — CONFIRMATION : fixtures ALL PASS
- Meme code, memes seed/coords : dragon 32,2 -> 38,0 ; everest 34,7 -> 36,5 ;
  lake ~80 -> 118,5 (preload vanille 60-73 %) : bruit machine ±20 %, jamais
  lie au mod. dressAndPlant 0,3 -> 0,2 s (stable, 3x sous budget guide 1 s).
- ship/circus PASS; toujours 5 FAIL latence structurels de fixture (30 s CI).
- Deux points de mesure acquis : la non-regression dressing est CONFIRMEE ;
  le FAIL latence des grosses structures est un verdict machine, pas qualite.

### Test 7/10 (36345131072, bf64e0b) — PREMIER RUN 100 % PASS
Latences (limites fixture) : citadel PASS, observatory PASS, dragon PASS,
circus PASS, ship PASS, everest 29.4 s PASS (limite 30), crimsonlake 66.0 s x2
PASS (limite 120). Fixtures ALL PASS. everest 29.4 < 30 pour la premiere fois
(preload 25.6 s, fenetre-sonde de 10.2 s pas reproduite ; loterie machine).
lake 66.0 = meilleur temps jamais mesure (preload 37.0 = 56 %).

### Test 8/10 (36345649429) — 2e RUN 100 % PASS, chiffres stables
everest 29.8 s (preload 27.5 = 92 %, mod 2.3 s) ; lake 64.5 s (preload 37.1 =
58 %, mod 27.5 s : rebuild 6.5, balayage 3.4, decor 3.8, decorate 3.7, fix 2.8,
attente/tick 5.2, divers ~2) ; une petite structure a 8.1 s (preload 100 %).
Fixtures ALL PASS, 4e/5e fois. Deux runs identiques -> tableau de reference
structure-par-structure remis a l'utilisateur ; la marge /2 restante est
entierement dans le chargement vanille (non rognable sans casser la qualite
ou les fixtures, interdits).

### Test 9/10 — 3e PASS consecutif vise (stabilite du protocole)
