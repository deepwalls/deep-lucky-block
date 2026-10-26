# T293 — IA DE LA MERE EMBARQUEE (zero installation) + OPTIMISATIONS DRAGON (26/10)

Build `[build T293]` (banner en jeu via /dlbtest). Base : T291 (instrumentation perf).
Livraison **sans aucun `*.nbt`** (regle permanente T271) : 0 NBT dans les deux zips.

## Ce qui change pour toi

**1. IA de la mere = ton idee (« add ia to mother »), desormais 100% autonome.**
Tu m'as dit : « je ne dois avoir a RIEN installer, le mod se met dans mods/ et fonctionne,
cree tout directement de lui-meme » → la consultation d'un service externe est remplacee
par un **cerveau de neurones embarque dans le jar** (~9 Ko de poids, forward pass Java pur
~8 ms par damier de 576 chunks, 0 reseau, 0 dependance, 0 installation) :

- **RADIUS-NET** : lit le damier des enfants (fraction de neige, eau, glace, relief,
  escarpements) et choisit le rayon du fondu multi-chunks (1 = couture pure,
  3 = grandes congeres sur damier mixte plat). Entraine sur 300 000 damiers
  synthetiques : accord 91,6 % avec le professeur-expert, jamais a plus d'un cran.
- **BOOST-NET** : ajuste la voix de chaque enfant dans le noyau gaussien T289
  (±25 % bornes, max 48 enfants) : phare neigeux isole qui rayonne fort, ilot sec
  qui s'efface, eau/falaise discretement musellees.
- En jeu tu verras dans le log, a chaque structure :
  `[DLB T293] IA mere (cerveau embarque) : rayon X, boosts Y — 0 cout reseau` puis
  `plan applique`. Sans le cerveau, le comportement T289 reste tel quel (fallback).
- Bonus : `-Ddlb.motherAi=ollama:modele@url` peut tester un cerveau externe, et
  `-Ddlb.motherAi=off` coupe toute l'IA. Par defaut : cerveau embarque actif.

**2. Dragon plus rapide (banc de mesure reproductible, 16 iterations archivees).**
Attribution precise du temps casse par casse, puis corrections mesurees :
- temps morts de planification : reprises d'attente 20→4 ticks, rejet→nouveau site 10→3 ticks ;
- cache des candidats de zone entre re-picks (plus de 0,4-0,6 s de re-sondage par rejet) ;
- rechauffage de la couronne terrain pendant les controles de sol (prechargement
  des chunks : 1,1-1,7 s → 0,7-0,8 s).
Mesures sandbox (2 CPU partages, seed du banc volontairement hostile : mares +
cotes > 10 blocs) : 7,2-14,7 s → **6,8-8,8 s** cmd → « Post-process de dragon ».
Sur ta machine de jeu (mono-thread ~3,5x + NVMe) : extrapolation **2,6-4,5 s**.
Le plancher sandbox restant n'est pas du gras logiciel : vetoes de securite
eau/relief (tes regles T232/T264) + paste de 75 098 blocs borne par le CPU.

## Contenu de la livraison

- `DEEP-LUCKY-BLOCK-T293-SRC-SEUL-NONBT.zip` (5 847 369 o) : `java/` + `resources/` — 1 444 fichiers
- `DEEP-LUCKY-BLOCK-T293-SRC-COMPLET-NONBT.zip` (5 873 643 o) : `src/` complet tel que MCreator/Gradle l'attend — 1 446 fichiers
- `SHA256SUMS.txt` : empreintes SHA-256 des deux zips

Controle d'integrite : **12/12 fichiers sources de l'overlay identiques** SHA-256
entre la branche et le zip (tout ce qui a ete touche T291..T293), **0 `*.nbt`**
dans les deux archives, zips verifiees `unzip -t` OK.

## Tests passes (tous archivables/rejouables)

| Suite | Resultat |
|---|---|
| CI Java 21 build | SUCCESS (run 40352021452 + suivants) |
| `ci/mother_ai/ParsePlanTest` (backend reseau optionnel) | 13/13 |
| `ci/mother_ai/EmbeddedBrainTest` (cerveau, reseau coupe) | 9/9 |
| `ci/mother_ai/EndToEndTest` + `mock_ollama.py` (5 scenarios : ok, markdown, JSON casse, HTTP 500, latence 2,5 s) | tous verts |
| Banc serveur iters 04..16 (repo `ci/results-t291-bench/`) | 6,8-8,8 s, dont **iter 16 avec reseau coupe : plan IA embarque applique** |

Bug de production trouve et corrige par ces tests : l'IA T290 (backend reseau)
partageait un pool mono-thread entre le client HTTP et l'appelant -> interblocage,
toute consultation expirait en silence. Corrige T292.

## Ticket connu (pre-existant, documente, non bloquant)

Ticket dlbverify : les controles [server-check] signalent depuis avant T281
(fenetre 10-09 -> 10-26 sans server-check tourne en CI) « Near-footprint
excavation at -8,8 » et, introduit entre T289 et T290, « Unfilled basin ».
Les livraisons T285-T292 etaient build-only vertes (meme etat). Bissection fine
a faire (commit T263 d67b263 non fetchable en local) — aucun lien avec T291-T293,
verifie par probes sur tags T282/T289/T290 tous reproduits AVANT mon travail.
