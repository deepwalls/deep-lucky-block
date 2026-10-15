package deepluckyblock.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import deepluckyblock.DeepLuckyBlockMod;
import deepluckyblock.blockentity.DeepLuckyBlockBlockEntity;
import deepluckyblock.init.DeepLuckyBlockModBlocks;
import deepluckyblock.procedures.CrimsonLakeStructureSpawnProcedure;
import deepluckyblock.procedures.Structures5Procedure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /dlbtest — outil de TEST headless du pipeline de structures.
 *
 * POURQUOI : les freezes du pipeline terrain (prepZone / decorate) ne peuvent
 * etre reproduits qu'en jeu, et les commandes existantes (/eventtest,
 * /crimsonlake, /dlbaudit) exigent un JOUEUR connecte
 * (getPlayerOrException) : elles sont donc inutilisables depuis la console
 * d'un serveur dedie -- seul moyen de tester sans affichage graphique.
 *
 * Cette commande fonctionne AVEC OU SANS joueur (elle cree un FakePlayer si
 * elle est lancee depuis la console) et rejoue EXACTEMENT le meme appel que
 * celui declenche par la casse d'un Deep Lucky Block :
 *     TestProcedure (recompense) -> Structures5Procedure.execute(level, player, id)
 *
 *   /dlbtest structure &lt;id&gt;      : force une structure (memes ids que
 *                                   Structures5Procedure : 0=citadel, 3=circus,
 *                                   4=shipdead, 5=everest, ...).
 *   /dlbtest luckybreak [nombre]  : pose un Deep Lucky Block avec luck=100 puis
 *                                   le CASSE (avec le fake player), ce qui
 *                                   reproduit exactement l'action du joueur.
 *   /dlbtest ids                  : rappelle la table des ids.
 *
 * Utilisation depuis la console (ou en jeu, position = celle de l'executant) :
 *   execute positioned 0 80 0 run dlbtest structure 0
 *   execute positioned 0 80 0 run dlbtest luckybreak 20
 */
@EventBusSubscriber(modid = DeepLuckyBlockMod.MODID)
public final class DlbTestCommands {

    private DlbTestCommands() {}

    // =====================================================================
    // T67 : TABLE DES STRUCTURES TESTABLES (une entree /dlbtest par structure)
    // =====================================================================
    //
    // Demande utilisateur du 23/09 : « il faut un /dlbtest pour chaque
    // structure, everest, crimsonlake, dragon, citadel, ship, circus,
    // observatory ... (noms plus simples et cibles comme ca), il faut tout
    // rendre fonctionnel et minecraft doit bien nous proposer les choix avec
    // tab pour toutes mes commandes dlbtest ».
    //
    // AVANT : une seule entree generique `/dlbtest structure <id 0..40>` et un
    // `/dlbtest crimsonlake` isole : il fallait connaitre la table des ids par
    // coeur, et en partie solo (permission 0) la racine exigeait le niveau 2,
    // donc la commande n'etait NI reconnue NI proposee par le tab.
    //
    // MAINTENANT : un nom simple par structure (exactement le nom du fichier
    // .nbt), des alias francais, et des noeuds LITTERAUX -- un noeud litteral
    // est transmis au client, donc le TAB les propose tous.
    //
    // id < 0 : evenement special (Crimson Lake : chemin triggerFromTest).
    private record Struct(String name, int id, String nbt, String size, String... aliases) { }

    /** Les structures testables, dans l'ordre d'affichage du tab. */
    private static final List<Struct> STRUCTS = List.of(
            new Struct("citadel",     0, "citadel",     "5,99 Mo", "citadelle"),
            new Struct("observatory", 1, "observatory", "743 Ko",  "obs", "observatoire"),
            new Struct("dragon",      2, "dragon",      "2,32 Mo"),
            new Struct("circus",      3, "circus",      "2,05 Mo", "cirque"),
            new Struct("ship",        4, "shipdead",    "4,88 Mo", "shipdead", "bateau"),
            new Struct("everest",     5, "everest",     "194 Ko"),
            new Struct("crimsonlake", -1, "crimsonlake", "3,46 Mo", "lake", "lac", "crimson"));

    /** T67 : `-Ddlb.cmdOp=1` restaure l'ancienne restriction (operateur niveau 2). */
    private static boolean opOnly() {
        return "1".equals(System.getProperty("dlb.cmdOp", "0"));
    }

    /** Tous les noms et alias, pour le tab et la liste. */
    private static List<String> tabNames() {
        List<String> out = new ArrayList<>();
        for (Struct s : STRUCTS) {
            out.add(s.name());
            out.addAll(List.of(s.aliases()));
        }
        return out;
    }

    /** Suggestions du tab pour `/dlbtest structure <nom>` : noms, alias, ids. */
    private static final SuggestionProvider<CommandSourceStack> SUGGEST_STRUCTS =
            (ctx, builder) -> SharedSuggestionProvider.suggest(tabNames(), builder);

    private static Struct byName(String key) {
        if (key == null) return null;
        String k = key.trim().toLowerCase(Locale.ROOT);
        for (Struct s : STRUCTS) {
            if (s.name().equals(k)) return s;
            for (String a : s.aliases()) if (a.equals(k)) return s;
        }
        return null;
    }

    /** Accepte aussi les anciens identifiants numeriques (0..2, 16..18 compris). */
    private static Struct byId(int id) {
        int canonical = switch (id) {
            case 0, 16 -> 0;
            case 1, 17 -> 1;
            case 2, 18 -> 2;
            case 3 -> 3;
            case 4 -> 4;
            case 5 -> 5;
            default -> -99;
        };
        for (Struct s : STRUCTS) if (s.id() == canonical) return s;
        return null;
    }

    private static String namesList() {
        StringBuilder sb = new StringBuilder();
        for (Struct s : STRUCTS) sb.append(sb.length() == 0 ? "" : ", ").append(s.name());
        return sb.toString();
    }

    /** Lance la structure demandee (chemin identique a celui d'un lucky block casse). */
    private static int launchStruct(CommandContext<CommandSourceStack> ctx, Struct s) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        ServerPlayer player = fakeOrReal(src.getPlayer(), level, src.getPosition().x, src.getPosition().z);
        String where = player.blockPosition().toShortString();
        boolean fake = src.getPlayer() == null;
        DeepLuckyBlockMod.LOGGER.info("[DLBTEST] {} demandee a {} (id={}, nbt={}, joueur {})",
                s.name(), where, s.id(), s.nbt(), fake ? "factice" : "reel");
        String hint = s.id() < 0
                ? " -- presage de 120 s puis construction (le lac est un evenement)"
                : " -- la construction demarre apres le choix de zone (regarde la console)";
        src.sendSuccess(() -> Component.literal("[DLB-TEST] Structure " + s.name().toUpperCase(Locale.ROOT)
                + " demandee a " + where + hint), false);
        if (s.id() < 0) {
            CrimsonLakeStructureSpawnProcedure.triggerFromTest(level, player);
        } else {
            // T227 — retour « /dlbtest citadel » suivi de « [STRUCT] ID=0 confirmee » :
            // une pose de TEST ne doit pas consommer le droit de spawn du monde.
            Structures5Procedure.markManualTestSpawn();
            Structures5Procedure.execute(level, player, s.id());
        }
        return 1;
    }

    /** `/dlbtest structure <nom|id>` : par nom, par alias, ou par ancien id. */
    private static int launchByName(CommandContext<CommandSourceStack> ctx, String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        Struct s;
        try {
            s = byId(Integer.parseInt(key));
        } catch (NumberFormatException notANumber) {
            s = byName(key);
        }
        if (s == null) {
            final String k = raw;
            DeepLuckyBlockMod.LOGGER.warn("[DLBTEST] structure inconnue : '{}' (valides : {})", k, namesList());
            ctx.getSource().sendFailure(Component.literal("[DLB-TEST] Structure « " + k
                    + " » inconnue. Noms valides : " + namesList()));
            return 0;
        }
        return launchStruct(ctx, s);
    }

    /** `/dlbtest list` : la table complete (noms cliquables) + rappel du tab. */
    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("[DLB-TEST] " + STRUCTS.size()
                + " structures -- tape /dlbtest puis TAB :"), false);
        for (Struct s : STRUCTS) {
            String label = "  /dlbtest " + s.name() + "   (" + s.nbt() + ", " + s.size() + ")";
            if (s.aliases().length > 0) label += " [alias : " + String.join(", ", s.aliases()) + "]";
            final String l = label;
            final String cmd = "/dlbtest " + s.name();
            src.sendSuccess(() -> Component.literal(l).withStyle(st -> st
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                            net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, cmd))), false);
        }
        src.sendSuccess(() -> Component.literal(
                "[DLB-TEST] outils : water status|freeze [rayon]|thaw, probe <x> <z>, "
                        + "luckybreak [nombre], structure <nom|id>"), false);
        return STRUCTS.size();
    }

    /**
     * `/dlbtest tree` : deverse l'arbre Brigadier REELLEMENT enregistre pour
     * `dlbtest` (lecture directe du dispatcher du serveur -- donc exactement ce
     * que le client recoit et propose avec TAB). Chaque entree est cliquable.
     */
    /**
     * T226 — « mes structures ne spawnent pas malgre +400 blocs casses ».
     * Affiche quelles structures sont deja comptees comme apparues sur ce monde.
     * Si les 6 sont a OUI, plus aucune ne peut tomber : /dlbtest structure flags reset
     */
    private static int structureFlags(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        net.minecraft.server.level.ServerLevel lvl = src.getLevel();
        int[] f = deepluckyblock.procedures.GenerateLuckyItemProcedure.structureFlagSnapshot(lvl);
        String[] noms = {"Citadelle Sylvestre", "Observatoire Feerique", "Guilde du Dragon",
                         "Cirque Maudit", "Ruine d'Epave Fantome", "Everest d'Inoxtag"};
        int restantes = 0;
        src.sendSuccess(() -> Component.literal("§6[DLB] Structures deja apparues sur ce monde :"), false);
        for (int i = 0; i < 6; i++) {
            boolean prise = f[i] == 1;
            if (!prise) restantes++;
            final String ligne = (prise ? "§c  ✖ " : "§a  ✔ ") + noms[i]
                    + (prise ? " — deja apparue (ne retombera plus)" : " — encore disponible");
            src.sendSuccess(() -> Component.literal(ligne), false);
        }
        final int r = restantes;
        src.sendSuccess(() -> Component.literal(r == 0
                ? "§c§l⚠ Les 6 sont consommees : AUCUNE structure ne peut plus apparaitre. "
                  + "Utilise /dlbtest structure flags reset"
                : "§7" + r + " structure(s) encore disponible(s)."), false);
        return r;
    }

    private static int structureFlagsReset(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        deepluckyblock.procedures.GenerateLuckyItemProcedure.resetStructureFlags(src.getLevel());
        src.sendSuccess(() -> Component.literal(
                "§a[DLB] Drapeaux remis a zero : les 6 structures peuvent de nouveau apparaitre."), false);
        return 1;
    }

    private static int tree(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        var dispatcher = src.getServer().getCommands().getDispatcher();
        var root = dispatcher.getRoot().getChild("dlbtest");
        if (root == null) {
            src.sendFailure(Component.literal("[DLB-TEST] commande /dlbtest ABSENTE du dispatcher !"));
            DeepLuckyBlockMod.LOGGER.error("[DLBTEST] /dlbtest absent du dispatcher");
            return 0;
        }
        int n = 0;
        for (var child : root.getChildren()) {
            String name = "<" + child.getName() + ">";
            boolean literal = child instanceof com.mojang.brigadier.tree.LiteralCommandNode;
            StringBuilder subs = new StringBuilder();
            if (literal && child.getChildren().size() > 0) {
                for (var sc : child.getChildren()) {
                    if (subs.length() > 0) subs.append(' ');
                    subs.append(sc.getName());
                }
            }
            final String line = (literal ? "  /dlbtest " : "  (argument) ") + name
                    + (subs.length() > 0 ? "  -> " + subs : "")
                    + (literal && child.getChildren().isEmpty() ? "  (executable)" : "");
            src.sendSuccess(() -> Component.literal(line).withStyle(st -> st
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                            net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, "/dlbtest " + name))), false);
            n++;
        }
        final int total = n;
        src.sendSuccess(() -> Component.literal("[DLB-TEST] arbre enregistre : " + total
                + " entree(s) de premier niveau sous /dlbtest -- toutes proposees par TAB"), false);
        DeepLuckyBlockMod.LOGGER.info("[DLBTEST] arbre /dlbtest : {} entree(s) de premier niveau", total);
        return n;
    }

    /**
     * T67 : journal de demarrage -- la liste EXACTE de ce que le tab proposera.
     * Si cette ligne n'apparait pas dans le log, le jar du mod n'est pas charge
     * (c'est le premier point a verifier quand « la commande n'existe pas »).
     */
    private static void logTabTree() {
        List<String> names = new ArrayList<>();
        for (Struct s : STRUCTS) names.add(s.name());
        // T239 : l'etiquette de build dans la ligne « commande prete » permet
        // d'identifier instantanement la version compilée côté utilisateur
        // (trop de diagnostics ont buté sur « quelle version tournait ? »).
        DeepLuckyBlockMod.LOGGER.info("[DLBTEST] commande prete : /dlbtest -> {} | structure <nom|id> | list | "
                        + "water <status|freeze|thaw> | probe <x> <z> | luckybreak [nombre] "
                        + "(permission {}) [build T264]",
                String.join(", ", names), opOnly() ? "operateur (niveau 2)" : "tout le monde (0)");
    }


    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // =================================================================
        // T67 : noeuds LITTERAUX (donc proposes par le TAB) pour chaque entree
        // =================================================================
        final var root = Commands.literal("dlbtest");
        if (opOnly()) root.requires(src -> src.hasPermission(2));

        // Une seule entree visible par structure. Les alias restent acceptes via
        // `/dlbtest structure <alias>`, mais ne polluent plus le premier TAB avec
        // plusieurs commandes qui exécutent exactement la même chose.
        for (Struct s : STRUCTS) {
            root.then(Commands.literal(s.name()).executes(ctx -> launchStruct(ctx, s)));
        }

        // --- compatibilite : /dlbtest structure <nom|id> (avec suggestions) ---
        root.then(Commands.literal("structure")
                        .then(Commands.argument("nom", StringArgumentType.word())
                                .suggests(SUGGEST_STRUCTS)
                                .executes(ctx -> launchByName(ctx, StringArgumentType.getString(ctx, "nom")))))

                // --- table des structures (noms cliquables) ---
                .then(Commands.literal("list").executes(DlbTestCommands::list))
                .then(Commands.literal("ids").executes(DlbTestCommands::list))
                // --- T67 : arbre REELLEMENT enregistre (ce que le client recoit
                // pour le tab). Si une entree manque ici, elle manquera en jeu :
                // c'est le premier diagnostic a faire si le tab ne propose pas.
                .then(Commands.literal("tree").executes(DlbTestCommands::tree))
                // T226 : etat et reparation des drapeaux « une seule fois par monde ».
                .then(Commands.literal("flags")
                        .executes(DlbTestCommands::structureFlags)
                        .then(Commands.literal("reset").executes(DlbTestCommands::structureFlagsReset)))

                // === T50 : rejoue l'evenement CRIMSON LAKE hors evenement ===
                // (l'entree /dlbtest crimsonlake est desormais aussi une entree
                // directe ; ce chemin reste inchange : triggerFromTest)
                // ==========================================================
                // === T17 : eau -- gel / degel manuels (guide, section 39) ===
                // ==========================================================
                .then(Commands.literal("water")
                        .then(Commands.literal("status").executes(DlbTestCommands::waterStatus))
                        .then(Commands.literal("freeze")
                                .executes(ctx -> waterFreeze(ctx, 64))
                                .then(Commands.argument("rayon", IntegerArgumentType.integer(4, 256))
                                        .executes(ctx -> waterFreeze(ctx,
                                                IntegerArgumentType.getInteger(ctx, "rayon")))))
                        .then(Commands.literal("thaw").executes(DlbTestCommands::waterThaw)))
                .then(Commands.literal("mobs")
                        .executes(ctx -> listMobs(ctx, 64))
                        .then(Commands.argument("rayon", IntegerArgumentType.integer(4, 256))
                                .executes(ctx -> listMobs(ctx,
                                        IntegerArgumentType.getInteger(ctx, "rayon")))))
                .then(Commands.literal("probe")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> {
                                            ServerLevel level = ctx.getSource().getLevel();
                                            int x = IntegerArgumentType.getInteger(ctx, "x");
                                            int z = IntegerArgumentType.getInteger(ctx, "z");
                                            probe(level, x, z);
                                            return 1;
                                        }))))
                .then(Commands.literal("luckybreak")
                        .executes(ctx -> breakLucky(ctx.getSource().getLevel(), ctx.getSource().getPlayer(),
                                ctx.getSource().getPosition().x, ctx.getSource().getPosition().y,
                                ctx.getSource().getPosition().z, 1))
                        .then(Commands.argument("nombre", IntegerArgumentType.integer(1, 200))
                                .executes(ctx -> breakLucky(ctx.getSource().getLevel(), ctx.getSource().getPlayer(),
                                        ctx.getSource().getPosition().x, ctx.getSource().getPosition().y,
                                        ctx.getSource().getPosition().z,
                                        IntegerArgumentType.getInteger(ctx, "nombre")))));

        event.getDispatcher().register(root);
        logTabTree();

    }

    /** Compte et liste les entités réellement présentes autour de l'exécutant. */
    private static int listMobs(CommandContext<CommandSourceStack> ctx, int radius) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        var box = new net.minecraft.world.phys.AABB(src.getPosition(), src.getPosition()).inflate(radius);
        var entities = level.getEntities((net.minecraft.world.entity.Entity) null, box,
                e -> e instanceof net.minecraft.world.entity.LivingEntity
                        && !(e instanceof net.minecraft.world.entity.player.Player));
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (var e : entities) {
            String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
            counts.merge(id, 1, Integer::sum);
        }
        int butterflies = counts.getOrDefault("deep_lucky_block:crimson_butterfly", 0);
        src.sendSuccess(() -> Component.literal("[DLB-TEST] " + entities.size()
                + " mob(s) dans " + radius + " blocs; papillons Crimson=" + butterflies), false);
        for (var entry : counts.entrySet()) {
            src.sendSuccess(() -> Component.literal("  " + entry.getKey() + " x" + entry.getValue()), false);
        }
        return entities.size();
    }

    /**
     * /dlbtest probe &lt;x&gt; &lt;z&gt; — verifie la CHAINE DE MESURE DE HAUTEUR sur une
     * colonne : etat de la heightmap AVANT, valeur brute renvoyee par le jeu,
     * valeur apres passage par SafeSurface (notre correctif), etat APRES.
     *
     * Sert a prouver la cause racine des bugs de placement (structures a Y=-90,
     * "deltaY anormal (-65 -> 71)", "Aucune zone valide trouvee") : les chunks
     * livres par le chunk source ont une heightmap NON INITIALISEE, donc
     * getHeight() renvoie minBuildHeight (-64) au lieu du sol reel.
     */
    private static void probe(ServerLevel level, int x, int z) {
        try {
            var H = net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES;
            // ETAPE 1 : lecture BRUTE, sans aucun preparatif -- exactement ce que
            // faisait le code avant correctif (deepluckyblock.util.SafeSurface.height(level, ) sur un chunk
            // jamais visite). C'est cette valeur-la qui ancrait les structures a
            // Y=-90 / -78 et qui faisait "deltaY anormal (-65 -> 71)".
            var chunkNow = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            boolean loadedBefore = chunkNow != null;
            boolean primedBefore = loadedBefore && chunkNow.hasPrimedHeightmap(H);
            int rawBefore = deepluckyblock.util.SafeSurface.height(level, H, x, z) - 1;
            // ETAPE 2 : lecture CORRIGEE (SafeSurface : generation + heightmap utilisable).
            int fixed = deepluckyblock.util.SafeSurface.surfaceY(level, x, z);
            // ETAPE 3 : etat apres correction.
            var chunkAfter = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            boolean primedAfter = chunkAfter != null && chunkAfter.hasPrimedHeightmap(H);
            int rawAfter = deepluckyblock.util.SafeSurface.height(level, H, x, z) - 1;
            DeepLuckyBlockMod.LOGGER.info(
                    "[DLBTEST] PROBE {},{} : AVANT correction -- chunk charge={} heightmap initialisee={} getHeight brut={} | "
                            + "APRES correction (SafeSurface) -- hauteur={} heightmap initialisee={} getHeight relu={} | {}",
                    x, z, loadedBefore, primedBefore, rawBefore, fixed, primedAfter, rawAfter,
                    deepluckyblock.util.SafeSurface.stats());
        } catch (Throwable t) {
            DeepLuckyBlockMod.LOGGER.error("[DLBTEST] PROBE {},{} en echec", x, z, t);
        }
    }

    /** Un vrai joueur s'il y en a un, sinon un FakePlayer positionne au meme endroit. */
    private static ServerPlayer fakeOrReal(ServerPlayer real, ServerLevel level, double x, double z) {
        ServerPlayer p = real;
        if (p == null) {
            p = FakePlayerFactory.getMinecraft(level);
        }
        // On recale le faux joueur sur la position demandee : Structures5Procedure
        // s'en sert comme origine de la structure et pour l'orientation face au joueur.
        p.moveTo(x, p.getY(), z, 0.0F, 0.0F);
        return p;
    }

    /**
     * Reproduit l'action du joueur : pose un Deep Lucky Block porteur de luck=100,
     * puis le casse. `nombre` blocs sont casses, espaces de 20 ticks (1 s).
     * La structure eventuelle est ensuite posee par le mod lui-meme, comme en jeu.
     */
    private static int breakLucky(ServerLevel level, ServerPlayer real, double x, double y, double z, int nombre) {
        ServerPlayer player = fakeOrReal(real, level, x, z);
        try {
            player.setGameMode(GameType.SURVIVAL);
        } catch (Throwable ignored) { }
        for (int i = 0; i < nombre; i++) {
            final int bi = i;
            int delay = bi * 20;
            int px = (int) Math.floor(x) + (bi % 5);
            int pz = (int) Math.floor(z) + (bi / 5);
            int py = (int) Math.floor(y);
            deepluckyblock.procedures.TestProcedure.schedule(level,
                    deepluckyblock.procedures.TestProcedure.currentTick(level) + delay, () -> {
                BlockPos pos = new BlockPos(px, py, pz);
                level.setBlock(pos, DeepLuckyBlockModBlocks.DEEP_LUCKY_BLOCK.get().defaultBlockState(), 3);
                if (level.getBlockEntity(pos) instanceof DeepLuckyBlockBlockEntity be) {
                    be.setLuck(100);
                    be.setSlbLuckBonus(100);
                    be.setChanged();
                }
                DeepLuckyBlockMod.LOGGER.info("[DLBTEST] casse du lucky block {} (luck=100, n°{}/{})",
                        pos.toShortString(), bi + 1, nombre);
                player.moveTo(px + 0.5, py, pz + 0.5, 0.0F, 0.0F);
                BlockState state = level.getBlockState(pos);
                try {
                    // Meme chemin qu'un clic gauche de joueur : playerDestroy/onDestroyedByPlayer.
                    player.gameMode.destroyBlock(pos);
                } catch (Throwable t) {
                    DeepLuckyBlockMod.LOGGER.error("[DLBTEST] echec destroyBlock {}", pos, t);
                }
                if (!level.getBlockState(pos).equals(state)) {
                    DeepLuckyBlockMod.LOGGER.info("[DLBTEST] bloc casse OK {}", pos.toShortString());
                }
            });
        }
        return nombre;
    }

    // ------------------------------------------------------------------
    // T17 : eau -- gel / degel manuels (section 39 : /opti freeze, /opti unfreeze)
    // ------------------------------------------------------------------

    /** /dlbtest water status -- etat du gel d'eau sur le niveau courant. */
    private static int waterStatus(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        boolean frozen = deepluckyblock.util.WaterDam.isFrozen(level);
        int n = deepluckyblock.util.WaterDam.frozenCount(level);
        String msg = "[DLB-WATER] etat : gel " + (frozen ? "ACTIF" : "inactif")
                + ", " + n + " bloc(s) d'eau memorises (futurs restaures)";
        DeepLuckyBlockMod.LOGGER.info(msg);
        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
        return n;
    }

    /** /dlbtest water freeze [rayon] -- gele l'eau autour de l'executant. */
    private static int waterFreeze(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx, int rayon) {
        ServerLevel level = ctx.getSource().getLevel();
        net.minecraft.world.phys.Vec3 p = ctx.getSource().getPosition();
        int cx = net.minecraft.util.Mth.floor(p.x), cz = net.minecraft.util.Mth.floor(p.z);
        DeepLuckyBlockMod.LOGGER.info("[DLBTEST] water freeze rayon={} centre={},{}", rayon, cx, cz);
        deepluckyblock.util.WaterDam.freeze(level, cx - rayon, cz - rayon, cx + rayon, cz + rayon, () -> {
            DeepLuckyBlockMod.LOGGER.info("[DLBTEST] water freeze TERMINE : {} bloc(s) d'eau geles",
                    deepluckyblock.util.WaterDam.frozenCount(level));
            ctx.getSource().sendSuccess(() -> Component.literal("[DLB-WATER] gel manuel termine : "
                    + deepluckyblock.util.WaterDam.frozenCount(level) + " bloc(s)"), false);
        });
        return 1;
    }

    /**
     * /dlbtest water thaw -- restitue l'eau gelee (aucune emprise exclue : la
     * commande manuelle n'est pas rattachee a une structure).
     */
    private static int waterThaw(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        if (!deepluckyblock.util.WaterDam.isFrozen(level)) {
            ctx.getSource().sendSuccess(() -> Component.literal("[DLB-WATER] rien a degeler (aucun gel actif)"), false);
            return 0;
        }
        DeepLuckyBlockMod.LOGGER.info("[DLBTEST] water thaw demande ({} bloc(s) geles)",
                deepluckyblock.util.WaterDam.frozenCount(level));
        deepluckyblock.util.WaterDam.thaw(level, null, null, () -> {
            DeepLuckyBlockMod.LOGGER.info("[DLBTEST] water thaw TERMINE");
            ctx.getSource().sendSuccess(() -> Component.literal("[DLB-WATER] degel manuel termine"), false);
        });
        return 1;
    }
}
