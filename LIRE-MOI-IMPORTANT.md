═══════════════════════════════════════════════════════════════════
  DEEP LUCKY BLOCK — 1.21.1 NEOFORGE — v8  (armures + coffres + traces)
═══════════════════════════════════════════════════════════════════

POURQUOI UNE v8 ?
─────────────────
Ton dernier log de test (celui que tu m'as renvoyé) prouve que le jeu
tournait avec le code v6, PAS la v7 :
  • "Prepared 7 mixins" — la v7 en prépare 8 ;
  • le mixin HumanoidArmorLayerEnchantMixin apparaît avec seulement 2
    injections (capture/clear de l'aura) — la v7 en a 3 ;
  • aucune ligne [SLB-V7] dans tout le log ;
  • en revanche "Samouss-DEBUG: positions armure corrigees" EST présent
    (c'est du code d'origine de MCreator, pas le mien).
Autrement dit : les fichiers v7 n'ont pas été recopiés dans ton
workspace MCreator. La v8 corrige ça par deux moyens :
  1. une bannière de version dans le log — la 1re ligne après "Prepared"
     doit être "[SLB-V8] Deep Lucky Block v8 (2026-09-05) charge" ;
     SI TU NE VERRAS PAS [SLB-V8], c'est que les fichiers ne sont pas
     les bons, et il faut relire la procédure ci-dessous.
  2. des diagnostics [SLB-V8] qui tracent tout le flux (pose, casse,
     infusion, coffre) même si MCreator régénère des fichiers.

CE QUI CHANGE EN v8
────────────────────
ARMURES (fix définitif, par le mécanisme OFFICIEL de NeoForge) :
  • J'ai relus les sources de NeoForge 1.21.1 : le hook getHumanoidArmorModel
    des items EST appelé par HumanoidArmorLayer (c'est pour ça que ton log
    montre "Samouss-DEBUG: positions armure corrigees"). MAIS la boucle de
    rendu de NeoForge ne s'exécute que s'il y a au moins UN "layer" dans le
    material de l'armure — les tiens en avaient ZÉRO (List.of()) → 0 rendu
    → invisible. Le workaround de rendu que j'avais mis en v7 (CustomArmorRender
    + injection mixin) n'était pas nécessaire : je l'ai retiré.
  • Fix : SAMOUSS_MATERIAL et LUNETTES_MATERIAL ont maintenant un
    ArmorMaterial.Layer (init/DeepLuckyBlockModItems.java). Résultat :
    NeoForge rend le modèle Blockbench custom (hook item) avec la texture du
    mod, le glint d'enchante s'applique sur le modèle custom, et c'est du
    code 100 % officiel (plus fragile = jamais).
  • À vérifier : équiper le Samouss / les Lunettes du Patron et les regarder
    de face + F5.

COFFRES (fix v7 conservé) :
  • Lucky Chests : le drapeau passe par NeoForgeData + BLOCK_ENTITY_DATA au
    chargement (survit aux rechargements /data sync), + filet de transfert
    à la pose du coffre, + force de chargement de la procédure.
  • Diagnostic [SLB-V8] COFFRE : clic droit sur un coffre → le log dit
    s'il porte les drapeaux slb_* et son rank.

LUCK (diagnostics pour localiser, le flux a été audité sans bug) :
  • Nouveau fichier manuel SlbDiagnostics.java (MCreator ne peut pas le
    régénérer) qui trace, avec [SLB-V8] :
      POSE      : item dans la main au clic droit → "luck=10" attendu.
                  (la luck vient de l'infusion — POSE=10 prouve que
                  l'infusion a fonctionné ; la recette trace aussi
                  [SLB-DEBUG] infusion: ... luckFinale=10) ;
      CASSE     : blockentity au moment de la casse → "beLuck=10" attendu.
  • En jeu, un bloc +10 cassé DOIT afficher le holo flottant
    "⚡ +10% Luck Bonus!" au-dessus de la roue. Si le holo s'affiche mais
    que les drops semblent identiques, le problème est dans la roue elle-
    même (les logs [SLB-DEBUG] de TestProcedure, si tes fichiers sont les
    miens, donneront la valeur effectiveLuck).
  • SI les traces disent luck=10 partout et que c'est encore KO →
    renvoie-moi ces lignes [SLB-V8], j'attaque la roue.

CONSOLE (invariable)
────────────────────
  ./gradlew runClient        (le client intégré, pas le jar)

INSTALLATION — LIRE AVANT TOUT (c'est ici que ça a cassé la dernière fois)
──────────────────────────────────────────────────────────────────────────
Ton workspace MCreator est :
  C:\Users\Nazario Delucci\MCreatorWorkspaces\deep_lucky_block-neoforge-1.21.1

La v7 a échoué parce que seuls certains fichiers avaient été remplacés.
Cette fois, REMPLACEMENT COMPLET :

  Étape 1 — Ferme MCreator (totalement, vérifier la barre des tâches).
  Étape 2 — Renomme ton dossier actuel :
            deep_lucky_block-neoforge-1.21.1  →  deep_lucky_block-NEO-backup
            (ne le supprime PAS, on aura besoin de tes fichiers MCreator)
  Étape 3 — Dézippe CE zip v8 dans C:\Users\Nazario Delucci\MCreatorWorkspaces\
            → il crée le dossier  deep_lucky_block_1.21.1\  ... ce NOM EST
            DIFFÉRENT, on va le corriger à l'étape 4.
  Étape 4 — De ton dossier de backup, copie dans deep_lucky_block_1.21.1\
            TOUT ce qui n'y est PAS encore (ou écraser) :
              •  MCreatorWorkspace.json
              •  les dossiers  elements\  et  procedures\  (si présents
                 dans le zip, sinon garde les tiens du backup)
              •  le dossier  .mcreator\  (si présent)
            En résumé : le zip v8 = base du projet ; tout fichier de
            MCreator (workspace.json, elements, procedures, .mcreator)
            provient du backup et doit être présent à la fin.
            Les fichiers Java/src/resources du zip v8 ne sont JAMAIS
            remplacés par ceux du backup.
  Étape 5 — Renomme  deep_lucky_block_1.21.1  →  deep_lucky_block-neoforge-1.21.1
            (exactement ce nom, pour que MCreator le trouve).
  Étape 6 — Dans MCreator : File → Open, ouvre le workspace.
  Étape 7 — Avant de lancer : vérifie 2 fichiers dans ton workspace
            (double-clic → Notepad) :
            a) src\main\java\deepluckyblock\init\DeepLuckyBlockModItems.java
               → doit contenir la ligne  "ARMOR_LAYER_TEXTURE"
                 (sinon = mauvais fichier → recommence l'étape 4).
            b) src\main\java\deepluckyblock\SlbDiagnostics.java
               → le fichier doit EXISTER (sinon = mauvais dossier).
  Étape 8 — runClient.

VÉRIFICATION DU BON CODE (dans le log, tout en haut) :
  ✔  "[SLB-V8] Deep Lucky Block v8 (2026-09-05) charge"
  ✔  "Prepared 7 mixins"  (le rendu custom n'utilise plus de mixin)
  ✘  si tu vois "patch v7" ou pas de [SLB-V8] → mauvais fichiers,
     recommence l'installation.

TESTS À FAIRE (dans l'ordre)
────────────────────────────
  1. ARMURES : ouvre l'onglet créatif Deep's Lucky Block, prends le Samouss
     (tête, poitrine, jambes) et les Lunettes du Patron, équipe-toi,
     regarde-toi + F5. → DOIT être visible (cochon en armure, lunettes).
  2. LUCK : craft un Deep Lucky Block + 1 carotte dorée (recette d'infusion)
     → log [SLB-DEBUG] infusion doit dire luckFinale=10. Pose-le →
     [SLB-V8] POSE luck=10. Casse-le → [SLB-V8] CASSE beLuck=10 + holo
     "⚡ +10% Luck Bonus!" en jeu.
  3. COFFRES : pose 2 Lucky Chests + 1 Deep Lucky Block entre/autour,
     clic droit → le coffre se remplit (log [SLB-V8] COFFRE + [SLB-CHEST]
     si le filet de transfert a dû jouer).
  4. GLINT : un bloc enchante + une armure enchantée → aura dorée (déjà
     corrigé, juste une ré-vérification).

RENVOIE-MOI SI KO :
  • le log COMPLET (dès "Prepared ..." jusqu'à tes actions) ;
  • les lignes [SLB-V8] exactement ;
  • ce que tu vois en jeu pour l'armure (rien / noir / partielle) et le
     holo de la roue (affiché ou non, avec quel texte).

═══════════════════════════════════════════════════════════════════
