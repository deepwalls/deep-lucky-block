# Optimisation — objectifs 30 s / Crimson Lake 10–120 s

## Contrat de mesure

Temps mural depuis l’envoi de la commande, et non seulement la boucle de pose.
Pour citadelle/observatoire/dragon : décoration **et** post-traitement terminés.
Pour cirque/bateau : pose, nettoyage et post-traitement terminés.
Pour Everest : pose **et** post-traitement terminés.
Pour Crimson Lake : annonce, préparation, dégagement, pose et finitions compris ;
les apparitions de papillons ultérieures restent l’animation de l’événement.

Objectifs : ≤30 s pour les six premières, 10–120 s pour Crimson Lake. Le harnais
signale un échec pour chaque dépassement, mais laisse finir les travaux pour
mesurer leur durée réelle. Aucun bloc ou passage n’est supprimé pour masquer un
dépassement. Ce sont des objectifs à vérifier sur un environnement donné, pas
une garantie temps réel indépendante du matériel ou de la charge du serveur.

## Recherche en ligne, version concernée

Sept recherches effectuées : futures/tickets de chunks, flags de blocs,
traitement par sections FAWE, placement NeoForge 1.21.1, LevelChunk/sections,
features vanilla et profilage serveur. Pas de dépendance ajoutée.

