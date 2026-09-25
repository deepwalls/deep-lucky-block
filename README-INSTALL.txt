DEEP LUCKY BLOCK — NEOFORGE 1.21.1 (CIBLE DE MIGRATION)
═════════════════════════════════════════════════════════
Minecraft 1.21.1 + NeoForge (JDK 21 requis).

CE QUI A ÉTÉ CHANGÉ :
  - autoport mécanique (imports, événements, bus, config) — voir
    PORT_REPORT.txt dans src/ : 63 fichiers modifiés
  - kit de port manuel (5 fichiers remplacés) :
      DeepLuckyBlockMod.java          (entrée mod, bus NeoForge, payloads)
      network/DeepPayloads.java       (API réseau 1.21.1)
      network/DeepLuckyBlockModVariables.java
      enchantments/CustomEnchantment.java (nouvelle API enchantement 1.21.1)
      util/DeepEnchant.java           (helper : setLevel/getLevel/remove)
  - build.gradle / settings.gradle / gradle.properties (ModDevGradle)
  - neoforged.mods.toml (remplace mods.toml)
  - deep_lucky_block.mixins.json en JAVA_21

INSTALL :
  1. Installer JDK 21 (JDK 17 ne suffit plus pour 1.21.1).
  2. Extraire.
  3. Double-clic sur  deep_lucky_block-neoforge-1.21.1.mcreator
     → MCreator pose le générateur NeoForge 1.21.1.
     MCreator proposera le re-mappage des modèles (mojmap 1.20.x → 1.21.x) : accepter.
  4. Lancer RUN (client) dans MCreator — c'est lui qui "lance le jeu".
     Le premier build Gradle prend 5-10 min (téléchargements).
  5. Corriger les sites MANUAL de PORT_REPORT.txt au fur et à mesure
     (les erreurs de compile les pointent tous, classés par gravité).

ORIENTATION (voir MIGRATION_MAP.md dans port_1.21.1/ si tu l'as conservé) :
  - 133 sites MANUAL : la plupart sont des TickEvent (méthodes renommées),
    de la gestion d'enchants résiduelle, et 7 sites d'enregistrement
    des spawn handlers (PlayMessages supprimé — utiliser l'équivalent
    NeoForge de l'entité concernée).
  - Structures .nbt : si un portail ne se charge plus, le palette
    mapping 1.20.1 → 1.21.1 doit être refait.
