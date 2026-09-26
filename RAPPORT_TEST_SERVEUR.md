# Génération des structures — dernier résultat

## Statut au 26 septembre 2026

**Validation partielle : six objectifs de durée sur sept respectés. Everest reste au-dessus de 30 s.**

Sources testées : `79d3edd`. `./gradlew build` Java 21 réussi. Tous les tests
fonctionnels passent ; les sept structures terminent. Le run
[36209068656](https://github.com/deepwalls/deep-lucky-block/actions/runs/36209068656)
(check `108311667567`) échoue uniquement sur le plafond Everest.

Les **cinq nouveaux cycles** accordés après le rapport `e256a7b` sont consommés.
Aucun sixième lancement de cette série n’a été effectué. Une nouvelle autorisation
est nécessaire avant de poursuivre les corrections/relances.

## Temps réellement mesurés

Temps mural depuis l’envoi de la commande jusqu’aux marqueurs de fin des travaux,
**décoration et post-traitement compris**, et non seulement le paste.
Minecraft **1.21.1**, NeoForge **21.1.77**, Java 21, **2 CPU / 2 Go**, graine
`123456789`, distances de vue/simulation 4. Monde de test neuf, fixtures puis
sept générations successives dans des zones distinctes. Ce n’est pas un monde
neuf par structure : les chunks générés par les étapes précédentes peuvent subsister.

| Structure | Dernière durée | Objectif | Résultat |
|---|---:|---:|---|
| Citadelle | **26,30 s** | ≤30 s | PASS |
| Observatoire | **22,00 s** | ≤30 s | PASS |
| Dragon | **27,90 s** | ≤30 s | PASS |
| Cirque | **7,90 s** | ≤30 s | PASS |
| Bateau | **9,70 s** | ≤30 s | PASS |
| Everest | **35,39 s** | ≤30 s | **FAIL** |
| Crimson Lake | **101,80 s** | 10–120 s | PASS |

Ces chiffres ne garantissent pas les mêmes délais sur tout matériel, toute graine,
toute rotation ou un serveur déjà chargé. Le classement des variantes par vitesse
n’est pas établi par un seul passage : la variabilité reste importante.

## Everest : blocage restant et diagnostic CPU

- Préchargement exact : **368/368 chunks en 31,449 s**, déjà plus que le plafond total.
- Réparation d’eau : **863 ms**, aucun chunk voisin supplémentaire demandé, aucun
  chunk non chargeable et aucun remplissage nécessaire sur ce site.
- **52 979 blocs** posés ; marqueur de pose à 33,940 s, puis post-traitement.
- Mesure CI autour de l’opération : **35,397 s murales**, **33,003 s CPU cumulées
  des threads « worker »**, **4,450 s CPU du thread serveur**, 0,026 s des autres
  threads Java observés. Collecteurs JVM : **315 ms** cumulées rapportées.
  Tas utilisé aux deux instantanés : 1 439 → 844 Mio.

Ces mesures orientent vers le travail en arrière-plan du moteur pendant le
préchargement, notamment génération/éclairage, plutôt que vers un simple quota
insuffisant de pose ou de longues collectes mémoire. Elles ne constituent pas un
profil par méthode ; les temps CPU peuvent se chevaucher et ne s’additionnent pas
pour donner le temps mural. Les instantanés ne modifient ni le nombre de threads,
ni les limites de temps, ni les chunks à traiter.

## Changements de cette série

1. Terrain Everest préchargé intégralement ; partie intacte du halo liquide explorée
   à la demande. Les chunks atteints par la propagation sont chargés **et épinglés**
   avant la suite. La limite de réparation de 48 blocs et les garde-fous restent.
2. Les bandes intactes dues à l’arrondi aux chunks ne servent plus artificiellement
   d’amorces. La propagation peut toujours dépasser la boîte de découverte des sources.
3. Emprise horizontale calculée avant l’altitude ; ancrage lu après chargement du
   site final. Le chunk d’ancrage est également retenu si un offset le place à part.
4. File de requêtes sans curseur invalidé par les retraits ; groupes compacts de
   4×4 chunks, ordonnés depuis le centre dans la dernière variante. Même couverture,
   mêmes limites de 8 demandes/tick, 16 en vol et 8 épinglages/tick.
5. Renouvellement des tickets en place, sans retrait/réajout périodique inutile.

Aucun bloc supplémentaire du modèle n’a été supprimé pour gagner du temps.
L’analyse du NBT a notamment trouvé deux blocs de neige isolés aux bornes : ils
sont conservés. Le gain de l’ordre centré pour Everest **n’est pas démontré** par
ce dernier essai, plus lent que le précédent ; les mesures ne sont pas présentées
comme une progression monotone.

## Crimson Lake

- Annonce de **10 s**, garde minimale également basée sur une horloge monotone.
- Premier bloc réel à `sol naturel + 1 - 10`, padding vertical du NBT compensé.
  Dernier cas : sol Y=47, premier bloc Y=38.
- Avant la pose : dégagement de l’emprise jusqu’au ciel, même si une montagne
  dépasse le toit. **1 419 848 blocs** retirés sur le dernier essai.
- Sol sous le dégagement, colonnes extérieures et bedrock préservés ; aucun
  fichier de chunk ni métadonnées de chunk supprimés.
- Pose des blocs non-air par chunks ; finitions de construction comptées.
  L’animation ultérieure des papillons n’est pas incluse dans le marqueur de construction.

## Tests fonctionnels — tous PASS

- Huit demandes de chunks vierges : envoi **7,39 ms**, sans attente inline,
  rappels sur le thread serveur, chunks disponibles.
- Bassin de **45 120 sources**, testé avec préchargement complet **et** exploration
  à la demande : frontières de chunks, colonne partielle et emprise sèche vérifiées.
- Frontière liquide froide réellement chargée/épinglée ; canal rempli au-delà de
  la boîte de découverte des sources ; frontière intacte du padding non chargée.
- Même objet ticket renouvelé, chunk maintenu après **360 ticks** — au-delà de sa
  durée initiale de 300 ticks — et ticket absent après libération.
- Coffres : huit variantes rotation/miroir, seize inventaires matérialisés,
  contenus et tables préexistants conservés.
- Terrain : emprise inchangée et bande de six blocs non excavée sur fixture.
- Lac : enfoncement de dix blocs avec padding, montagne dépassant le toit dégagée,
  côtés et sol inférieur préservés.
- Vérification locale de l’ordonnancement sur **10 000 rectangles** à coordonnées
  positives/négatives : couverture identique, aucun doublon ni omission.

**Limites :** pas de contrôle visuel client ni de test de cheminement du joueur
à toutes les entrées du vrai modèle. Les fixtures ne prouvent pas une restauration
universelle des océans façon Axiom/WorldEdit ni l’absence d’excavation dans toute
la couronne extérieure.

## Deuxième autorisation — cinq nouveaux cycles consommés

| Cycle | Sources | Run | Everest | Crimson Lake | Fonctionnel |
|---|---|---|---:|---:|---|
| 1 | `69a2f59` | 36206658722 | 40,00 s | 109,60 s | PASS |
| 2 | `d76b341` | 36207219264 | 32,70 s | 105,30 s | PASS |
| 3 | `e852e3c` | 36207932683 | 35,90 s | 104,60 s | PASS |
| 4 | `c9e3536` | 36208627496 | 32,40 s | 103,50 s | PASS |
| 5 | `79d3edd` | 36209068656 | 35,39 s | 101,80 s | PASS |

Toutes ces sessions ont terminé les sept structures ; seul Everest dépasse dans
chacune. Le changement d’ancrage `c8fe81c` a aussi été compilé séparément avec
succès (run `36207761445`) avant le test serveur du cycle 3.

## Première autorisation — historique conservé

| Cycle | Sources | Run | Résultat |
|---|---|---|---|
| 1 | a8736c7 | 36203760412 | Fixture async : chunk non disponible au rappel |
| 2 | 9b68ac2 | 36203967349 | Fixtures PASS ; Everest 42,80 s ; lac 91,00 s |
| 3 | 7e3cf06 | 36204448994 | Fixture bassin : course avec chunks non maintenus |
| 4 | 36c734a | 36204702222 | Fixtures PASS ; Everest 33,70 s ; lac 133,10 s |
| 5 | 511a507 | 36205331210 | Fixtures PASS ; Everest 39,40 s ; lac 111,10 s |

L’essai initial `b12f48d` (run `36202755287`) donnait Everest 66,10 s et lac 129,20 s.
Les rapports historiques restent dans Git.

## Archive livrable — validation partielle

`livraison/optimisations-src-79d3edd-validation-partielle.zip` — **202 939 octets**.
SHA-256 : `e183d91a228115237dda7e542a60115f9f5d409414fc84a416d85e9021b1b7ac`.

**Sept sources modifiées uniquement**, hiérarchie `src/...` d’origine, comparées
aux sources Git testées puis relues octet par octet dans le ZIP. Aucun harnais CI,
rapport, cache, dépendance ou projet complet inclus. Sauvegarder les sources,
extraire à la racine contenant `src`, puis recompiler : ce n’est pas un JAR.
Les anciennes archives sont dépassées.

**Ce n’est pas une livraison certifiée « tout ≤30 s ».**
PR : https://github.com/deepwalls/deep-lucky-block/pull/1, maintenue en brouillon.
Recherche et décisions : `RECHERCHE_OPTIMISATION_30S.md`.