- NeoForge **1.21–1.21.1** documente `Level#setBlock` et les flags :
  `UPDATE_CLIENTS`, `UPDATE_KNOWN_SHAPE`, `UPDATE_SUPPRESS_DROPS`, et le rôle
  spécifique aux pistons de `UPDATE_MOVE_BY_PISTON`.
  [1](https://docs.neoforged.net/docs/1.21.1/blocks/states/)
  Choix : garder l’API de blocs pour les métadonnées/clients ; utiliser 2|16|32
  pour le lac et Everest au lieu du flag de déplacement par piston.
- FAWE décrit le placement par sections/chunks et la séparation entre calculs
  asynchrones et tâches sur le thread principal.
  [1](https://www.spigotmc.org/resources/fastasyncworldedit.13932/)
  Choix : retenir la localité des accès, **pas** copier ses écritures NMS Bukkit
  dans NeoForge. Pas de mutation des sections depuis un thread de travail.
- La documentation NeoForge 1.21.1 décrit les features configurées/placées dans
  les registres de données.
  [3](https://docs.neoforged.net/docs/1.21.1/concepts/registries/)
  Le replantage vanilla déjà introduit est conservé ; aucune reconstruction
  artisanale des arbres pour gagner artificiellement du temps.

Les résultats de recherche visant des versions Forge anciennes, Fabric ou Paper
ne sont pas utilisés comme preuve de compatibilité NeoForge 1.21.1. Les signatures
réellement utilisées sont vérifiées par compilation Java 21 sur le projet.

## Changements ciblés

1. **Recherche d’emplacement en lecture seule** : pas de génération de toutes
   les zones candidates. Les vérifications de chunks utilisent `getChunkNow`.
   Une colonne inconnue n’est pas interprétée comme un terrain plat valide.
2. **Nettoyage cirque/bateau** : inversion du parcours. Chaque cellule de la
   boîte est lue une fois ; seules les cellules végétales vérifient leur
   proximité avec la structure. Même rayon et même exclusion des blocs du
   modèle. Suppression du quota de 64 blocs/tick qui imposait ~30 s au seul
   nettoyage d’un cirque de 39 088 blocs. Tranches de 12 ms.
3. **Everest** : calcul de l’emprise réellement transformée avant la lecture
   d’altitude, chargement de cette emprise et de son ancrage, puis lecture du sol.
   La petite sonde indépendante n’est conservée que pour la recherche distante
   configurée, pas pour le placement direct par défaut.
4. **Chargement borné** : budget partagé par tick, défaut 8 demandes/tick et
   16 demandes en vol ; propriétés de diagnostic bornées. Les appels multiples
   de `keep` dans un tick ne multiplient plus le budget.
5. **Terrain** : tranches adaptatives à partir de 12 ms, plafond 24 ms au lieu
   de 16 ms, réduction conservée quand le serveur est déjà en retard.
6. **Crimson Lake** : préchauffage recentré sur l’emprise effective ; annonce
   de 10 s (garde minimale monotone également) ; premier bloc réel à
   `sol naturel + 1 - 10`, indépendamment du padding vertical du NBT.
   Après le terrain, dégagement de l’emprise jusqu’à la surface la plus haute,
   même au-dessus du toit du modèle ; sol inférieur, colonnes extérieures et
   bedrock préservés. Puis pose des blocs non-air par chunks. Le dégagement
   utilise les chunks existants : aucun fichier de chunk ni ses métadonnées
   n’est supprimé. Un chunk absent est réessayé, pas ignoré.
7. **Ancrage brut** : relire le sol après préchargement évite de conserver une
   hauteur de repli utilisée lors de la sélection sur un chunk vierge.

## Vérifications

- Comparaison déterministe de l’ancien et du nouveau voisinage de nettoyage :
  100 jeux de cellules, ensembles de suppression identiques.
- Nouvelle fixture Java : décalage vertical avec padding NBT ; montagne
  dépassant le toit ; volume intérieur vidé ; côtés et sol inférieur préservés.
- Fixtures antérieures conservées : eau inter-chunks, coffre existant/butin,
  rotations/miroirs, absence d’excavation sur la bande protégée de six blocs.
- La session complète sur `b12f48d` est la première mesure de cette série ; consulter
  `RAPPORT_TEST_SERVEUR.md` pour le dernier résultat, sans assimiler compilation et performance.

## Diagnostic issu de la première mesure

Run `36202755287`, sources `b12f48d` : toutes les structures terminent mais trois
objectifs échouent (dragon 32,70 s, Everest 66,10 s, lac 129,20 s). Les autres :
citadelle 29,80 s, observatoire 19,90 s, cirque 6,40 s, bateau 11,50 s.
Les fixtures passent, y compris dégagement du lac. Le vrai lac a dégagé
1 419 961 blocs avant la pose. La réparation d’eau de l’Everest signale encore
43 chunks absents après la phase de scan : validation visuelle incomplète.

Recherche ciblée supplémentaire : une pile d’appels **Minecraft 1.21.1 / NeoForge**
montre `getChunkFuture → managedBlock` depuis un tick serveur.
[2](https://github.com/Yucareux/Tellus/issues/43)
Le correctif `982ad28` appelle donc cette API via sa branche hors-thread, sans
`join`, puis revient sur le serveur pour lire le résultat et exécuter les rappels.
Aucune mutation du monde depuis le dispatcher. Le préchargement devient réellement
concurrent plutôt que de sérialiser des attentes cachées. Une fixture contrôle
que huit demandes sur chunks vierges ne rappellent pas inline et que leurs
callbacks s’exécutent sur le thread serveur.

`ca90834` demande également les rives nécessaires à l’Everest avant le terrain,
avec attente de couverture réelle, plutôt qu’au début de `fixLiquids`.
Compilations réussies : `36203338979` et `36203479414`.
Ces deux correctifs restent à mesurer en jeu ; une nouvelle session est demandée
explicitement pour respecter la limite initiale sur les relances serveur.

## Résultat au terme de la première autorisation de cinq essais

Voir `RAPPORT_TEST_SERVEUR.md` : six plafonds respectés, Everest encore à 39,40 s.
Les correctifs ont été complétés par des tickets temporaires distincts et un
rappel au tick suivant, une attente de tous les chunks épinglés avant réparation
d’eau, l’attente de couverture complète avant le terrain, et la réutilisation
du découpage au temps existant pour trois passes auparavant bloquées à 600
colonnes/tick. L’Everest conserve une marge de réparation d’eau de 48 blocs ;
son nettoyage local ne reprend plus l’anneau de 96 blocs des grands plateaux.

Ces cinq premiers cycles ont été consommés. L’utilisateur a ensuite explicitement
accordé cinq nouveaux cycles, détaillés ci-dessous. Les paragraphes précédents
« à mesurer » sont l’historique, pas le statut actuel.


## Deuxième autorisation : cinq cycles réalisés, résultat encore partiel

Dernières sources `79d3edd`, run `36209068656` : compilation et fixtures PASS,
Everest **35,39 s**, Crimson Lake **101,80 s**, les cinq autres sous 30 s.
Aucun sixième cycle de cette autorisation n’a été lancé.

### Choix conservant le travail nécessaire

- Tous les chunks du terrain Everest travaillé sont préchargés/épinglés. Le halo
  liquide intact est exploré à la demande, sans réduire la limite de réparation
  de 48 blocs. La propagation attend les nouveaux chunks plutôt que d’épuiser
  six tentatives et d’abandonner des points.
- Les sources de départ sont recherchées dans la boîte du terrain et de sa
  bordure ; les bandes supplémentaires dues à l’arrondi aux chunks ne créent
  plus de fronts artificiels. Une fixture vérifie expressément que le remplissage
  peut dépasser cette boîte, et qu’un vrai voisin froid est chargé/épinglé.
- La liste mutable des demandes ne fait plus dériver son curseur lors des retraits.
  Les requêtes suivent des groupes 4×4 compacts ; la dernière variante les classe
  depuis le centre. La couverture est comparée sur 10 000 rectangles. Concurrence
  et budget de dépôt inchangés. Le gain pour Everest de la variante centrée n’est
  pas démontré par ce seul essai : 35,39 s contre 32,40 s au précédent.
- L’altitude est lue sur l’ancrage disponible après le chargement final, et non
  sur une hauteur de repli à conserver pendant toute la construction.
- Les tickets sont renouvelés en place. Le retrait préalable provoquait des
  mises à jour inutiles des graphes de distance. La fixture vérifie l’identité du
  ticket, son âge renouvelé, le maintien après 360 ticks et sa disparition à la libération.

### Vérification de l’API de tickets

Consultation des mappings **1.21.1** :
[Ticket](https://mappings.dev/1.21.1/net/minecraft/server/level/Ticket.html),
[ServerChunkCache](https://mappings.dev/1.21.1/net/minecraft/server/level/ServerChunkCache.html).
Lecture du patch NeoForge ciblé :
[DistanceManager, branche 1.21.1](https://github.com/neoforged/NeoForge/blob/1.21.1/patches/net/minecraft/server/level/DistanceManager.java.patch).
Le comportement de renouvellement sans retrait est surtout confirmé par la
fixture exécutée sur **NeoForge 21.1.77**, et non supposé à partir d’une version ancienne.
Aucune dépendance ajoutée, aucune modification de `gradle.properties` ou `settings.gradle`.

### Mesure du coût restant

Everest : **368 chunks en 31,449 s** ; eau **863 ms**, aucun chargement extérieur
supplémentaire sur ce site ; **52 979 blocs** toujours posés.
L’inspection du NBT a identifié deux blocs de neige isolés aux bornes : ils n’ont
pas été supprimés pour réduire artificiellement l’emprise.

Instantanés CPU CI : workers **33,003 s**, serveur **4,450 s**, autres threads Java
**0,026 s**, pour **35,397 s murales** ; collecteurs JVM **315 ms** rapportées.
Ces durées se chevauchent. Les snapshots ne changent ni les threads du moteur ni
la mémoire ni les seuils du benchmark. Ils ne profilent pas les méthodes et ne
prouvent pas que chaque milliseconde worker relève de la génération ; ils orientent
vers le travail moteur de génération/éclairage plutôt que vers de longs GC ou
un simple quota de pose. Il reste à réduire ce coût sans omettre du terrain utile.

Les deux bassins de 45 120 sources, la frontière froide, la propagation au-delà
des amorces, les tickets, les coffres et le dégagement du lac passent. Cela ne
remplace pas une inspection visuelle des océans et des entrées en jeu.
