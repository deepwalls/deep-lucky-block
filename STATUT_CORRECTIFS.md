## Mise à jour — validation finale en attente

La livraison ZIP précédente correspond à **0fefe6d**, pas aux derniers correctifs.
Ne pas la présenter comme une livraison finale des travaux ci-dessous.

- Session serveur `36200434878` (8cee194) : fixtures eau (45 120 sources),
  coffres/rotations et protection du sol **réussies** ; citadelle **43,40 s**,
  observatoire **24,60 s**, dragon **30,20 s**. Échec global : cirque bloqué
  au-delà de 240 s ; bateau, Everest et Crimson Lake non atteints.
- `6a9aaca` : sources d’eau existantes ignorées dans le quota et les écritures,
  arrêt de la recherche de fond lorsque le niveau demandé est déjà une source,
  anticipation du chargement de la rive. `./gradlew build` Java 21 réussi,
  run `36201069741`.
- `50c2de0` : préchargement de l’emprise des poses brutes (cirque/bateau),
  finitions différées jusqu’à la fin des files de pose/nettoyage, libération
  des chunks différée pendant la chaîne de décoration de la même emprise.
  `./gradlew build` Java 21 réussi, run `36201193872`.
- Harness : attente des véritables marqueurs de fin des deux pipelines,
  y compris le post-traitement ; syntaxe Python vérifiée.

Ces deux derniers correctifs sont **compilés mais pas encore validés en jeu**.
Une nouvelle session serveur nécessite confirmation de la dérogation à la
consigne initiale « une seule vérification ». Aucun nouveau serveur lancé pour
ces deux correctifs. L’objectif inférieur à 60 s pour les sept structures et
la restauration des océans naturels ne sont pas encore démontrés.
Le ZIP final devra contenir trois sources modifiées, dont Structures4Procedure.java.

---

# Correctifs terrain — compilation et session serveur vérifiées

Cette branche reste en brouillon : compilation et génération serveur vérifiées, mais validation visuelle et restauration complète de l’eau encore ouvertes.

## Dernier résultat

Run serveur réussi : https://github.com/deepwalls/deep-lucky-block/actions/runs/36199003753 . Une session, trois structures : citadelle 51,10 s, observatoire 23,90 s, dragon 31,20 s de bout en bout. Fixwater actif, mais plafond de 40 000 blocs atteint sur la citadelle ; aucun coffre vide admissible rencontré dans ces tirages. Aucun contrôle visuel effectué.

Les deux fichiers Java ont été regroupés dans `livraison/correctifs-src-testes-serveur.zip` (archive de livraison non versionnée). Voir `RAPPORT_TEST_SERVEUR.md` pour les mesures, les réserves et les instructions. Les sections ci-dessous sont historiques.

## État actuel — compilation débloquée

Les sections suivantes conservent l’historique des blocages ; elles ne décrivent plus toutes l’état actuel.

- Typage des features d’arbres corrigé sans cast forcé : build complet réussi sur GitHub Actions (run 36198252292).
- Mini-structures : multiplicateur par site entre ×1,2 et ×2,9 appliqué à la cible et au quota naturel ; le terrain et les protections peuvent réduire le nombre effectivement posé.
- Rotations 0/90/180/270 et miroir conservés ; origine transformée corrigée pour correspondre à l’emprise vérifiée. Protection contre le chevauchement de l’ensemble du décor avec la structure principale ; rejet des emprises non chargées.
- Coffres vides des templates : attribution différée d’une table vanilla de village avec graine aléatoire. Tables et inventaires existants préservés. Liste explicite : maisons des cinq biomes, pêcheur, berger, tanneur et cartographe. Aucun ajout de table bastion, Nether, End ou manoir.
- Build complet après ces changements réussi : https://github.com/deepwalls/deep-lucky-block/actions/runs/36198392966
- Ces résultats valident la compilation, pas le comportement en jeu. Fixwater, creux du terrain, chronométrage et test serveur restent à traiter. Aucun ZIP de livraison produit.



## Contenu

Le dépôt versionne actuellement le projet dans `deep_lucky_block_mcreator.zip`, pas dans un arbre source. Le fichier `mod_project/src/main/java/deepluckyblock/procedures/StructureTerrainPrep.java` est ajouté explicitement pour rendre le travail extrait accessible à la revue. Il contient aussi les modifications déjà présentes dans le workspace avant cette reprise, notamment le placement des arbres par feature vanilla et l'appel de correction d'eau avant lissage. Ces modifications ne sont pas validées.

