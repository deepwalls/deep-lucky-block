# Étape 5 — fixLiquids : règle stricte d'appartenance à une nappe (anti-inondation)

Fichier : `StructureTerrainPrep.java` (complet, 5 813 lignes — copier-coller direct MCreator).
Base : version utilisateur du 27/09 (origin/main 595de78), déjà compilée en jeu.
Version « avant » : `avant/StructureTerrainPrep.java`.

## Problème mesuré (test en jeu du 27/09)

`fixLiquids` a noyé le paysage : 46 539 colonnes / 239 715 blocs d'eau, 34,3 s,
après l'élargissement ×4 de la portée. La propagation (clone de WorldEdit
`/fixwater`) remplit TOUTE terre ferme sous le niveau de l'eau et propage depuis
chaque remplissage : toute la plaine inondable connectée à l'océan part sous l'eau.

## Correctif T77

Règle stricte demandée par l'utilisateur : ne jamais remplir une colonne sèche
qui n'appartient pas à une nappe connectée préexistante, et ne jamais élever un
niveau au-dessus de celui de la nappe d'origine.

- Devant chaque remplissage, un verdict par colonne : ENCLAVE (bassin fermé
  prouvé) → remplissage exactement à hauteur de la nappe ; OUVERT → rejet
  définitif à ce niveau ; non prouvé → preuve différée.
- La preuve est un BFS d'évasion borné (budget par tick) sur la composante de
  terre ferme sous le niveau, par lecture heightmap MOTION_BLOCKING seule
  (aucune génération, aucun scan bloc). Verdicts : frontière de la zone, chunk
  inconnu, ou plafond d'exploration (200 000 colonnes) ⇒ OUVERT ; épuisement à
  l'intérieur ⇒ ENCLAVE.
- Le corps de remplissage inline d'origine est déplacé tel quel dans
  `tryFillColumn` (toutes les conditions exactes re-vérifiées à l'instant du
  remplissage : fond proche, fond naturel, creux libre, plafonds de volume).
- Portée LARGE ×4 CONSERVÉE (demande utilisateur) : seule la règle change.
- Le bilan `fixLiquids TERMINE` affiche désormais les preuves d'enclave et les
  rejets terre ferme ouverte.

## Correctif associé (mesuré en CI, run 36331359511)

Tempête de chunks dans la passe (1 360 demandes, retry « tour 43/6 », job sans
fin) : la branche de retry onDemand n'avait aucune borne de tours, et les
preuves re-demandaient des chunks. Désormais : retry onDemand borné à
FIXLIQ_PENDING_ROUNDS=6 (puis `stalled`, comme la branche request()), et les
preuves ne demandent jamais de chunk (inconnu = OUVERT).

## Historique des mesures (transparence complète)

1. Run CI 36331233923 : build Java 21 **OK** (compile seule).
2. Run CI 36331359511 : build OK, session serveur **échouée** — tempête de
   chunks préexistante (1 360 demandes, retry « tour 43/6 », jamais finie) que
   le ×4 avait introduite et que personne n'avait jamais mesurée (jamais de
   `[server-check]` sur les commits utilisateur). Correctifs : retry onDemand
   borné à 6 tours, preuves sans demande de chunk.
3. Run CI 36332468110 : build OK, session **échouée** —
   `Unfilled basin at -43,241,-42` : zéro remplissage. Cause : la file de
   preuve garait la colonne d'eau (packed) au lieu du candidat sec (nk).
   Correctifs : candidat garé, type de liquide parent conservé, colonnes
   remplies exclues des parois, anneau de force pré-×4, frontière onDemand
   bornée au halo seedMargin.
4. Simulation `ci/sim_t77.py` (rejoue l'algorithme colonne par colonne, hors
   JDK) : bassin fermé = **45 113 nouveaux blocs (+ 7 préexistants = 45 120,
   l'assert EXACTE de la fixture CI)** ; plaine ouverte = **0 remplissage**,
   902 colonnes prouvées OUVERTES.
5. AST tree-sitter : 0 erreur de syntaxe, 0 signature publique perdue,
   accolades équilibrées sur les 5 838 lignes.

## Limites admises

- **Ce fichier n'a PAS encore été re-mesuré en CI dans sa version finale** :
  le jeton GitHub du sandbox a expiré après le commit local (324b53f). La
  compilation réelle Java 21 et les fixtures `[server-check]` seront relancées
  dès le retour de la connexion (la livraison reste en attente de ce feu vert
  pour la mise en production ; les correctifs sont déjà validés par simulation).
- Un trou sec dont le bassin déborde de la zone chargée restera sec (verdict
  OUVERT) : c'est le choix « jamais noyer l'inconnu », contraire de
  l'inondation. Le halo chargé (anneau proche) couvre le cas visible en jeu.
- Les vérifications locales (tree-sitter, AST, simulation) ne prouvent NI la
  compilation, NI le comportement en jeu, NI les temps.
