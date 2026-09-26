# Statut actuel — 26 septembre 2026

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
