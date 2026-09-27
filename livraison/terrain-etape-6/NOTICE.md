# Étape 6 — ancrage sûr : repli sur le sol médian mesuré (doPaste)

Fichier : `Structures5Procedure.java` (complet — copier-coller direct MCreator).
Base : version utilisateur du 27/09 (origin/main 8ab74b4), déjà compilée en jeu.
Version « avant » : `avant/Structures5Procedure.java`.

> Statut : fichier livré EN SECOND (un seul fichier de production par
> livraison). **Compilé en CI Java 21** (runs 36331359511 / 36332468110 :
> build OK), comportement pas encore exercé en session serveur (les fixtures
> eau bloquaient avant le dragon — maintenant corrigées, re-mesure en attente
> du retour de la connexion GitHub).

## Problème mesuré (test en jeu du 27/09)

Dragon ancré à groundY=65 (mesure sur UNE colonne centrale, sur un candidat de
repli « pente minimale brute, eau=100% ») alors que le sol médian de l'emprise
valait 49 : modèle posé à first blockY=31 → 18 blocs sous le sol. La suppression
du ré-ancrage post-smooth (étape 2) avait retiré le filet de sécurité.

## Correctif T76

Dans `doPaste`, après le calcul de la boîte englobante et **avant** tout appel
terrain (prepZone) :

- mesure du sol solide sur l'emprise entière (`SafeSurface.groundStats`,
  foulée 4, médiane/min/max/nb colonnes) ;
- si ≥ 16 colonnes mesurées et |médiane − baseY| > MAX_POST_SMOOTH_DELTA_Y (4)
  → **translation verticale unique** de baseY, fp, rp, min, max vers la médiane ;
- sinon l'ancrage reste fixe (et le diagnostic après terrain continue d'avertir
  si le sol reste inégal, sans jamais re-déplacer le modèle).

L'offset manuel du modèle (dragon −34, citadel −35, observatory −5) est appliqué
exactement UNE fois, en aval, sur l'ancre corrigée : `foundationBaseY =
groundY + oy` inchangé. L'enfoncement du lac (sink 10, autre fichier) n'est PAS
touché.

## Limites admises

- Si l'emprise n'est pas mesurable avant terrain (chunks non générés → 0
  colonne), l'ancrage central est conservé avec avertissement explicite : la
  fiabilisation de la sélection de zone (priorité 5) traitera ce cas à la racine.
- Le fichier est compilé en CI mais pas encore exercé en session serveur
  complète ni en jeu.
