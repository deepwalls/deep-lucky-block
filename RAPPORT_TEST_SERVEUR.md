# Génération des structures — dernier résultat

## Statut

**6 plafonds sur 7 respectés lors du dernier essai. Everest reste hors objectif.**
Sources testées : `511a507`. Build Java 21 réussi et serveur lancé ; run
[36205331210](https://github.com/deepwalls/deep-lucky-block/actions/runs/36205331210),
check `108300537437`. Échec global uniquement sur le plafond Everest dans ce test.
Les cinq cycles supplémentaires autorisés ont été consommés. Aucune sixième
session ne doit être lancée sans nouvelle confirmation.

## Temps réellement mesurés

Temps mural entre l’envoi de la commande et la fin des travaux, pas seulement le paste.
Environnement : Minecraft **1.21.1**, NeoForge **21.1.77**, Java 21, **2 CPU / 2 Go**,
graine `123456789`. Sept générations successives, zones distinctes.

| Structure | Durée | Plafond | Résultat |
|---|---:|---:|---|
| Citadelle | **28,70 s** | 30 s | PASS |
| Observatoire | **26,10 s** | 30 s | PASS |
| Dragon | **28,30 s** | 30 s | PASS |
| Cirque | **7,90 s** | 30 s | PASS |
| Bateau | **9,80 s** | 30 s | PASS |
| Everest | **39,40 s** | 30 s | **FAIL** |
| Crimson Lake | **111,10 s** | 10–120 s | PASS |

Everest : **36,9 s (94 %) de préchargement**, contre ~2,4 s pour le reste du
pipeline interne. Les deux préchargements mesurés : 16 chunks en 3,994 s,
puis 432 chunks en 31,426 s. Aucun chunk manquant signalé par `fixLiquids`.
C’est le chargement/génération du terrain qui reste à optimiser, pas les
52 979 blocs du modèle. Les chiffres ne garantissent pas les mêmes délais
sur tout matériel, toute graine, toute rotation ou un serveur déjà chargé.

## Crimson Lake

- Annonce ramenée à **10 s**, garde minimale également basée sur une horloge monotone.
- Ancrage : premier bloc réel du modèle à `sol naturel + 1 - 10`, sans compter
  les marges vides du NBT. Dernier cas : sol Y=47, premier bloc Y=38.
- Avant la pose : dégagement dans l’emprise jusqu’au ciel, même si une montagne
  dépasse le toit. **1 419 854 blocs** retirés sur le dernier essai.
- Sol sous le niveau de dégagement, colonnes extérieures et bedrock préservés ;
  aucun fichier de chunk ni métadonnées de chunk supprimés.
- Pose des blocs non-air par chunks ; toutes les finitions de construction comptées.
  L’animation ultérieure des papillons n’est pas la construction et n’est pas
  incluse dans le marqueur de fin.
- Pas de contrôle visuel client ni de test de cheminement du joueur autour
  de chaque entrée du vrai modèle. Le dégagement intérieur est testé, mais
  l’accessibilité visuelle de tous les sites n’est pas certifiée.

## Tests fonctionnels — tous PASS

- Huit chunks vierges demandés sans attente inline : envoi **4,11 ms**, rappels
  exécutés sur le thread serveur et chunks disponibles.
- Bassin : **45 120 sources**, frontières de chunks, colonne partielle, emprise sèche.
- Coffres : huit variantes rotation/miroir, seize inventaires matérialisés,
  contenus et tables préexistants conservés.
- Terrain : emprise inchangée et bande de six blocs non excavée sur fixture.
- Lac : décalage de dix blocs avec padding, montagne au-dessus du toit dégagée,
  côtés et sol inférieur préservés.

Ces fixtures ne prouvent pas une restauration universelle des océans façon
Axiom/WorldEdit ni l’absence d’excavation dans toute la couronne extérieure.

## Cinq cycles supplémentaires autorisés

| Essai | Sources | Run | Résultat |
|---|---|---|---|
| 1 | a8736c7 | 36203760412 | Fixture async : chunk non disponible au rappel |
| 2 | 9b68ac2 | 36203967349 | Fixtures PASS ; Everest 42,80 s ; lac 91,00 s |
| 3 | 7e3cf06 | 36204448994 | Fixture bassin : course avec chunks non maintenus |
| 4 | 36c734a | 36204702222 | Fixtures PASS ; Everest 33,70 s ; lac 133,10 s |
| 5 | 511a507 | 36205331210 | Fixtures PASS ; Everest 39,40 s ; lac 111,10 s |

Les essais 2/4/5 ont terminé toutes les structures. Les variations de durée
montrent qu’un seul passage sous un seuil ne constitue pas une garantie.
L’essai initial avant cette autorisation (`b12f48d`, run `36202755287`) donnait
notamment Everest 66,10 s et lac 129,20 s. Les rapports historiques restent dans Git.

## Archive préparée, statut partiel

`livraison/optimisations-src-511a507-validation-partielle.zip` — **203572 octets**.
SHA-256 : `defcf2a8a523ce25cd9efad87194b73faa8ec8ab2253b82498db389dcc3c9082`.
Sept sources modifiées uniquement, avec leur hiérarchie `src/...` d’origine.
Archive relue et comparée octet par octet aux fichiers testés. Aucun harnais CI,
rapport, cache, dépendance ou projet complet inclus. Sauvegarder les sources,
extraire à la racine contenant `src`, puis recompiler ; ce n’est pas un JAR.
**Ce n’est pas une livraison certifiée « tout ≤30 s ».**

PR : https://github.com/deepwalls/deep-lucky-block/pull/1, maintenue en brouillon.
Recherche et décisions : `RECHERCHE_OPTIMISATION_30S.md`.
