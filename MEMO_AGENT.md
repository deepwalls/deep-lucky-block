# MEMO AGENT — workflow imposé par l'utilisateur (06/10) + historique des références

## Règles de travail (à suivre à chaque itération)

1. **À chaque livraison** : donner le lien Google Drive vers la nouvelle version + expliquer ce qui a changé.
2. **Quand l'utilisateur envoie des logs** :
   a. Analyser d'abord TOUS les problèmes visibles (opti, warnings, lags, erreurs, silences anormaux) et les répertorier.
   b. Relire ensuite le(s) fichier(s) ciblé(s) en chassant TOUS les commentaires signés **Dev** (et assimilés) : les noter, les prioriser, les interpréter, et **rapporter mon interprétation** à l'utilisateur.
   c. Dans la même réponse : corriger le bug.
3. **Livraison du fix** : nouveau numéro T (séquence continue), zip Drive contenant UNIQUEMENT les fichiers modifiés, chacun **au bon emplacement dans src/** (ex. `src/main/java/deepluckyblock/procedures/Xyz.java`) + une note Txxx explicative ; plus optionnellement un zip « FULL-SRC ». MD5 du zip Drive vérifié byte-exact.
4. **Mémoire des références** : conserver l'historique. Quand l'utilisateur valide un état **en jeu**, cet état devient « meilleure optimisation connue » : en cas de régression ultérieure on y revient pour la zone concernée ; sinon on passe au problème suivant.
5. **Questions / demandes de validation** : les poser via questionnaire cliquable à options (outil ask_user), pas en texte libre.

## Référence « validée en jeu » actuelle

- **Dernière référence validée par l'utilisateur en jeu : l'état du 27/09** (chercheurs de zones
  qui demandent les chunks candidats en asynchrone puis revérifient — comportement perdu par T185).
- **T228 puis T229** (06/10) : compilation CI validée, **validation en jeu EN ATTENTE** de l'utilisateur.
  Ils deviendront la nouvelle référence après test `/dlbtest dragon` concluant dans un monde neuf.

## Historique T (récap)

| T | Date | Objet | État |
|---|---|---|---|
| T228 | 06/10 | Structures5 : compilation CI (overlay resync), boucle fantôme après abort (SEARCH_ABORTED), lac « brûlé » (notifyGenerationAborted), achievement volé (PendingStructure), everest async gélant GENERATION_BUSY | CI OK ; jeu à valider |
| T229 | 06/10 | Structures5 : blocage silencieux du secours ciblé (cg/cf==null -> boucle sans re-demande/log), terrain non visité téléport ~1 km (T185 remplacé par demande fond + file différée bornée 96/600), diagnostics d'attente promus en warn périodique | CI OK (37412249658) ; jeu à valider ; **livrés Drive + GitHub** |

## Où vivent les choses

- **Drive** (info la plus récente côté utilisateur) : `DEEP-LUCKY-BLOCK-v2.1-COMPLET.zip`, puis
  `DEEP-LUCKY-BLOCK-T229-STRUCT5-FIX.zip` (+ `-FULL-SRC`) + `T229-NOTE.txt`.
- **GitHub** branche de session `arena/7cdbe3b3-deep-lucky-block` : `mod.zip` à jour,
  `livraison/fix-t228-struct5/`, `livraison/fix-t229-struct5/`, CI `java21-build.yml`.
- Les logs incidents à analyser viennent de `main` (commit « Add files via upload »).

## Pièges techniques connus du sandbox (pour moi)

- L'extraction `/home/user/work/mod` et le `.git` local peuvent être perdus entre tours : toujours
  `git fetch` + comparer l'arbre au bout distant et recommiter/pousser **dans le même appel**.
- `www.googleapis.com` / `uploads.github.com` / blob storage GitHub : réseau bloqué depuis le
  sandbox → passer par les outils du connecteur (`upload_file file_path`), jamais par curl.
- CI : erreurs lisibles via `gh api .../check-runs .../annotations` (téléchargement de logs blob HS).

## Ajout 07/10 — T230 (apres validation jeu du T229)
- T229 valide en jeu (07/10 ~02h28) : zone trouvee a -230,322 en monde neuf, pose + achievements OK. Reference validee = zip T229 sur Drive.
- Problemes du run : recherche 2 min 25 silencieuse, lags Can't keep up, message "Pose brute ... en X ms" trompeur.
- T230 (3bb42e2) : drain des candidats differes a chaque reprise de findFlat (contrainte T229 levee), secours lointain T174/T190 bloque tant qu'un differe proche est encore en chargement, ligne INFO de progression toutes les 10 s pendant la recherche, durees de recherche affichees dans "Zone choisie", chat "Pose brute" precise "(phase paste seule, hors recherche)".
- Livraison T230 : zip 55 832 B sur Drive (id 1nnsCuh809xc5p3eVZ5IbVWq-SXekOzuC) + note (id 1W_ss1KgRWrObFKe480H4FHZPnAzFdcCQ), dossier 0AMOST-WPxU3uUk9PVA.
- Piege sandbox rencontre : rollback local de mod.zip entre appels bash → reconstruire mod.zip depuis /home/user/work/mod et commiter+pusher DANS LE MEME appel ; push hors detached HEAD (git checkout -B <branche>).

## Deep-learning (instruction utilisateur 11/10 04:14 : « learn and retry », punition/récompense)

| Date | Evenement | Score |
|---|---|---|
| 11/10 | T234 a introduit la « giga pause » (216 s en jeu, revert partiel impossible → T235) | **Punition -1** |
| 11/10 | T235 : giga pause eliminee (32 s lab, valide par protocole, en attente user) | **Récompense +1** |
| 11/10 | Protocole 10 mondes : 9/10 sous 6 mn, 10/10 completes | **Récompense +1** |
| 11/10 | Run09 507 s hors seuil (monde cotier 63 rejets aquatiques) | **Punition -1** |
| | **Solde cumulé** | **0** (la faute T234 est compensee, la dette run09 = travail T235b optionnel) |

## Historique T (suite)

| T | Date | Objet | État |
|---|---|---|---|
| T230-T234 | 09-10/10 | SeaLanternGate, dry pick T233b/T233c, crimsonlake blocage infini (deadlines dures) | livrés GitHub |
| T235 | 11/10 | Giga pause : pin keeper remplace au lieu d'union + release sur rejet/abandon/fin (216 s -> 32 s lab) | livré GitHub, validé 10-run |
| T236 | 11/10 | Ancre Y replaiste sur sol REEL post-mods (couronne 2..6 blocs hors emprise, borne +4, soutien rempli) | livré GitHub, tirée 2× en vrai run lab |
| T237 | 11/10 | Bambou/cactus/citrouille/champignon géant ne bloquent plus la recherche de sol (clear jamais sauté) ; quota replantation au sommet des piles | livré GitHub |

## T238 (interne T235b) — rejet anticipe du candidat aquatique (11/10 fin)
Suite protocole 10 mondes : run09 = 507 s (cotier, 63 rejets x emprise
complete 4-6 s). Piste « pre-charge speculatif du candidat suivant » :
ecartee (plomberie lourde du picker, regression risk, gain partiel).
Piste retenue : **avortement precoce du rejet** — FLAT_MAX_WET == 0,00,
donc une seule colonne d'eau deja visible condamne le candidat sans
recours possible ; partialWetProbe() (meme grille que qualityOnReady,
chunks absents toleres, arret au 1er plan d'eau) declenche le rejet
aquatique sans attendre les 100 % d'emprise. Acceptation inchangee (T159).
Lab AGGRAVE (6 mondes NEUFS, structures visees a la cote la plus proche) :
314 / 245 / 306 / 239 / 229 / 262 s => moyenne 266 s, pire 314 s ; 6/6
< 6 mn ; crimsonlake 6/6 posee (presage 120 s + pose ~2,9 s).
Contre-reference : run09 507 s sous conditions moins dures. Livre 500f183.
Scoreboard deep-learning : T234 -1, T235 +1, protocole +1, recap run09 -1,
T238 6/6 sous budget +1 => SOLDE +1.
Lecons lab (cout reel paye) : (1) verifier les argfiles apres regen —
ancre sed vide = echec silencieux, 4 boots NoSuchElement ; (2) jamais
pkill -f <mot de ma propre ligne> : autokill deux fois de suite ;
(3) `cat >>` sur fichier non committe + clone reset = annexe perdue :
toujours s'assurer que le fichier source existe AVANT d'ajouter.

## T239 (11/10 soir) — crimsonlake : fin du silence + T239-B rejete
Ticket : lac demande 18:21:38, 33 min de silence absolu (monde actif).
Cause non reproductible en lab (8/8 succes) ; diagnostic aveugle car
TOUTES les etapes clefs du pipeline ecrivaient seulement sous dlb.debug.
T239-A : logs System.err sur trigger/presage/PENDING/attente NBT/abort
implicites + watchdog +130 s avec etat complet + etiquette [build T239]
dans la ligne « commande prete » (plus jamais de diag version aveugle).
T239-B (pre-chauffage speculatif du candidat suivant) : protocole aggrave
6 mondes cotes = 327,5 s moy / 368 s max vs 266/314 pour T238 pur ->
REGRESSION => REVERT avant livraison, Structures5 remis a T238.
Scoreboard : T239-B -1 (avoir expédie le cycle entier avant le A/B
protocole) => SOLDE 0. Lecon : toute acceleratrice = A/A sur le protocole
AGGRAVE avant meme de raconter « ca devrait ».

## T240 (11/10 nuit) — couture franche au bord de la dragon : raccord rebranche
Ticket : delimitation nette zone coupee vs terrain modifie (screenshot
client dragon). Repro lab obtenue sur windswept_hills : profil probe,
saut de 24 blocs entre voisins DANS la zone lissee + "lissage bride :
1548 colonnes" = meme signature que le log client.
DEUX causes racines : (1) smoothAround() — la passe de raccord post-paste
— definie l.200 mais AUCUN appelant dans tout le repo (grep exhaustif) :
la pose coupait l'emprise a vif ; (2) T185 avait reduit la serie T109 a
{49, 71} alors que T109 documentait 7>11>15>21>29>49>55>61>67>71.
Fix (2 fichiers, minimal-touch) : serie T109 complete restauree ;
smoothAround = serie raccord {11,21,31,43,57} via smoothPassAfterPreload
(T89/T177/limitSlope intacts, emprise protegee) ; smoothAround ENFIN
cablee dans les finitions post-paste de Structures5 (apres POST_Q).
REGRESSION INTERNE attrapee en lab avant livraison : naturalize()
re-epingle la zone apres sa liberation par decorateFinish -> held() vrai
a jamais -> FIFO gelee, toute structure suivante "mis en file" sans fin.
Correctif : ChunkKeeper.release() a la fin de la chaine du raccord.
Mesures : couture 24 -> <=6 blocs (3 mondes, plaines/montagnes, pas 1) ;
cout raccord ~0,3-0,5 s ; protocoles perf (3 structures + 1 crimsonlake,
mondes neufs) : 328 s puis 216 s < 6 mn (ref T238 266/314) => OK.
Livre 33e0d1b + zip DEEP-LUCKY-BLOCK-T240-SRC-COMPLET.zip.
Scoreboard : T240 +1 (diagnostic par mesure avant/apres + regression
interne detectee et corrigee par le protocole AVANT livraison) =>
SOLDE +1. Lecon : toute passe post-paste qui consomme naturalize()
hors de la fenetre du pipeline DOIT rendre le keeper — held() est un
portillon global, pas un refcount.

## T241 (11/10 nuit) — REGRESSION T240 livree au client : hang 6+ min repare
Log client 23h30 (compilation OK, test sur New World) : dragon+lac ok,
citadel pedale 6+ min sur "verifyGrassSurface 15312/15312", Can't keep
up en cascade, total > 6 mn ("trop lent", "chaque structure avant
mettait moins de 5000ms"). Etiquette restee [build T239] (bump oublie)
MAIS preuve que le run = T240 : dragon affiche 4 lignes "lissage bride"
(2 prep + 2 raccord) au lieu de 2.
Cause racine = MA regression T240 : le raccord cablé en PARALLELE de
decorateFinish executait verifyGrassSurface/replant/naturalize APRES la
relache du keeper -> chargements synchrones de chunks par colonne sur
machine lente (lab : tout en cache, 8 ms -> trou invisible).
Correctif : surcharge decorateFinish(level,min,max,boolean raccordT240)
-> le raccord s'execute dans le dernier callback AVANT la liberation
TerrainChain/ChunkKeeper (fenetre tenue, emprise protegee) ; relache
dans smoothAround supprimee ; Struct5 l.768 -> decorateFinish(...,true),
smoothAround parallele supprime ; Structures4/lac inchanges (false) ;
etiquette [build T241] bumpee (regle desormais : toute livraison bump).
Lab : citadel = raccord dans la fenetre, verify 0,1 s, fifo liberee 21 s ;
protocole 3+1 = 231 s < 6 mn ; couture conservee (<=2,5/bloc).
Scoreboard : -1 (regression livree au client ; le protocole lab ne
simulait pas la fenêtre relachee + machine lente -- defaut SYSTEMIQUE
de mon cadre de test) ; +0 pour la reparation (reparer sa propre casse
n'est pas un merite) => SOLDE 0. Lecon : toute passe ajoutee doit etre
validee SOUS FENETRE LIBEREE et avec la chronologie exacte de release ;
la latence disque du client est un axe de test a part entiere.

- T242 (livré 12/10) : pose brute — anticipation de la demande de chunks
  de l'emprise du paste (avant prepZone + au lancement du PasteJob),
  idempotente, non bloquante, zéro sacrifice, bump [build T242].
  DIAG avant fix : paste CPU ~190 ms/75k blocs ; wall = attente chunks
  + SAFETY + prep (chrono = tGlobal). Lab T242 : obs 5085, dragon 5636,
  citadel 5403, lac 2,7 s, protocole 3+1 ≈236 s <6 mn, aucune régression.
  Reste plancher = génération vanilla des chunks vierges sur monde neuf
  (incompressible sans sacrifier). Options T243 proposées au client.

- T243 (livré 12/10) : neige FONDUE au bord des biomes (dégradé 100/80/
  50/30/10/0 % par bandes depuis le chunk neigeux, couverture totale si
  entouré de neigeux, patches déterministes) + unsnowExposedGrass (tue le
  « grass snowy=true exposé sans neige » qui rend le dessus GRIS = bug
  vanilla connu). Diag livré (verifyGrassSurface logue les refusés par
  dégradé). Protocole 3+1 ≈260 s <6 mn, aucune régression. Ouvert T244 :
  « smooth vers l'intérieur 1 bord » — attendre capture/coords (garde T26
  anti-enfouissement non touchée). Perf « TROP LONG » obséré de nouveau.

## T244 (12/10/2026) — LISSAGE 4 BORDS + ZONE CREUSEE + PERP
Diag instrumenté « T244 symetrie lissage » (LOG WARN par passe) : N/S/E/O
ampleur |cible-naturel|, remblai moy, marche max LOCALISEE (x,z,Y,dist-emprise).
Preuves : marches de 7-14 blocs TOUTES en bord de zone (d22-24, fenêtre
adaptative xfeather ≈ 0 au bord puis +18/+40 à 5 colonnes = falaise bride) +
marche 5 au contact emprise (d1, restore y0) => « 1 seul bord fondu ».
Fix (StructureTerrainPrep) : limitOuterEdgeSlope (pente<=2, ruban externe 20,
eau exclue, naturel pivot) ; limitContactSlope (pente<=2, 10 cols couronne,
emprise pivot figée, restore y0 inchangé) ; anchorMargin 1.0->0.0 ; fenêtre
REMBLAI refermée 1 bloc/bloc au lieu de 2 (drop inchangé). Perf
(Structures5Procedure) : reprise attente SAFETY plan-zone 20->2 ticks
(plan-zone 7667->2084 ms, -73 %, zéro changement de verdict).
Dev : AIOOBE typo j+1 (au lieu de j-1) dans limitOuterEdgeSlope branche j>0 ->
corrigé AVANT livraison (diag shape inutile mais gardée défensive).
Protocole 3+1 v6 : observatory 20,8 s / dragon 15,1 s / circus 10,2 s /
lac 2,8 s après présage 120 s = **~169 s < 6 mn** (T243=260 s).
Symétrie finale : remblai + sur 4 côtes, marche max=2 partout. Solde 0.

## T244b (12/10/2026) — zip complet META-INF
Client : « dans le telechargeable src j'avais main + meta-inf » -> ajoute
main/resources/META-INF/ (neoforge.mods.toml, enumextensions.json, services/
mixin connector) extrait de mod.zip a la racine du depot dans le zip T244.
Commit 196175f, release T244 recreee dessus, liens raw verifies 302. Solde 0.
=== T245 (12/10) ===
T245 (log client 12/10 ~05:19) — 3 chantiers livrés :
- (C) Recherche <= 0,5 s : NOUVEAU tier « chunks deja charges » en tete de recherche
  (loadedPickCenter, Structures5Procedure). Enumeration serveur de centres dont
  l'emprise 9-points est deja en memoire (0 generation), filtres bruit sur 2 threads
  a indices disjoints (pairs/impairs, AtomicBoolean d'arret commun, budget DUR 480 ms
  = propInt dlb.search.loadedBudgetMs), tri sec-puis-span, verdict REEL qualityOnReady
  (slope<=10, wetRatio==0) avant retour. Fallback T233c inchange. Kill-switch
  -Ddlb.search.loadedFirst=0. ATTENTION : l'accesseur du record FlatQuality est
  slope() (pas span()) — corrige en revue.
- (A) Cause racine anneau gris « terre enneigee » : le REFUS du degrade T243 laissait
  l'herbe heritee snowy=true sans couche -> dessus gris. placeSnowOrUnsnow() partout
  (verifyGrassColumn, dressAndPlant, naturalize-via-applySnowLayer? non: naturalize
  garde applySnowLayer ; plateforme secours). Regle : herbe exposee sans neige = snowy=false.
- (B) snowVaried() : couches 1-3 (55/30/15 %), hash deterministe, applique aux 4 sites neige.
- Stamps : [build T245] (DlbTestCommands), « T245 symetrie lissage » (diag lissage).
- Zip 21 fichiers + note + release (meme fabrication que T244).
ENVIRONNEMENT : le lab (/home/user/dlb-lab, JDK, caches) a DISPARU du sandbox entre
  les tours (reset comme celui du repo), et l'egress reseau n'autorise que GitHub/PyPI
  (pas adoptium/gradle.org/maven) -> impossible de compiler/graader localement.
  Verification T245 = statique (diff complet relu, equilibrage blocs, symboles).
  Si le workdir retombe A b215420 : timeout 170 git fetch origin arena/... (retenter),
  puis git reset --hard origin/arena/... — origin est fiable.
T246 (retour client 12/10 ~06:35) — 4 fuites comblees :
(A) tier charge T245 jamais utilise : regle 9/9 inatteignable a view distance 20
  (un coin de l'emprise depasse le carre charge). Assouplie : centre obligatoire,
  >=6/8 periph., >=2 coins. Repli logge en LOGGER.info (visible sans debug).
(B) fondu externe : NOUVEAU systeme universel borderFadePick + outsideSoilForSide
  (spec client 06:40) : cote W/E/N/S par depassement box, sol detecte 1 chunk
  au-dela du bord (16 sondes, cache OUTSIDE_SOIL_CACHE vide par clearDressingCaches),
  degrade proba 90%(1-d/R) patches posNoise(SALT_FADE). Applique dans dressAndPlant
  a TOUTE colonne non-vegetationOnly (inner ET outer ring). isNaturalTerrain inclut
  ICE/PACKED_ICE/SNOW_BLOCK : fade vers lacs geles OK.
(C) snow carpets : placeSnowOrUnsnow n'etait appele que dans le bloc
  `if (inner && !vegetationOnly)` de dressAndPlant -> 3/4 du ring nu. Desormais
  HORS du bloc inner : couvre inner+outer+emprise naturelle. surface-write cur/chosen
  mutualize sorti du bloc inner (autrefois inner-only).
(D) anneau gris : 3 filets : placeSnowOrUnsnow partout + unsnowExposedGrass au
  ring terrainRing()+NATURALIZE_EXTRA_RING(16) + NOUVEAU antiGrayFinalSweep public
  (StructureTerrainPrep) balayage ring complet charge-seul (isLoadedAt guard, 0 gen),
  planifie +40 ticks apres POST_Q dans Structures5Procedure.
Stamps [build T246] + « T246 symetrie lissage ». Zip 21 fichiers + NOTE + release.
NOTE compilation sandbox : toujours impossible (pas de JDK, egress GitHub/PyPI
seuls). Verification = statique (diff relu, equilibrage, symboles).
Process reset repo : TOUJOURS verifier git log --oneline -1 au debut du tour ;
retombe 3 fois a b215420 ; heal = fetch (retenter si timeout 170) + reset --hard origin/...

## T247 (2026-10-12) — CAMPAGNE SERVEUR 10 RUNS (via GitHub Actions)
- Mecanisme : commit marqueur [variants] -> workflow java21-build.yml -> ci/variants_server.py
  (10 mondes frais seed 123456789, RCON execute positioned 1200 90 0 run dlbtest dragon).
- Fix prealable : loadedPickCenter pivotait sur NULL sans joueur RCON -> jamais mesurable ;
  pivot = origine de recherche si p==null (commit 5f93792).
- Forceload +-20 chunks ajoute autour du point de test pour simuler le client qui pre-charge,
  MAIS 45 s insuffisants sur runner 2 CPU : le tier charge T245 n'a jamais trouve d'emprise
  chargee (sortie silencieuse cands.isEmpty()). Tier donc non mesure en CI.
- RESULTATS (voie seche T233c = chemin reel quand tier silencieux) :
  v01 defaut 369 ms | v02 tier-off 408 | v03 budget300 381 | v04 budget800 383 |
  v05 goodspan6 413 | v06 goodspan10 382 | v07 dryms600 390 | v08 dryms1500 373 |
  v09 maxr1024 342 (MEILLEUR) | v10 combo 377. Tous site 1018,187 dist 261.
  => SLA 0,5 s tenu meme sans tier (voie seche seule).
- DECISION : dryPickMaxR defaut 1536 -> 1024 (gain 7%, coherent : moins de sondes avant
  bulle 0). 1 run/variante => bruit possible, mais direction theoriquement coherente.
- Notes : audit [DLB-RINGAUDIT] non logue par la commande dlbringaudit (a investiguer si
  besoin) ; artifacts CI (blob.core.windows.net) NON telechargeables depuis la sandbox.

## T248 (2026-10-12) — CAMPAGNE 2 : 15 RUNS SERVEUR (run GH 38289148270, SUCCESS)
- Patchs script : attente ACTIVE « execute if loaded » des 4 coins du forceload (deadline 210 s),
  audit 300->90 s, capture tier_fail/candidats, 2 paires de repetitions (bruit).
- BRUIT MESURE : defaut x2 = 385/384 ms ; maxr768 x2 = 366/406 ms => bruit ~ +/-40 ms (10%).
- RESULTATS sec (ms) : r01 defaut 385 | r02 tier-off 404 | r03 maxr768 366 | r04 maxr1280 393 |
  r05 goodspan7 340(meilleur) | r06 goodspan9 498(pire,sgnf) | r07 dryms700 387 | r08 dryms1100 385 |
  r09 budget380 387 | r10 budget580 382 | r11 comboA 369 | r12 comboB 401 | r13 budget800 382 |
  r14 defaut-bis 384 | r15 maxr768-bis 406.
- TIER : absent des 15 runs => les 4 coins du forceload +-20c (1600 chunks, monde neuf) ne
  chargent pas en 3min30 sur runner 2 CPU (DLCORNERS jamais dit, confirme log r01).
- DECISIONS :
  * goodspan7=340 non retenu : non monotone entre campagnes (6=413/7=340/8=369-385/9=498)
    et bruit +/-40 ms => pas de preuve ; goodspan9=498 confirme qu'au-dela c'est pire
    (engorgement candidats) : GARDER goodSpan=8.
  * dryPickMs 700/1100 = neutre ; maxR 768/1280 = neutre => GARDER maxR 1024 (T247).
  * SLA 0,5 s tenu sur 25 runs cumules (max 413 ms) sur runner 2 CPU sous-clocké.
- Synthese 25 runs : aucune prop ne fait mieux que le bruit hormis maxR=1024 ; defauts figes.

## T249-T251 (2026-10-12) — MESURE DU TIER CHARGE T245 EN CI : REUSSIE
- Campagne 3 (forceload reduit +-16c, deadline 330s) : ECHEC — le log t01 montre ZERO
  activite serveur pendant toute l'attente => la commande forceload via mcrcon est
  silencieusement inerte (probablement non delivree). Ne plus utiliser forceload en CI.
- FIX INFRA : setworldspawn 1200 64 0 + gamerule spawnChunkRadius R (vanilla, charge
  prioritaire et synchrone) + sonde « execute if loaded » sur 4 coins + reponse RCON
  capturee dans le tableau (spawn_resp). 
- Campagne 4 (radius 10 = 441 chunks, deadline 150s) : tier ACTIF enfin !
  « sans bon candidat » 46/39 ms, 14 candidats charges. Bords pas encore prets (coins=False).
- Campagne 5 (radius 16 = 1089 chunks, deadline 300s) : coins=True, tier 153/227 ms,
  39 candidats, budget 480 respecte, 0 chunk genere, repli sec propre. Le site sec
  1018,187 ETAIT dans la zone chargee mais tous les verdicts reels des 39 centres
  ont ete refuses (eau/pente) => repli sec = comportement des portes reelles correct.
- EFFET DE BORD MESURE : spawn pret du test => sec 184-250 ms (vs 369-413 avant) :
  la generation async des chunks du spawn d'origine volait du CPU aux sondes bruit.
- BILAN 33 runs au total : tier prouve fonctionnel (enum + double cerveau + budget +
  verdict reel + repli) ; aucun reglage supplementaire justifie au-dela du bruit
  (+/-40 ms) ; maxR 1024 reste le seul changement retenu (T247).
- POINT DE VIGILANCE : cumul tier+sec pire cas = 227+459 = 686 ms sur runner 2 CPU
  (budgets separes). Si le SLA 0,5 s doit couvrir le TOTAL, prevoir un plafond global.

## T252-T253 (2026-10-12) — PLAFOND GLOBAL + TIER GAGNANT PROUVE + BEST VERSION
- T252 : plafond global de recherche par vague (prop dlb.search.totalBudgetMs) :
  le choix sec recoit le RELIQUAT (plancher 200 ms). Overload dryPickCenter(budgetCapMs).
- Campagne 6 (10 runs, run 38335411874, SUCCESS) : cumul tier+sec par plafond :
  400->410 ms | 500->511 ms | 800->603 | 640(defaut d'origine)->624-693.
  GOODSPAN-6 : LE TIER CHARGE A GAGNE (log verbatim) :
  « Zone choisie a 1018,187 (tier charge T245 : 310 ms, 5 sondes sechees, 34 ecartees
  eau/haut, 39 candidats charges, span sec=3, span reel=7, 0 chunk genere, phase suivante
  directe) » => fast-path prouve de bout en bout sur runner 2 CPU.
  Note : depassement residuel possible (~50 ms) car le budget sec est verifie par etapes.
- DECISION FINALE : totalBudgetMs defaut 640 -> 500 (borne la vague sous SLA 0,5 s ;
  400 juge trop agressif pour terrain oceanique, n=1).
- 43 runs serveur au total sur 6 campagnes, tous SUCCESS apres l'infra spawn-chunks.

## T254 (2026-10-13) — RETOUR VISUEL CLIENT T253 EN JEU + CHANTIER NEIGE
- Preuve site client : LE TIER CHARGE T245 A GAGNE CHEZ LE JOUEUR (log 06:11:39 :
  « tier charge T245 : 383 ms, 27 candidats charges, span reel=9, 0 chunk genere »).
  Voie seche 224-461 ms courant. Points perf restants : tier 490-495 ms par vague
  quand filtres tout ecartent (0-2 sondes sechees), bulle 1 757-866 ms (depassement
  cap connu), vagues multiples quand portes reelles refusent en serie, prepZone 85 s.
- RECHERCHE WEB effectuee (gradients biomes) : blend 15-63 blocs (Ultimate Biome
  Blend), dithering ordonne aux bords (Floyd-Steinberg/Bayer, Reddit fttb),
  palettes de transition en etapes 45/25/20/10 (minecraftgradient.blog), jitter
  brise-lignes (noiseposti.ng). Applique : fondu bilineaire + patches hashes.
- IMPLEMENTE T254 dans StructureTerrainPrep.java :
  * snowCoverHere DESORMAIS piloté par le TAUX REEL de neige mesure sur les tops
    des 4 chunks voisins (snowRateCell, cache ; SNOW layer au-dessus du sol ou
    SNOW_BLOCK ; repli froid-biome si chunk absent). Couverture = interpolation
    BILINEAIRE des 4 taux sur tout le chunk + patches deterministes (1000/1000) ;
    >=85 % partout -> couverture integrale conservee. Fini « neige partout en
    biome non neigeux » ET « 1-2 chunks seulement » ; fondu plein chunk (spread).
  * verifyGrassColumn : « smooth -ice » strict — ICE/PACKED_ICE/BLUE_ICE jamais
    repeints (avant : la glace des lacs etait convertie en herbe = rendu bugge
    « glace sur l'eau »). + snow appelée aussi hors biome neigeux (taux decide).
  * clearSnowCellCache vide aussi SNOW_RATE_CACHE + SNOW_NEIGHBOR_CACHE.
  * Supprime snowyNeighbors(int[])/snowBand (remplaces par snowyRates/bilineaire).
- T254 LIVRE (98e0cbf + livraison 6fa39da, CI 38347545394 SUCCESS) : neige =
  taux REEL par chunk (snowRateCell, cache, fondu bilineaire plein chunk),
  smooth -ice strict (ICE/PACKED/BLUE jamais repeint), snow hors-biome gate
  aux voisins, purge des 3 caches a clearSnowCellCache.
- T255 LIVRE (e236be6 + livraison ff4c4f1, CI 38350510604 SUCCESS, release T255) :
  retour client 13/10 « c'est si long » (pose dragon 2 min 23 : 7 vagues rejouant
  le tier a ~490 ms pour rien, 2x12 s d'emprises, prepZone 85 s) —
  (a) TIER_MISS_PIVOT/UNTIL/STREAK : skip du tier 8 s x serie (borne 30 s)
      si miss recent au meme pivot (+-64 blocs) — 7 vagues = 1 tier joue ;
  (b) budget adaptatif : a >175 ms, si <20 % des candidats ont un resultat,
      coupe la vague sterile (~490 ms -> ~175 ms) ;
  (c) chat : rappel toutes les 25 s (phase + duree) pendant paste/carve/post
      (PROGRESS_MSG_LAST_MS) + annonce finale reelle dans
      finishWhenTerrainReleased (« entierement terminee », duree totale) —
      le client n'avait jamais de confirmation apres les finitions.
  Honnetete : 2x12 s d'emprises = prix du verdict reel (pas de fix trivial),
  PRELOAD_CHUNKS_PER_TICK est une constante MORTE (aucun usage) — le goulot
  prepZone est le pipeline vanilla, indirectement reduit par (a).
  Zip = mod.zip (base 1881 fichiers) + ecrasement mod_project/src (1899 au
  total, 21,2 Mo). raw.githubusercontent.com bloque TLS dans le sandbox :
  verifier les liens via https://github.com/<repo>/raw/<sha>/... (302 attendu).
- A FAIRE (T256, consignes visuelles neige en attente) : ordre des passes snow
  (passe sol apres peinture AVANT replantage + passe FINALE apres tout, arbres +
  structure + mini-structures enneigees) ; zone interieure paste : replanter
  plantes + repaint sol ; derniere couche du rayon de terrain a enneiger si
  encore visible ; spread gradient 2-3 chunks si 1 chunk insuffisant ;
  generaliser comportement aux surfaces moddees equivalentes ; tier CI muet par
  vague (budget auto si 0 sonde).
- LIENS DE TELECHARGEMENT (retour client 13/10 « les liens ne fonctionnent pas ») :
  uploads.github.com est BLOQUE depuis le sandbox (EOF immediat) -> pas d'assets
  de release possibles ici. MAIS le repo est PUBLIC (isPrivate=false) : les liens
  raw.githubusercontent.com/<org>/<repo>/<sha>/<chemin> fonctionnent cote client
  (verifie via fetch_page : contenu OK). Toujours COLLER LES URL COMPLETES dans
  le message de livraison + dans les notes de release — ne jamais ecrire juste
  « liens verifies » sans les donner.
- T256 LIVRE (57d45d9 code + livraison e885cbf, CI 38451604861 SUCCESS,
  release T256) : degrade neige visible+symetrique (trou herbe hors biome,
  taux perimes -> invalidation par ecriture + clear par passe, amplification
  1,5x), dressAndPlant degate biome (snowInfluenceNear), REGLE FINALE client
  « dirt snowy -> toujours couche SNOW min » dans antiGrayFinalSweep
  (de-peinture seulement si dessus occupe non-neige). Test client T255 :
  pose dragon 44 s (vs 2 min 23) — 21 s de recherche 14 vagues restent le
  goulot (verdict reel ~1-1,5 s/site sur sa machine) ; messages T255 vus.
- T257 LIVRE (code + livraison fdf20a9, CI 38475481559 SUCCESS, release T257) :
  screenshots client 23:58 = « poussiere » partout + coupure rectangle au
  perimetre. Fixes : bruit blanc -> bruit de valeur 2D (cellules 8 blocs,
  semence fixe, congeres coherentes) ; SNOW_ZONE_* + retombee pct*(1-dE/ring)
  a 0 au perimetre ; retroaction coupee (taux figes toute la chaine, amplif
  1,5 et invalidation-par-ecriture du T256 retirees). Trou-herbe T256 et
  couche-min finale conserves. NOTE : marche de hauteur visible bord gauche
  de la zone sur ses screenshots (fenetre lissage adaptative) — propose au
  client pour prochain correctif.
- T258 LIVRE (code 48af188 + livraison 5ad3f94, CI 38477737485 SUCCESS,
  release T258) : plainte client 00:12 « surelevation de terrain direct
  colle a la structure, bloquee dans une montagne ». CAUSE RACINE (bons
  yeux) : la relaxation de pente (T121, emprise non exclue) convergent
  VERS la moyenne floutee de l'emprise (blur incluant les collines) —
  cone de compromis en l'air, anneau remblaye jusqu'a +46/+48 au mur
  (lissage bride 2107-2173 colonnes), restore y0 TROP TARD -> cassure
  frontiere = marches T246 p4..p11. FIX : emprise epinglee au sol zero
  (CLEARANCE_PLAN) AVANT limitSlope (nouveau param pinned ; null =
  comportement legacy) ; l'anneau rejoint le vrai niveau de base en
  pente <=2, never au-dessus ; restores finaux idempotents. BONUS :
  TIER_MISS memoire clee sur origin (etait position joueur -> en vol
  >64 blocs le skip n'agissait jamais, tier rejoue toutes les 6-8 s).
  NON TOUCHE : fenetre T89 +64/-64 (coupe flanc de montagne conservee),
  anti-gris T256, congeres T257. EN ATTENTE test client : T246 « marche
  max » doit tomber p2/p3 + bride ~0. LAGS <5 s toujours ouverts :
  poses 14-72 s, pires ticks 15,5 s pre-chargement + 12,6 s scanTrees.
- T259 LIVRE (code e3d3e34 + livraison e874182, CI 38484214068 SUCCESS,
  release T259). Retour client run 00:48 (banner informatif : jamais
  bumpe depuis T246 -> bumpe en T259 desormais ; run identifie T258
  via mesures log : bride 2107->1116-1573, marche p2). Cinq plaintes :
  (1) rectangle vert/delimitations = taux neige re-mesures APRES dress
  (clearDressingCaches en FIN de chaine, pas en tete) -> ~0 % en zone ->
  fondu refuse -> desenneigement 3/4 ; FIX : primeSnowRates au
  streamLoadedChunk (vierge, avec paletteAt) + clearSnowCellCache ne
  vide plus SNOW_RATE/NEIGHBOR. (2) retombee-a-zero T257 = couronne
  tondue -> PARITE perimetre 8 bl avec taux reel DEHORS. (3) regle
  litterale client : grass snowy en contexte neigeux = TOUJOURS layer
  (placeSnowOrUnsnow), desenneige seulement hors influence. (4) « 2
  chunks de glace » = grass snowy SANS layer (dessus gris-bleu vanilla),
  pas de la glace ; spread eau/glace desormais interdit (garde
  WATER/ICE/*). (5) montagnes 2e ed. = rampe conservee de T258 : cap
  remblai riseCapNearEmprise (+4 contact, +3/bl jusqu'a 18, coupe
  libre) applique clampSmoothDelta + limitSlope (param riseLimit) +
  limitContactSlope ; contact sol/base porte par la motte (confirmee
  plein ecran gris au coin structure). PERF (T260 plan, promis) :
  stall 19,4 s 200 reprises capDrops=0 (emprise async), scanTrees
  43 s+, 1140 chunks epingles tick principal, pose 105 s, total 139 s.

T260 (livre 14/10 ~02h20) — code c00ec3c, livraison cf500fc, CI
  38488242568 SUCCESS (Java 21), release T260 (liens raw au SHA
  verifies : note 200 integral, zip SHA = 20 851 225 o / 1881
  fichiers). Retour run T259 client (banner [build T259] confirme,
  pose 8,1 s / total 23 s) : 3 plaintes + 1 regle dure.
  FIX 1 tablier dur (consigne 01:46 « interdit a la zone proche de
  faire 3 blocs plus haut que le y sol » + « smooth jusqu'a rendre
  plat ») : dans smoothPassAfterPreload, apres limitContactSlope,
  ruban contact dEm 1..10 cappé a Y0+3 (coupe naturelle COMPRISE —
  c'etaient les « montagnes frollantes » = terrasses de coupe au
  pied) + re-relaxation contact ; log « T260 tablier : N ... ecretees
  a Y0+3 ». VIGILANCE T261 : si paroi naturelle haute persiste au-
  dela de dEm 10, une marche unique peut rester a la frontiere
  10/11 (ruban cappé par riseLimit cote montee) — etendre la
  relaxation a dEm<=14 si le client le signale.
  FIX 2 anneau gris (cadre snowy sans layer tout le tour) : le
  filet antiGrayFinalSweep tournait sur tick +40 dans POST_Q.poll
  AVANT la fin du raccord T240 (ordre non garanti) => supprime de
  Structures5Procedure, devenu le DERNIER geste inconditionnel de
  smoothAround (apres unsnowExposedGrass). Et flip du faiseur
  d'anneau : unsnowExposedGrass posait snowy=false meme en biome
  neigeux => en contexte neigeux (snowInfluenceNear) + dessus air,
  il POSE la layer SNOW ; snowy=false seulement hors contexte ou
  dessus occupe. Log « X recouvertes d'une layer SNOW, Y ...
  hors contexte ». REPONSE WEB (demande client « id plus
  specifique ») : MC-181555/MC-182423 — top snowy sans layer GRIS
  PAR DESIGN vanilla (overlay niveaux de gris jamais teinte biome) ;
  AUCUNE id/blockstate de contournement en 1.21 ; seule la layer
  (contexte neigeux) ou snowy=false (hors contexte) resout ; notre
  strategie EST le fix. Rayon vu par le client = DERNIERE couche de
  notre zone de raccord (ruban T240), pas le monde exterieur.
  FIX 3 terre dans la maison (consigne 01:49 tolerance zero) : sweep
  interieur deterministe post-pose dans POST_Q.poll (remplace le
  schedule +40) : PostJob enrichi (columns/baseCols/occupied/
  baseLevel du CarveJob) ; (A) colonnes structure nettoyees loY..hiY
  sauf occupied ; (B) colonnes d'air enfermees (4 voisins cardinaux
  dans baseCols, colonnes SANS entree template jamais carvees =
  poches seuil/linteau de porte) nettoyees baseLevel..maxY ; motte
  preservee (jamais < baseLevel). Log « T260 sweep interieur : N
  blocs ... retires ». Si N=0 avec terre visible => bug template,
  demander coordonnee exacte au client.
  PERF : chantier blitz T261 toujours ouvert (stall 19,4 s
  capDrops=0, scanTrees 43 s, 1140 chunks epingles, cible 5000 ms) ;
  T260 ajoute ~0,2-0,3 s de sweeps.
  PIEGE GIT : push du zip 20 Mo => premier push « remote hung up » ;
  retry avec git -c http.postBuffer=524288000 push => OK.

T261 (livre 14/10 ~03h45) — code 8748797, livraison 0bec1a5, CI
  38654589890 SUCCESS, release T261 (note raw 200, zip SHA = 20 852 523 o).
  Run T260 client (banner confirme, dragon Y 169->69, 51 s total) :
  3 plaintes : « toujours la montagne » (03:05), « LES ARBRES N ONT
  PAS TOUS ETES REMIS » (03:06), « Wtf il y a des trous » (03:07).
  INCIDENT SANDBOX : workspace local reset a b215420 au demarrage du
  tour (tous commits T260 absents localement) ; restaure via
  git fetch + git reset --hard origin/arena (origin = source de
  verite, verifiee 4847a7f + tag T260). Si ca se reproduit : meme
  procedure, jamais de force-push.
  FIX 1 paroi au mur (« montagne ») : cause = riseLimit = NATUREL +
  cap(T259) — au-dessus d'un creux (terrain min 53 vs base 69) le
  ruban contact ne pouvait jamais remonter a Y0 ; T246 post-pose
  « p13 d1 » le mesurait. FIX : plancher riseLimit = Y0+3 pour
  dEm 1..10 quand CLEARANCE_PLAN matches (tablier bidirectionnel,
  symetrique du cap T260). Log « T261 tablier bidirectionnel : N
  autorisees a remonter jusqu'a Y0+3 ».
  FIX 2 trous : fillCrevasses rayon 3 invisible pour crevasse 12-16
  blocs (berges hors fenetre) ; double passe R=3 + R=7 (wrapper,
  comblement 80 % mediane inchange). Crevasse de lave comblee avant
  flou.
  FIX 3 arbres : (a) verifyGrassSurface pose couches SNOW AVANT
  replant -> feature vanilla exige case libre au pied -> refus
  silencieux. FIX : clearTrunkColumn (snow/plantes canBeReplaced,
  break au 1er solide, top 10 mega / 7 petit) avant placeRealTree.
  (b) halo 16 hors zone chargee -> skip pur (T157) ; repli petit
  type halo 8 (shrunken++), skip seulement si halo 8 hors zone
  (unloadedSkipped++). Log enrichi : planted (shrunken, refused),
  duplicates, protectedSkipped (T92), unloadedSkipped.
  VIGILANCES T262 : (1) ruban contact peut remonter de +16 blocs au-
  dessus d'une crevasse comblee — verifier visuellement que le
  client ne relit pas ca comme un « remblai » ; (2) fillCrevasses
  R=7 ne comble pas les canyons > ~14 blocs (gamma : elargir R ou
  rejet de zone sur span > 12 — non fait pour eviter les boucles de
  repli de selection) ; (3) perf : chantier blitz toujours ouvert
  (stall 8,4 s capDrops=0, pre-chargement ~7 s, dressAndPlant 2,4 s).

T262 (livre 15/10 ~01h55) — code 3df8f2f, livraison 3b0d193, CI
  38666314697 SUCCESS, release T262 (note raw 200 integral).
  ANALYSE 5 IMAGES client run T260 : falaise/crevasse/damier
  (03.07.18) => regle T261 (tablier bidirectionnel + fillCrevasses
  R=3+7) ; ILOT VERT emprise (03.07.47) => NOUVEAU fix T262 :
  verifyGrassColumn sautait TOUTE colonne isProtected (emprise+marge
  1) pre ET post-pose -> herbe a Y0 jamais couchee. Fix : protection
  limitee a la structure (surface haute ou bloc non naturel) ; sol
  interieur naturel <= Y0+3 recoit herbe+couche (innerGround via
  CLEARANCE_PLAN.contains + groundY+3). Dirt au pied (03.06.54) :
  partie reglee T262, chemins = template NBT nus par design. Bord
  sapins nets (03.06.31) => regle T261. Ruban nu/potirons vers le
  lac (03.06.19) = allee « The Broken Path » DU TEMPLATE (advancement
  du meme nom debloque par le client 03:04:56, preuve) : NE PAS
  repeindre sans ordre explicite (flag possible si il veut la neige
  dessus). Damier flancs = style congeres T243/T256 voulu.
  AUDIT ZIP (plainte client « parties de code non a jour ») : FONDEE.
  src/main/java du zip etait 100 % a jour MAIS le zip contient un
  DOUBLE de chaque .java sous resources/deepluckyblock_patch/
  FIXES_MCREATOR/src/main/java (mecanisme applyMcreatorPatch du
  client) : 162 fichiers, 8 OBSOLETES = exactement nos 8 fichiers
  modifies depuis T232 (DlbTestCommands inclu). T262 : double
  synchronise (cp git -> staging -> zip) + livraison DUO :
  SRC-COMPLET (20 898 693 o) + SRC-SEUL (src/ uniquement, 9 252 981 o,
  324 .java). PREUVE checksum SHA-256 : 0 mismatch / 9 fichiers git
  dans main/java ET FIXES_MCREATOR. PROCEDURE DESORMAIS A CHAQUE
  LIVRAISON : overlay git -> staging, synchro des doubles
  FIXES_MCREATOR (boucle find $FIX -name '*.java' + cp si diff),
  rezip, verif checksums. Liaison grille de verification note dans
  T262-NOTE.

T263 (livre 15/10 ~05h50) — code d67b257, livraison ff634be, CI
  38669716609 SUCCESS, release T263 (note raw 200 via fetch_page,
  tailles au SHA = local ; curl direct raw.githubusercontent bloque
  par proxy sandbox hors allowlist -> toujours utiliser fetch_page).
  CONTEXT RUN CLIENT (log 03:33-03:57) : banner ENCORE [build T261]
  (T262 pas importe au lancement). Dragon 90 s total (plus lent que
  T260 51 s : verifyGrassSurface 12 s, decorate 52,5 s ; stall async
  32,8 s). Lissage bride 1281+43+2889+112 colonnes, derive max 15 bl,
  T246 toujours « ... d1 » cote E. Y fallback 166 -> 70 OK.
  ORDRE 1 « smooth = etalement en baissant, JAMAIS de montagnes
  (WorldEdit) » : CONFIRME par son log (« remblai +4 contact puis
  +3/bl jusqu'a +64 », T246 remblai moy +2.8). FIX : riseCapNearEmprise
  AVANT +4@contact / +3*bl -> +64 ; APRES +2@contact, +1<=4bl, 0
  au-dela (T263). Suppression du bloc « tablier bidirectionnel » T261
  (plancher riseLimit=Y0+3 ruban 1..10 = la boite). Textes logs
  lissage bride adaptative T89+T263. ATTENTION par la suite : la
  falaise p13 est evitee par LA DESCENTE (drop libre contact) -- si
  le client re-voit une marche au contact, regarder limitSlope drop
  cote ruban.
  ORDRE 2 « scan final chunk-par-chunk, force layer sur tout grass
  snowy, taux pattern » : methode forceSnowLayerSweep (pattern
  litteral 39 valeurs client : 24x1, 12x2, 3x3 ; k via
  floorMod(x*341873128712 + z*132897987541 + 104729, 39),
  deterministe ; pose si air au-dessus, releve si snow<k, sinon
  ne touche rien ; Heightmap MOTION_BLOCKING_NO_LEAVES du chunk ;
  setBlock flags 3 ; log compteurs + repartition + ms). Appelee
  dans decorateFinish (release, apres raccord T240, AVANT releases)
  + decorateChain historique (avant SettleGate.wait).
  PROCEDURE LIVRAISON CONSOLIDEE (depuis T262) : staging overlay
  zip T(n-1) + cp sources git + synchro kit FIXES_MCREATOR + verif
  0 mismatch main&kit + zips SRC-COMPLET/SRC-SEUL + note + release
  + raw checks + memo. Vigilances : sed banner = version PRECEDENTE
  (T262 cette fois, pas T261) ; pas de backticks dans --notes
  (--notes-file OK).
  CHANTIERS OUVERTS : perf (90 s -> cible 5000 ms ; nouveau sweep
  ajoute qques ms ; le vrai goulot = async stall 33 s + verify 12 s
  + decorate 52 s) ; allee Broken Path (flag si il veut la neiger) ;
  damier flancs style congeres (param resserrable).

T264 (livre 15/10 ~07h25) — code b144e2b, livraison f0828c8, CI
  38675388636 SUCCESS, release T264. 3e reset sandbox (b215420)
  detecte au debut de ce tour -> restaure via origin (7f4a485).
  RUN T263 CLIENT (banner OK, dragon @3,97,-607) : boite regle
  (T246 remblai moy 0.2-0.7), duree 37 s, stall 33 s parti, keep-up
  residuel = paste 16,4 s. SWEEP T263 BUG : « 537 snowy -> 0 posees
  0 relevees » car fenetre ring+4 TROP JUSTE — passes herbe/snowy
  (naturalise/replant/raccord/track/mesure finale) travaillent a
  ring + NATURALIZE_EXTRA_RING (16) => bande 12 bl non couverte
  (mesures client N4 O5 S0 E13 ; arbres replantes nord = sol nu,
  meme marge). FIX : sweep r = terrainRing()+NATURALIZE_EXTRA_RING+2
  + garde level.getChunkSource().hasChunk(cx,cz) (skippedChunks) +
  log T264 avec skippes. POINT OUVERT (note client) : rectangles
  gris-plat INTERNES = roche exposee par coupe (derive 21 bl), pas
  du grass snowy ; reverdissage/naturalize a creuser au prochain
  ticket sur confirmation client.
  PROCEDURE CONFIRM-RESET : toujours git rev-parse HEAD en debut de
  tour (3 resets deja) -> fetch + reset --hard origin/arena si ecart.

T265 (livre 15/10 ~07h55) — code 6cd8087, livraison 03324d9, CI
  38678511054 SUCCESS, release T265. SRC-SEUL 9 173 747 o (1617 f.),
  COMPLET 20 805 731 o (1881 f.), checksums 8/8 OK relus depuis les
  zips finaux, kit FIXES_MCREATOR 241 f. preserves.
  RUN T264 CLIENT (banner OK, 3 dragons @-62,98,166 / -216,95,269 /
  -304,90,296 New World, 22/16/13 s) : fenetre OK (99 chunks, 20 800
  col, 0 skip) MAIS « 45/486/262 snowy trouves -> 0 posees
  0 relevees » = detection aveugle. 5 plaintes (05:33-05:38) : (a)
  bordure dirt-version-neige sans slab TOUT autour ; (b) arbres/
  structure non neiges ; (c) grassy-snowy nus colles a la structure +
  big zone ; (d) « rayon exterieur oublie a la repose » ; (e) idem a
  la neige. CAUSE 1 : mega-sapins replantes convertissent le sol en
  PODZOL snowy (vanilla) + mycelium -> filtre grass_block seul = 0.
  CAUSE 2 : heightmap caduque apres pose en section -> garde-fou
  above bloque meme les snowy trouves. FIX : detection =
  SnowyDirtBlock entier (grass/podzol/mycelium, SNOWNY=true) + probe
  vertical sky+2..sky-12 -> surfY, pose a surfY+1, compteurs probed /
  debugShown (12 premiers skips logues avec block(sky-1)=...). Log
  « T265 sweep-neige final : ... snowy trouves (dont P via probe) ».
  RITUEL T254 CONFIRME : --notes-file (pas de backticks), liens raw
  au SHA livraison, fetch note 200 + gh api tailles fait.
  CHANTIERS OUVERTS : chevauchement inter-spawns 88-154 bl (zones
  ring+18 se chevauchent : le raz d'un spawn peut effacer les couches
  du precedent — a surveiller si bandes residuelles apres T265) ;
  rectangles gris-plat internes (roche, attente confirmation) ;
  perf 13-22 s (cible 5000 ms) ; Broken Path / damier congeres sans
  ordre.

T266 (livre 15/10 ~08h35) — code 9695d1f, livraison 8ac0eda, CI
  38681744434 SUCCESS, release T266. SRC-SEUL 9 175 073 o, COMPLET
  20 807 057 o, checksums 8/8, kit 241 f. preserves, parite 1881/1617.
  RUN T265 CLIENT (banner OK 06:16:14, dragon @-95,90,-363, 28 s) :
  sweep 99 chunks / 20 800 col / 14 068 snowy trouves (13 863 via
  probe !) / 779 posees + 3 389 relevees / 182 ms. PLAINTES : (1) « il
  manque plus de la moitie » → ~9 900 snowy TROUVES restes nus =
  garde-fou « above air|snow » : abandons silencieux sous feuilles/
  troncs (canopee dense > moitie de l'anneau), murs/toit structure
  (« trous colles a la structure »), herbe haute. (2) « etape finale
  apres replantation pour neiger arbres/structures » → exists deja
  (sweep APRES decorate) mais condition sautait. (3) « trou ENORME
  wtf » → montagne O/S 83-94 bl denivele + smooth descente bride -64
  = paroi franche ; PAS touche au terrain en T266 ; 3 options
  proposees (A glacis interne ring+16 [reco], B fenetre ring+48/64,
  C falaise habillee) — ATTENDRE son choix. FIX T266 : canBeReplaced
  = air ; pose sur-obstacle par remontee colonne jusqu'au 1er niveau
  libre (plafond sky+40) → neige sur canopee/structure/corniches ;
  compteurs capped/deja-epais/skips-obstacle ; log T266 detaille.
  ATTENDU prochain run : C ≈ 9-10 k, N+M+C ≈ Z, skips ≈ 0.

T267 (livre 15/10 ~13h20) — code 7359c42, livraison 80b6491, CI
  38725858806 SUCCESS, release T267. SRC-SEUL 9 177 349 o, COMPLET
  20 809 333 o, checksums 8/8, parite 1881/1617, kit 241. 4e RESET
  sandbox (b215420) en debut de tour -> fetch+reset origin OK.
  RUN T266 CLIENT (banner OK 12:42:52, 2 dragons @880,90,-139 61 s /
  @737,87,-155 11 s) : sweeps 12 056/16 770 snowy trouves, 11 868+188
  puis 15 933+837 poses, 0 skip — MAIS client 12:52 « TOUTE zone
  structure : 0 layer + grand contour ». CAUSE : sonde sky±12 <
  hauteur toit structure et troncs grands sapins (NO_LEAVES compte
  troncs) → sol interieur jamais atteint = skips silencieux hors
  compteurs ; seules zones degagees posees (d'ou damier visible).
  FIX 1 : sonde sky-48 + skippedNoSurf compte + debug 12 rebadge T267.
  PLAINTE 2 « tu repetes trop parfaitement ton pattern : des TAUX pas
  un pattern bete ». FIX 2 : tableau 39+mix lineaire SUPPRIME, k tire
  splitmix64(x,z), u ∈ [0,1), seuils 24/39 et 36/39 = TAUX EXACTS
  (24/12/3), deterministe, zero strie. PLAINTE 3 « ICE = eau, surface
  intouchable, sauf centre structure = supprimee ». FIX 3 : (a)
  waterColumns (masque lissage) : ICE/PACKED/BLUE/FROSTED en surface =
  wet (+ icy count, log « dont Y gelees ») ; (b) sweep : couche refusee
  si support (capY-1) = glace (skipObstacle) ; (c) centre : deja raze
  par clearFootprint avant terrain (log run « chunks centraux +
  clearFootprint termines AVANT preparation »). Deja proteges : T254
  verifyGrass, T259 placeSnowOrUnsnow, fixLiquids (lac gele ni graine
  ni traite). ATTENDU run : Z en hausse (sous-toit/tronc comptes), Q
  (sans snowy a -48) petit, lacs geles intacts. TOUJOURS OUVERT :
  « trou enorme » T266 = options A/B/C SANS reponse client (A glacis
  interne / B fenetre ring+48..64 / C falaise habillee) — NE PAS coder
  sans ordre. MEMO : raw.githubusercontent.com n'est PAS accessible en
  curl sandbox (HTTP 000) → verif note via fetch_page.

T268 (livre 15/10 ~22h30) — code 57bf1bf, livraison fe5cb27, CI
  38812456984 SUCCESS, release T268. SRC-SEUL 9 179 435 o, COMPLET
  20 811 419 o, checksums 8/8, parite 1881/1617, kit 241. 5e RESET
  sandbox -> fetch+reset origin OK.
  RUN T267 CLIENT (banner OK 23:14:08, dragon @1218,-55, Y 242->64) :
  sweep 20 800 col, 14 703 snowy (14 110 via probe OK), 13 967+736
  poses, 0 skip, taux 60,9/31,1/8,0 — MAIS 6 097 « sans snowy » = 29 %
  = le GRIS des 7 screens chronologiques client. VERDICT CLIENT JUSTE
  (« layer pas mise ») — le gris a 2 racines, meme cause « pas de
  couche » : (1) grass_block meme snowy=true rend son DESSUS teinte
  biome (le blanc vanilla = LA COUCHE au-dessus, pas le top du bloc) et
  verifyGrassColumn repeint GRASS_BLOCK snowy=false nu ; (2) le sweep
  ne visait que SnowyDirtBlock snowy=true -> pierre/gravier/podzol/
  chemins des coupes exclus (bancs tailles, grand patch SE). FIX T268
  « neige universelle » : scan = 1er CORPS PLEIN depuis sky+2 (air/
  canBeReplaced/POWDER_SNOW montent ; SNOW layer -> surfY=y-1 support ;
  fluid -> wetCol ; ICE/PACKED/BLUE/FROSTED -> wetCol) puis pipeline
  identique (pose/raise/walk-up+ice guard) sur TOUT solide : herbe,
  terre, pierre, gravier, podzol, sable, chemin, snow block, feuilles,
  troncs, toits, vitres. Garde-fou T259 renforce : base surfY glace
  (neige pre-existante sur lac gele) -> wetCol, jamais epaissie.
  Compteurs : waterIce (eau+glace intouchees), skippedNoSurf = colonnes
  vides. Log renomme « T268 sweep-neige universel ». LIMITE signalee au
  client : faces VERTICALES du carre ne peuvent porter de couche ;
  corniches snow_block possibles en T269 si demande. TOUJOURS OUVERT :
  trou enorme A/B/C (A glacis recommande), perf replantTrees (LAGPROBE
  2082 ms), chevauchement inter-spawns ring+18, rectangles roche
  internes (estompes par T268 mais forme = fenetre de coupe).

## T269 (16/10/2026 ~05h56) — passe B sol interieur + purge structure baseLevel/BFS
- Run T268 valide "presque bon", 2 restes : (1) sol sous toit/canopee peint herbe mais SANS couche (T268 posait au 1er corps plein ; T267 sondait le snowy en dessous = regression) ; (2) terrain residuel EN structure (sweep T260 : purge loY..hiY ratait baseLevel..loY-1 des pieces hautes ; interieur vrai = test 4-voisins qui ne marche qu'en fente 1 bloc, cours gardees).
- Code (commit 6b7cb54, CI 38845771275 SUCCESS) :
  * StructureTerrainPrep.forceSnowLayerSweep : passe B par colonne quand le 1er corps n'est pas le sol herbe -> descente jusqu'au 1er SnowyDirtBlock, couche dessus si air/remplacable/ou couche < k ; pas de walk-up ; compteur `inner`, log "T269 sweep-neige universel ... + {inner} sol-interieur (T269)".
  * Structures5Procedure.sweepInteriorTerrain : (A) start = min(lo, baseLevel) purge baseLevel..hi (jamais sous baseLevel-1 = peau interieure peinte conservee) ; (B) inondation BFS depuis bord bbox sur colonnes hors baseCols -> non-inondees = interieur vrai -> purge baseLevel..top, masque `occupied` conserve (jardins NBT). Log "T269 sweep interieur de".
  * Banner build T269 (DlbTestCommands).
- Livraison : 2 zips staged (SEUL 9 183 459 o/1617 f, COMPLET 20 815 443 o/1881 f, kit 241), checksums relus 12/12 fois depuis les zips finaux, cp livraison, NOTE, commit livraison 941e6e2 + push.
- Release T269 : uploads.github.com INJOIGNABLE sandbox (SSL reset, 403 identifie, 15 retries) -> release creee --notes-file SANS assets, body PATCHe avec liens raw @941e6e2 (3 fichiers). fetch_page NOTE raw = 200 contenu conforme. Pas d'assets gh cette fois (raw = meca historique).
- Ouvert : trou geant A/B/C (choix), faces verticales, perf replant (LAGPROBE 5-10 s), chevauchement spawns, villager suffocation @1678,80,-16 observee run T268.
