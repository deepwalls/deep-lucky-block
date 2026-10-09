# Correctif T228 — Structures 5 : file d'attente fantôme, lac « brûlé », achievements volés

> **T229 (06/10, après revue des logs GitHub `main`)** — le `Structures5Procedure.java` de
> ce dossier comporte en plus trois correctifs ciblés issus du run dragon du 06/10
> (monde neuf « New World », joueur à (37,96,128)) :
> 1. **Blocage silencieux du secours ciblé** — `cg/cf == null` renvoyait `null` à chaque
>    tick sans re-demande, sans compteur, sans saut, sans log : le dragon n'apparaissait
>    JAMAIS et plus aucune ligne ne sortait (3 min de silence dans ton log). → re-demande
>    toutes les 20 ticks, WARN, puis saut/abandon honnête après 100 ticks (même file T228).
> 2. **Terrain non visité = téléport ~1 km garanti** — T185 consommait tout candidat non
>    FULL à vue ; dans un monde neuf rien n'est FULL au-delà du spawn → tout sautait en
>    ~2,5 s (cf. `zones historiques épuisées ... emprise à 37,1045`). → les meilleurs
>    candidats sont demandés en tâche de fond et différés (max 96, max 600 reprises) :
>    la structure se pose à distance normale comme avant T185, sans réouvrir la dérive
>    97 180 / 294 595 recherches.
> 3. **Silences de diagnostic** — les attentes d'emprise (x0,6 / entière) et de chargement
>    du footprint passent en `LOGGER.warn` périodique borné au lieu de `DebugLog`
>    (désactivé dans ce build → invisible).

Fichiers (complets — copier-coller direct MCreator, ou extraire sur le workspace) :
- `Structures5Procedure.java` — avant : `avant/Structures5Procedure.java`
- `Structures4Procedure.java` — avant : `avant/Structures4Procedure.java`
- `CrimsonLakeStructureSpawnProcedure.java` — avant : `avant/CrimsonLakeStructureSpawnProcedure.java`

Base : contenu du `mod.zip` du 06/10 (00:35). Versions « avant » extraites du zip d'origine.

> Statut : **compilation Java 21 VALIDÉE EN CI** (run 37409807032, branche
> `arena/7cdbe3b3-deep-lucky-block`, commit 875cd22). La livraison précédente
> ne compilait plus contre le `SafeSurface` synchronisé en CI : 6 erreurs
> `cannot find symbol` (T161/T168/T173 appelaient `columnWet`, `surfaceScanEx`,
> `groundStats(..., excludeWater)`, `maxWaterLevelOutside`).
> Le comportement en jeu reste à valider visuellement.

## Ce qui « merdait » dans Structures 5

### 1. Boucle fantôme après abandon de la recherche (le plus grave)

`giveUpFallback()` appelait `notifyGenerationAborted()` (file libérée,
réservation rendue) **puis** renvoyait `null` — et `doPaste` replanifiait
malgré tout un essai au tick suivant. La génération déjà avortée repartait,
recréait le même secours, ré-échouait au bout de 8 sauts (~40 s) et
ré-abortait… la structure **suivante** (elle libérait le
`CURRENT_STRUCTURE_ID` d'une autre génération en cours). En boucle, sans fin.

Symptômes possibles vus en jeu : structures qui ne spawnent jamais alors que
le tirage a eu lieu, file d'attente qui se réinitialise toute seule, structure
suivante systématiquement « annulée ».

Correctif : `giveUpFallback` n'avorte plus lui-même ; il pose le drapeau
`SEARCH_ABORTED`, et `doPaste` renvoie `false` sans replanifier — l'abandon a
lieu exactement UNE fois, par l'appelant (`generate()` ou le wrapper planifié).

### 2. Crimson Lake « brûlé » à jamais après un abandon

Les deux chemins d'ABANDON du lac (aucune emprise libre / template hors
hauteur de build) appelaient `notifyGenerationFinished` — qui, depuis T226,
**grave la structure comme déjà apparue sur ce monde**. Un lac avorté (aucun
bloc posé) devenait donc intirable pour toujours. Ils appellent désormais
`notifyGenerationAborted` : le lac redevient tirable.

### 3. L'achievement d'une structure volé par la suivante

`execute()` écrivait l'origine/joueur de l'achievement dans des globaux **au
moment du tirage**, même quand la structure partait en file d'attente. Une
seconde demande écrasait le contexte de la première : son achievement partait
avec la position de la seconde (ou pas du tout si un abandon l'effaçait), et
la seconde n'en recevait jamais. Le contexte (joueur + origine + drapeau
`/dlbtest`) voyage désormais **dans** `PendingStructure` et n'est installé
qu'au démarrage réel de la génération (`execute` immédiat ou sortie de file
dans `startNextPending`).

### 4. Abandons silencieux de l'Everest (file gelée 4 minutes)

Deux chemins d'abandon asynchrones de `spawnEverest` (« pas assez de chunks
chargés », « aucun bloc extrait ») quittaient sans prévenir personne :
`GENERATION_BUSY` restait vrai jusqu'au contournement de 240 s, et la
réservation Everest n'était jamais libérée. Ils appellent maintenant
`notifyGenerationAborted`. De même, `beginGeneration` avorte explicitement si
`spawnCircus/Shipdead/Everest` renvoient `false` au premier tick.

## Hors périmètre (inchangé volontairement)

- La logique de recherche T159–T190 (zones sèches, exhaustive x0,6, secours
  ciblé), le paste rapide T171/T175, et la pose des autres structures :
  aucun changement de comportement nominal.
- Les copies figées sous `src/main/resources/deepluckyblock_patch/FIXES_MCREATOR/`
  (jamais compilées, simple stockage).
- `run/eula.txt` absent du zip : le contrôle serveur complet `[server-check]`
  n'a pas été lancé ici (compilation seule + revue statique).

## Vérifications

1. Run CI 37409807032 : `./gradlew build` Java 21 **OK** (warnings NeoForge
   habituels, 0 erreur).
2. AST tree-sitter : 0 erreur de syntaxe sur les deux fichiers principaux.
3. Revue statique des appels croisés : toutes les méthodes référencées
   (`SafeSurface.*`, `ChunkKeeper.*`, `TestProcedure.schedule/currentTick`,
   `GenerateLuckyItemProcedure.commitStructureSpawn/releaseStructureReservation`)
   existent avec les bonnes signatures dans le zip.
