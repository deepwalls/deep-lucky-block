# 🎨 Patch — brancher le glint coloré dans `GenerateLuckyItemProcedure.java`

Les 2 fichiers d'intégration sont fournis :
- `deepluckyblock/client/DeepGlintColor.java`  → résout la couleur de thème d'un enchantement.
- `deepluckyblock/compat/DeepCustomGlint.java` → applique le glint coloré via Glint & Glamour.

Tu dois juste **appeler `DeepCustomGlint.applyThemed(stack)`** dans ton
`GenerateLuckyItemProcedure.java`. L'idéal : **dans `addEnchantment(stack, id, lvl)`**,
car TOUS les items du mod y passent (récompense, curse, Creator, Samouss, Bo' Cube…).
Comme la méthode est idempotente, la relancer à chaque ajout est sans risque.

---

## 1) Ajoute l'import (en haut de la classe)

```java
import deepluckyblock.compat.DeepCustomGlint;
```

---

## 2) Dans `addEnchantment(stack, id, lvl)` — juste avant la fin de la méthode

La méthode se termine par :
```java
        stack.enchant(en, Math.max(1, Math.min(200, lvl)));
    }
```
Remplace-la par :
```java
        stack.enchant(en, Math.max(1, Math.min(200, lvl)));
        // 🎨 NOUVEAU ENVIRONNEMENT : glint COLORÉ selon le thème de l'enchantement.
        // No-op si la lib Glint & Glamour n'est pas bundle/installée.
        DeepCustomGlint.applyThemed(stack);
    }
```

> C'est LE point d'entrée central : chaque item enchanté du mod (récompense,
> malédiction, item de créateur, armure Samouss, Bo' Cube, item généré par le
> Lucky Idol… ) passe par `addEnchantment`, donc reçoit automatiquement son glint
> coloré dès qu'il possède au moins un enchantement à thème.

---

## 3) Optionnel — désactiver le glint de test dans la console

Dans `deepluckyblock/client/DeepGlintColor.java`, `DEBUG` est à `false` par défaut
(aucun log). Passe-le à `true` uniquement pour vérifier la couleur résolue :
```
[DLB-GLINT] applied #ff286eff on Épée de Laink
```

---

## 4) Ajoute la lib Glint & Glamour (le joueur n'installe RIEN)

Tu dois **bundle** la lib dans le jar (ou la déclarer en dépendance). Voir le guide
**`GLINT_API_SETUP.md`** (même config que deeps_enchantments) :

```gradle
repositories {
    maven {
        name = "TunaMods Glint & Glamour"
        url = "https://raw.githubusercontent.com/TunaMods/Glint-and-Glamour/1.20.1/mcmodsrepo"
    }
}
dependencies {
    compileOnly fg.deobf("net.tunamods.customglint:glint-and-glamour-api:1.7.0")
    runtimeOnly fg.deobf("net.tunamods.customglint:glint-and-glamour-api:1.7.0")
    jarJar(group: 'net.tunamods.customglint', name: 'glint-and-glamour-api',
           version: '[1.7.0,2.0)') { jarJar.ranged(it, '[1.7.0,2.0)') }
}
jarJar.enable()
```

> En dev, ajoute aussi aux `runs` (pour les hooks mixin de la lib) :
> ```gradle
> runs { configureEach {
>     property 'mixin.env.remapRefMap', 'true'
>     property 'mixin.env.refMapRemappingFile', "${projectDir}/build/createSrgToMcp/output.srg"
> } }
> ```

---

## ⚠️ Honnêteté
Je ne peux pas compiler ici (pas d'environnement Forge ni la lib). Le code suit la
doc officielle de l'API (méthode `CustomGlint.write(stack, design, color)`, constante
`WAVE`). Si une signature diffère, c'est **une ligne** à ajuster. Sans la lib, tout est
**no-op** : ton mod garde exactement son comportement actuel, aucun crash.
