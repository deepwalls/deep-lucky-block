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
