# Statut actuel — 6 octobre 2026

**T229 (dernier en date)** : dragon jamais posé en monde neuf — blocage silencieux
du secours ciblé réparé (re-demande + saut/abandon honnête), candidats proches à
nouveau éligibles via chargement tâche de fond + file différée bornée (fin du
téléport ~1 km systématique), diagnostics d'attente rendus visibles. Compilation
via CI uniquement ; validation en jeu à faire par l'utilisateur.
Voir `livraison/fix-t228-struct5/NOTICE.md` et `JOURNAL_AUTONOME.md`.

## 26 septembre 2026

Sources **`79d3edd`**, compilation Java 21 et tests fonctionnels PASS.
Dernière session serveur : **36209068656**, check **108311667567**.
Les sept structures terminent ; **six objectifs de durée sur sept respectés**.

**Everest : 35,39 s, objectif ≤30 s NON atteint.**
Crimson Lake : **101,80 s**, objectif 10–120 s respecté, enfoncement de dix blocs
et dégagement avant pose vérifiés. Les cinq autres structures sont sous 30 s.

Les **cinq nouveaux cycles** autorisés après le rapport `e256a7b` sont consommés.
Aucune sixième relance de cette série effectuée. Attente d’une nouvelle autorisation
avant de poursuivre. Aucun résultat « tout conforme » n’est revendiqué.

Le préchargement Everest seul prend 31,449 s. Le diagnostic CI relève 33,003 s CPU
cumulées dans les threads de travail sur 35,397 s murales, contre 315 ms rapportées
par les collecteurs JVM : le travail moteur reste le principal axe à examiner.
Ce n’est pas un profil par méthode, ni la preuve d’un gain systématique de l’ordre centré.

Archive actuelle : `livraison/optimisations-src-79d3edd-validation-partielle.zip`.
Sept fichiers modifiés uniquement dans leur hiérarchie `src/...`, ZIP vérifié.
Les anciennes archives sont dépassées. Contrôle visuel client restant.

Détails, résultats, historique des deux autorisations et checksum :
`RAPPORT_TEST_SERVEUR.md`. Recherche : `RECHERCHE_OPTIMISATION_30S.md`.
PR en brouillon : https://github.com/deepwalls/deep-lucky-block/pull/1.

## Session 27/10 (suite) — verdict seams + T298 livré

- **Seams (plainte « lignes strictes » du smooth)** : non reproduites sur le
  terrain. Les murs mesurés (x=1488, z=189) = silhouette du dragon dans le
  heightmap (deepslate/tuff des deux rives, ci/analyze_seams.py section D).
  Hors emprise : 0 mur terrain post-pose (dragon, seed banc 123456789) ;
  everest/citadel confirmés propres côté terrain.
- **T298 livré** : panne d'attente d'emprise = vrai signal de grève du
  générateur (plus aucun chunk livré nulle part depuis 4 s) au lieu du
  compteur-de-zone qui tirait au moment même où la zone se complétait (4,1 s
  mesurés, borne 4 s). Abandon = marqueur console + joueur toujours visible,
  purge défensive des files de pose. Textes d'abandon honnêtes.
- Banc : iter-20 = 8,4 s ; iter-21 = 6,3 s ; iter-22 (T298) = 7,9 s.
  Rapports et scans : `ci/results-t291-bench/`.

## Session 27/10 (dlbverify) — T299 livré, pipeline CI vert en sandbox

- **dlbverify** : reproduction exacte du pipeline `ci/server_check.py` en
  sandbox (nouvel outil permanent `ci/bench_verify.py`, commande RCON).
- **FAIL smooth** : assertion T89 rendue obsolète par l'adoucissement de
  marche (le rebord d'un plateau passe 240->238 comme dessiné par T263+) —
  invariant porté à « jamais sous top=237 dans les 8 » + fixture déplacée
  hors spawn (6000,6000) + ligne diagn [DLBVERIFY-DBG] conservation gracieuse.
- **FAIL eau (T299, cause racine réelle)** : la grille T127 ne voyait que
  l'air — une cellule d'eau courante (résidu) n'était jamais reprise en
  source et restait flowing en poche (reprod : 9 colonnes x 5 blocs, chunk
  (2,-3)). Grille élargie : trou = air OU liquide non-source. Verrous
  T77/T103/T232/T264 inchangés (tryFillColumn garde tout).
- **Validation** : ALL PASS 2/2 sandbox (r8/r9) incluant la fin de chaine
  (lake + tickets jamais atteints avant). Dragon : déterminisme + temps
  en cours de mesure (iter-23).
