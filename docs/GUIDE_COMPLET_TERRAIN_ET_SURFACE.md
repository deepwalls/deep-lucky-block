# GUIDE COMPLET — TERRAIN, HABILLAGE DE SURFACE, DEGRADES ET VEGETATION (passation)

> Document recu de l'IA precedente le 27/09/2026, transmis par l'utilisateur en
> chat (version 1.0). Copie de travail archivee par moi pour recherche/consultation
> (les uploads de fichiers sont inaccessibles dans l'environnement).
> AVERTISSEMENT : les numeros de ligne cites datent de la version a 5 650 lignes
> de StructureTerrainPrep.java ; le fichier fait 5 863 lignes (T77-T81) — tout
> point d'ancrage a ete re-audite par grep avant usage. Le code du Livre XIV
> n'a jamais ete compile (le guide le dit lui-meme) : ses choix API ont ete
> verifies et adaptes (voir JOURNAL_AUTONOME.md, entree du 27/09).

---

L'essentiel strategique du guide (resume fidele ; le texte integral est dans
l'historique de conversation du 27/09) :

1. **Le probleme traite** : deux verrous mesures et confirmes au code
   (structure-surface : la zone sous la structure cree une « croûte » visible ;
   verifyGrassSurface vertit les deserts dorees ; naturalize ne repose aucune
   tige/canne/a sucre/cactus/lichen — seules des herbes en carres sur le ring).
2. **La solution** : une passe unique `dressAndPlant` (habillage + repose de la
   vegetation sauvee en SOS) qui remplace verifyGrassSurface + naturalize.
3. **La palette** est echantillonnee AVANT toute edition (2x2 par chunk, inertie
   des categories) puis CONGELEE — jamais re-echantillonnee apres (invariant I1).
4. **Le tramage par hachage de position** (hash x,z XOR + golden-ratio) a ete
   CHOISI contre Bayer/blue-noise (0,68 pt de fidelite vs 6,0, crochet avalanche
   gagne a ~2^14). Sujet clos, ne pas re-ouvrir.
5. **Le warping** de la distance au bord (WARP_CELL=12, WARP_AMP=4, contrainte
   WARP_AMP + BLEND_BAND <= 16) evite les rigides frontieres/bandes pleines.
6. **La vegetation** est sauvee au scan initial (cactus/canne/tiges doubles
   incluses, style colonne-par-colonne) et reposee via `BlockState.canSurvive`
   (PAS de table sol/plante bloc par bloc ; FastFlag pour les hauts de tiges
   doubles, distances au cache 3x3 sur recyles).
7. **Plan en 8 commits** : (1) ChunkMajorZone + passe par chunk ; (2) neige par
   chunk (coldEnoughToSnow, cache) ; (3) reparer la dette T71 dans les boucles ;
   (4) echantillon de palette en debut de prepZone (journal seul) ; (5)
   dressAndPlant remplace verifyGrassSurface + naturalize AVEC [server-check] ;
   (6) sauvetage des plantes greffe sur scanTrees ; (7) rangement + docs ;
   (8) wrap-up bench.
8. **Perimetre interdit** : ne jamais attendre un chunk, jamais toucher au
   ring laissé, jamais modifier le terrain natif hors zone, conserver toute
   methode tant que son remplacant n'est pas valide.

## Etat re-coupe au 27/09 (fait par cette session-ci)

- StructureTerrainPrep.java : 5 863 lignes (guide : 5 650). readBlock 6 /
  getBlockState 62. columnsOf : 13 sites d'appel. verifyGrassSurface +
  naturalize encore APPELEES (prepZone 10/11 pre-pose, decorateChain 10/18
  post-pose, appel direct l.3919). ChunkMajorZone / dressAndPlant : absents.
- ColumnPass/startColumnPass existent deja avec les gardes du mod
  (ChunkKeeper.keep par slice, TerrainChain.heartbeat, zoneReady reporte,
  reprise T70 des colonnes non chargees, anti-doublon par label,
  sliceBudgetMs adaptatif dragon) — remplacent le ChunkPass du guide, qui
  violerait I6 et T13 (server.execute).
- ColumnPass actuel confirme le bug : verifyGrassColumn vertit tout
  isNaturalTerrain hors SAND/SANDSTONE/RED_SAND (gres rouge des mesas
  compris) ; isSnowy = comparaison de chaine par colonne.
- Recouvrements deja realises cette session : verrous eau = T77 (merge CI x4) ;
  ancrage mediane = T76 (merge CI x4) ; Everest exclu = T78 (merge CI 2x) ;
  clearSurfaceDecor sections T71 = T79 (merge CI) ; lake probe = T80.
- Choix API adaptes : pas de WORLD_SURFACE_WG pour l'habillage post-edition
  (instantane worldgen) — on garde SafeSurface (garde T7) ; coldEnoughToSnow
  au lieu de la chaine (signature 1.21.1 confirmee) ; BlockState.canSurvive
  existe ; BlockTags.TERRACOTTA existe ; SnowLayerBlock.LAYERS borne 1..8.
