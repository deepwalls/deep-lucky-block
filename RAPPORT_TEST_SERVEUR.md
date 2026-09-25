# Vérification serveur — correctifs terrain et décors

## Version et protocole

- Commit testé : `0fefe6d6c0c8c743625dfbaed49901ce4bf454c5`.
- [Run GitHub Actions réussi](https://github.com/deepwalls/deep-lucky-block/actions/runs/36199003753).
- Java 21 Temurin ; build complet `./gradlew build`, puis serveur dédié NeoForge 1.21.1 via `runServer`.
- Une seule session serveur, trois commandes successives : citadel, observatory, dragon.
- Monde de test distinct, graine `123456789`, distances de vue et simulation 4, JVM limitée à 2 processeurs actifs et 2 Go par processus.
- Le JAR 2.3 fourni par l’utilisateur n’a pas remplacé les classes recompilées.

## Temps de bout en bout

Durées murales mesurées depuis l’envoi de la commande RCON jusqu’au marqueur `decorate TERMINE`, après la porte de stabilisation :

| Structure | Durée totale | Décors posés | Eau remplie par fixLiquids |
|---|---:|---:|---:|
| Citadelle | 51,10 s | 39/39 | 40 000 blocs, plafond atteint |
| Observatoire | 23,90 s | 19/19 | 7 936 blocs |
| Dragon | 31,20 s | 39/39 | 7 311 blocs |

Les durées internes « TACHE COMPLETE » (28,2 / 21,6 / 21,7 s) excluent une partie du début de la commande. Ce ne sont PAS les temps de bout en bout ci-dessus. Le chargement des chunks est le premier poste dans les trois résumés internes. Aucun benchmark avant/après à graine identique n’a été exécuté : aucun gain relatif n’est revendiqué.

## Correctifs livrés

- Typage de la feature d’arbre corrigé, sans cast générique illégal.
- Scan du fixwater sur la véritable surface occupée, et non sur l’air au-dessus.
- Protection de l’eau réelle dans le masque de lissage, même sans ancien gel WaterDam.
- Moyennes arrondies au lieu de divisions entières répétées vers le bas ; arrêt quand la heightmap converge.
- Hauteurs mesurées conservées pour reconstruire les crevasses réellement ; emprise et eau préservées dans la cible finale.
- Colonnes inchangées exclues des lots de reconstruction ; les colonnes absentes sont ignorées par cette reconstruction, sans génération bloquante.
- Multiplicateur ×1,2–2,9 pour la cible des décors ; rotations et miroirs avec origine corrigée et emprises vérifiées.
- Tables village modestes pour coffres vides sans table existante ; inventaires et tables existants conservés.

## Limites — ne pas considérer tous les problèmes comme résolus

- Vérification sans client graphique : aucun rendu visuel des rivages ou des creux inspecté.
- Le fixwater de la citadelle a atteint son plafond de 40 000 blocs : la restauration peut rester incomplète. Le plafond n’a pas été relevé à l’aveugle, pour éviter d’inonder une vallée.
- Les protections de berges existantes restent actives. L’équivalence complète avec WorldEdit/Axiom et la continuité naturelle de tous les océans ne sont pas démontrées.
- Aucun coffre vide admissible traité dans les trois tirages (compteur 0) : le chemin de butin compile, mais son résultat à l’ouverture n’a pas été validé dans cette session.
- Pas de garantie d’un délai inférieur à 60 s pour d’autres machines, graines, dimensions ou les autres structures (Everest, Crimson Lake, etc.).
- Pas de preuve visuelle que tous les creux de 2–3 blocs ont disparu ; le biais numérique identifié est corrigé, pas tous les mécanismes de terrassement.

## Archive de sources

`livraison/correctifs-src-testes-serveur.zip` contient seulement les deux fichiers modifiés sous `src/main/java/deepluckyblock/procedures/`. Décompresser à la racine du projet qui contient déjà `src`, PAS à l’intérieur de `src`. Sauvegarder les anciens fichiers, remplacer puis recompiler. Il ne s’agit pas d’un JAR installable.

SHA-256 : `6e75710fa195d93a8e6ec66a75de5a60a4d302acab1973a5f23546618b8c0505`

## Extraits serveur disponibles via les annotations GitHub

```text
citadel: 51.10s from command to final decoration marker
observatory: 23.90s from command to final decoration marker
dragon: 31.20s from command to final decoration marker

dragon: 31.20s from command to final decoration marker
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 3/5 : double smooth termine
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 3/5 : sealWaterLeaks debut
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 3/5 : sealWaterLeaks termine
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 4/5 : smooth cible eau termine
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids : rayon demande 1000 blocs (15876 chunks candidats) autour de 1277,27 -- T75 : les chunks ABSENTS de la memoire sont demandes et reessayes (c'est dans ceux-la que restaient les murs d'eau figes aux frontieres du terrain)
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids : anneau proche (emprise + 48 blocs = chunks 74..85 / -3..6) charge a la demande ; au-dela, seuls les chunks deja en memoire sont traites (T75)
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] [17.0 s] fixLiquids : passe /fixwater + /fixlava planifiee (rayon 1000)
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids 1/2 : 182 chunks charges (0 voisins charges a la demande, 15694 jamais chargeables) -- 0 bloc(s) flowing -> SOURCE, 16287 colonne(s) d'eau et 0 colonne(s) de lave memorisees (17 ms)
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids 2/2 : propagation du niveau d'eau/lave sur 16287 colonnes de depart
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids TERMINE (0 chunks voisins demandes, 0 non chargeables) : 0 bloc(s) flowing -> source, 840 colonne(s) / 7311 bloc(s) d'eau remis a niveau, 0 colonne(s) / 0 bloc(s) de lave, 32 ms
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] [17.0 s] decorate TERRAIN TERMINE : terrain fige, la structure peut etre posee (T39)
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorateFinish (POST-POSE, apres TOUS les fixwater) : decors + arbres + naturalisation (aucun smooth) -- T75
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] scatter [DRAGON]: base=15, multiplier=2.571432035954018, target=39, village loot chests=0
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] scatter [DRAGON] : 21 naturels (cible 8) sur 39 decors poses
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] scatter [DRAGON] : 39/39 schematics vanilla poses AVANT les arbres (164 rejetes), couronne 46 blocs
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] scatter [DRAGON] : T42 -- 77 colonne(s) de decors plaquees au sol (aucune mini-structure ne flotte)
[23:00:32] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 5/5 : decors (scatter) poses
[23:00:32] [Server thread/INFO] [de.pr.Structures5Procedure/]: [STRUCT5] decorate + post de dragon termine
[23:00:37] [Server thread/INFO] [de.ut.TerrainEditClamp/]: [DLB-PERF] TACHE COMPLETE en 21.7 s -- ou est passe le temps : pre-chargement des chunks 7.9 s (37%) | rebuildTerrain 5.7 s (27%) | naturalize (herbes, fleurs, champignons) 124 3.9 s (18%) | clearSurfaceDecor 1.2 s (5%) | verifyGrassSurface 0.6 s (3%) | prepZone 7b/11 0.4 s (2%) | guardWaterEdges (berges anti-fuite) 0.3 s (2%) | balayage etendu des naturels flottants (lian 0.3 s (2%) | attente/tick system 1.3 sacces terrain : 451 requete(s) moteur, 1804223 lecture(s) servies par le cache (100%), 0 colonne(s) hors memoire
[23:00:37] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] [21.7 s] decorate TERMINE (decors, arbres, naturalisation -- structure posee en DERNIER, T39)

dragon: command submitted; response: [DLB-TEST] Structure DRAGON demandee a 1200, 68, 0 -- la construction demarre apres le choix de zone (regarde la console)


observatory: 23.90s from command to final decoration marker
[22:59:59] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 3/5 : double smooth termine
[22:59:59] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 3/5 : sealWaterLeaks debut
[22:59:59] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 3/5 : sealWaterLeaks termine
[22:59:59] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 4/5 : smooth cible eau termine
[23:00:00] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids : rayon demande 1000 blocs (15876 chunks candidats) autour de 657,30 -- T75 : les chunks ABSENTS de la memoire sont demandes et reessayes (c'est dans ceux-la que restaient les murs d'eau figes aux frontieres du terrain)
[23:00:00] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids : anneau proche (emprise + 48 blocs = chunks 36..45 / -3..6) charge a la demande ; au-dela, seuls les chunks deja en memoire sont traites (T75)
[23:00:00] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] [15.8 s] fixLiquids : passe /fixwater + /fixlava planifiee (rayon 1000)
[23:00:00] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids 1/2 : 193 chunks charges (0 voisins charges a la demande, 15683 jamais chargeables) -- 0 bloc(s) flowing -> SOURCE, 5974 colonne(s) d'eau et 0 colonne(s) de lave memorisees (436 ms)
[23:00:00] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids 2/2 : propagation du niveau d'eau/lave sur 5974 colonnes de depart
[23:00:00] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids TERMINE (0 chunks voisins demandes, 0 non chargeables) : 0 bloc(s) flowing -> source, 1671 colonne(s) / 7936 bloc(s) d'eau remis a niveau, 0 colonne(s) / 0 bloc(s) de lave, 496 ms
[23:00:00] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] [16.3 s] decorate TERRAIN TERMINE : terrain fige, la structure peut etre posee (T39)
[23:00:01] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorateFinish (POST-POSE, apres TOUS les fixwater) : decors + arbres + naturalisation (aucun smooth) -- T75
[23:00:01] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] scatter [OBSERVATORY]: base=9, multiplier=2.1538775551368023, target=19, village loot chests=0
[23:00:01] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] scatter [OBSERVATORY] : 13 naturels (cible 13) sur 19 decors poses
[23:00:01] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] scatter [OBSERVATORY] : 19/19 schematics vanilla poses AVANT les arbres (22 rejetes), couronne 46 blocs
[23:00:01] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] scatter [OBSERVATORY] : T42 -- 46 colonne(s) de decors plaquees au sol (aucune mini-structure ne flotte)
[23:00:01] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 5/5 : decors (scatter) poses
[23:00:01] [Server thread/INFO] [de.pr.Structures5Procedure/]: [STRUCT5] decorate + post de observatory termine
[23:00:06] [Server thread/INFO] [de.ut.TerrainEditClamp/]: [DLB-PERF] TACHE COMPLETE en 21.6 s -- ou est passe le temps : pre-chargement des chunks 11.6 s (54%) | naturalize (herbes, fleurs, champignons) 634 3.8 s (17%) | verifyGrassSurface 0.9 s (4%) | balayage des vegetaux flottants 0.8 s (4%) | cleanupZone 0.7 s (3%) | fixLiquids 0.5 s (2%) | rebuildTerrain 0.5 s (2%) | hole filler (tunnels / failles / cavites) 0.4 s (2%) | attente/tick system 2.5 sacces terrain : 360 requete(s) moteur, 1368655 lecture(s) servies par le cache (100%), 0 colonne(s) hors memoire
[23:00:06] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] [21.6 s] decorate TERMINE (decors, arbres, naturalisation -- structure posee en DERNIER, T39)

observatory: command submitted; response: [DLB-TEST] Structure OBSERVATORY demandee a 600, 68, 0 -- la construction demarre apres le choix de zone (regarde la console)


citadel: 51.10s from command to final decoration marker
[22:59:33] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 3/5 : double smooth termine
[22:59:33] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 3/5 : sealWaterLeaks debut
[22:59:33] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 3/5 : sealWaterLeaks termine
[22:59:33] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 4/5 : smooth cible eau termine
[22:59:35] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids : rayon demande 1000 blocs (15876 chunks candidats) autour de 124,11 -- T75 : les chunks ABSENTS de la memoire sont demandes et reessayes (c'est dans ceux-la que restaient les murs d'eau figes aux frontieres du terrain)
[22:59:35] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids : anneau proche (emprise + 48 blocs = chunks 2..12 / -5..6) charge a la demande ; au-dela, seuls les chunks deja en memoire sont traites (T75)
[22:59:35] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] [21.6 s] fixLiquids : passe /fixwater + /fixlava planifiee (rayon 1000)
[22:59:36] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids 1/2 : 210 chunks charges (0 voisins charges a la demande, 15666 jamais chargeables) -- 0 bloc(s) flowing -> SOURCE, 1497 colonne(s) d'eau et 0 colonne(s) de lave memorisees (424 ms)
[22:59:36] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids 2/2 : propagation du niveau d'eau/lave sur 1497 colonnes de depart
[22:59:36] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] fixLiquids TERMINE (0 chunks voisins demandes, 0 non chargeables) : 0 bloc(s) flowing -> source, 8996 colonne(s) / 40000 bloc(s) d'eau remis a niveau, 0 colonne(s) / 0 bloc(s) de lave, 613 ms
[22:59:36] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] [22.2 s] decorate TERRAIN TERMINE : terrain fige, la structure peut etre posee (T39)
[22:59:38] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorateFinish (POST-POSE, apres TOUS les fixwater) : decors + arbres + naturalisation (aucun smooth) -- T75
[22:59:38] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] scatter [CITADEL]: base=14, multiplier=2.7605561529161373, target=39, village loot chests=0
[22:59:38] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] scatter [CITADEL] : 11 naturels (cible 11) sur 39 decors poses
[22:59:38] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] scatter [CITADEL] : 39/39 schematics vanilla poses AVANT les arbres (79 rejetes), couronne 46 blocs
[22:59:38] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] scatter [CITADEL] : T42 -- 89 colonne(s) de decors plaquees au sol (aucune mini-structure ne flotte)
[22:59:38] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] decorate 5/5 : decors (scatter) poses
[22:59:38] [Server thread/INFO] [de.pr.Structures5Procedure/]: [STRUCT5] decorate + post de citadel termine
[22:59:42] [Server thread/INFO] [de.ut.TerrainEditClamp/]: [DLB-PERF] TACHE COMPLETE en 28.2 s -- ou est passe le temps : pre-chargement des chunks 10.9 s (39%) | naturalize (herbes, fleurs, champignons) 91, 3.0 s (11%) | clearSurfaceDecor 2.6 s (9%) | rebuildTerrain 2.3 s (8%) | cleanupZone 1.5 s (5%) | hole filler (tunnels / failles / cavites) 1.1 s (4%) | preparation du terrain (eau intacte) 1.0 s (3%) | balayage des vegetaux flottants 0.9 s (3%) | attente/tick system 4.8 sacces terrain : 373 requete(s) moteur, 2273003 lecture(s) servies par le cache (100%), 0 colonne(s) hors memoire
[22:59:42] [Server thread/INFO] [de.ut.DebugLog/]: [DLB-STRUCTURE] [28.2 s] decorate TERMINE (decors, arbres, naturalisation -- structure posee en DERNIER, T39)

citadel: command submitted; response: [DLB-TEST] Structure CITADEL demandee a 0, 68, 0 -- la construction demarre apres le choix de zone (regarde la console)


Dedicated server started successfully.
```