Dernières corrections : suppression des méthodes dupliquées et du chemin accidentellement inséré dans le code ; restauration de la surcharge fixWaterNearStructure ; détection d'eau sur les colonnes chargées à WORLD_SURFACE - 1 ; utilisation de ServerLevel.scheduleTick pour demander un tick de fluide.

## Validation

- `./gradlew build` : échec avant compilation, Java absent et JAVA_HOME non défini.
- Première tentative d’installation système : permission refusée sans sudo. À la reprise, `sudo -n` fonctionne, mais les dépôts APT sont injoignables.
- Téléchargement du JDK via api.adoptium.net : échec TLS SSL_ERROR_SYSCALL.
- Aucun serveur lancé ; aucune structure générée dans cette session ; aucune mesure de temps disponible.
- Aucune garantie de compilation ou de résultat visuel.

## Reste à faire

- Vérifier les API NeoForge 1.21.1 et compiler avec Java 21.
- Vérifier le remplissage des plans d'eau coupés : les ticks natifs seuls ne sont pas un équivalent de WorldEdit fixwater. Vérifier aussi les mécanismes qui neutralisent les ticks pendant les travaux.
- Diagnostiquer et corriger le creusement près des structures. Le lissage utilise toujours des moyennes entières ; le précédent rapport ne constitue pas une validation.
- Ajouter le multiplicateur aléatoire 1,2–2,9 aux décors et les tables de butin de villages pour les coffres vides, sans écraser les inventaires ou tables existants.
- Vérifier les emprises transformées des décors avec rotation et miroir.
- Effectuer un run serveur instrumenté et relever les temps complets par structure ; optimiser sur ces mesures.
- Produire ensuite un ZIP contenant uniquement les fichiers modifiés sous `src/...`, sans caches ni projet complet.

Aucun ZIP livré avant le test serveur demandé. Aucun changement de dépendances, gradle.properties ou settings.gradle.

## Reprise : installation de Java 21

- `sudo -n true` réussit : les droits administrateur ne sont plus le blocage.
- La consultation des releases Temurin via l’API GitHub réussit.
- Le téléchargement de l’archive Temurin échoue sur `release-assets.githubusercontent.com` avec `SSL_ERROR_SYSCALL`.
- Les endpoints Azul, Oracle, OpenJDK et Corretto échouent également à établir TLS.
- `sudo -n apt-get update` ne parvient pas à joindre les dépôts Debian ; `apt-get install openjdk-21-jdk-headless` ne trouve donc pas le paquet. Sa disponibilité dans les dépôts configurés reste à vérifier une fois l’accès rétabli.
- Java n’est toujours pas installé. Aucun nouveau build ni serveur lancé. Aucun correctif fonctionnel supplémentaire ajouté.

Reprise possible après rétablissement de l’accès réseau aux distributions Java et aux dépôts Gradle/Maven, ou mise à disposition d’un JDK 21 Linux x86_64 dans le workspace. Ne pas confondre une tentative de téléchargement avec une installation réussie.

## Validation distante sur GitHub Actions

Le blocage réseau local est contourné en exécutant le build sur un runner GitHub, sans désactiver TLS ni modifier les dépendances du projet.

- Workflow : `.github/workflows/java21-build.yml` ; déclenché par push sur la branche de cette session.
- Java 21 installé avec succès via `actions/setup-java` (Temurin).
- Projet extrait depuis le ZIP versionné, puis recouvert par les sources suivies de `mod_project/src`.
- Gradle et les artefacts Minecraft/NeoForge téléchargés avec succès.
- Deux builds distants échouent ; arrêt conformément à la consigne des deux échecs consécutifs.
- Diagnostic confirmé au second run : `StructureTerrainPrep.java:4032`, conversion générique illégale de `Reference<ConfiguredFeature<?,?>>` en `Holder<ConfiguredFeature<TreeConfiguration,?>>`. Le correctif devra conserver le type générique fourni par le registre plutôt que forcer ce cast.
- Run avec diagnostic accessible dans les annotations : https://github.com/deepwalls/deep-lucky-block/actions/runs/36196091397
- Aucun serveur Minecraft lancé ; aucune performance mesurée ; aucun ZIP validé livré.

L’environnement de build distant fonctionne désormais. Java reste absent du sandbox local ; ce n’est plus bloquant pour compiler sur GitHub. Le lancement manuel du workflow via l’intégration renvoie 403, mais son déclenchement automatique par push fonctionne.
