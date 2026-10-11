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
