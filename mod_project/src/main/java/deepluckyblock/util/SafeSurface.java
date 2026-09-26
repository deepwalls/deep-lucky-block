package deepluckyblock.util;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * LECTURE FIABLE DU TERRAIN -- CAUSE RACINE UNIQUE des bugs de placement.
 *
 * <p>PROBLEME (constate en jeu ET reproduit en test dedie) : les chunks livres
 * par le chunk source (statut FULL) arrivent avec une HEIGHTMAP NON
 * INITIALISEE -- ou une heightmap dont la donnee est restee a sa valeur par
 * defaut. Minecraft repond alors a {@code height(level, ...)} par sa
 * sentinelle "aucune donnee" = {@code minBuildHeight} (-64 dans l'overworld),
 * donc :
 *
 * <ul>
 *   <li>ancrage des structures a Y = -65 / -78 / -90 (structure enterree) ;</li>
 *   <li>"deltaY anormal apres smooth (-65 -> 71, brut=136)" : la mesure AVANT
 *       smooth etait fausse, celle d'apres (terrain reellement construit) est
 *       juste ;</li>
 *   <li>"[STRUCT5-FLAT] Aucune zone valide trouvee" : TOUS les candidats
 *       paraissent "plats, pente 0" avant generation et re-mesurent pareil
 *       apres, la verification ne peut jamais valider un terrain reel ;</li>
 *   <li>"despeckle : 25469 colonnes de heightmap corrigees" : 83 % de la zone
 *       lue a -64, le pipeline de terrain travaillait donc sur une carte
 *       fantome.</li>
 * </ul>
 *
 * <p>CORRECTIF : avant toute mesure, on force la generation du chunk
 * (statut FULL, comme le fait deja le paste) PUIS, si sa heightmap repond la
 * sentinelle, on la RECALCULE avec l'API vanilla prevue pour ca,
 * {@code Heightmap.primeHeightmaps} -- exactement ce que fait la generation
 * normale. Le recalcul n'a lieu que lorsque la valeur lue est impossible, donc
 * le cout est nul dans le cas normal (un chunk bien genere n'est jamais
 * recalcule).
 *
 * <p>Ce que ce n'est PAS : une correction de terrain. Rien n'est modifie en jeu,
 * on ne fait que LIRE la vraie hauteur du sol (et remettre la heightmap du
 * chunk en etat, ce que le jeu aurait fait tout seul).
 */
public final class SafeSurface {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Les trois heightmaps lues quelque part dans le mod. */
    private static final Set<Heightmap.Types> TYPES = Set.of(
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.WORLD_SURFACE);

    /** Aucune surface reelle n'existe au niveau du bedrock : en dessous = lecture fantome. */
    private static int floorOf(ServerLevel level) {
        return level.getMinBuildHeight() + 8;
    }

    private static long primedChunks = 0L;
    private static long scannedColumns = 0L;
    private static long repairedZones = 0L;

    // ==================================================================
    // T53 : CACHE DE CHUNKS -- « opti ultime » sur la lecture du terrain
    // ==================================================================
    /**
     * POURQUOI : les passes de terrain lisent le sol COLONNE PAR COLONNE (jusqu'a 150 000
     * colonnes par passe, une dizaine de passes). Chaque lecture refaisait une recherche
     * complete dans le chunk source (~2 millions de recherches par structure : le meme chunk
     * est re-resolu des dizaines de milliers de fois), et surtout `height(level, type, x, z)`
     * passe par `Level.getChunk(x >> 4, z >> 4, FULL, true)` : une seule colonne tombant dans
     * un chunk absent declenchait ~15 s de generation SYNCHRONE sur un monde neuf (meme tueur
     * que T49, reste dans les lectures de hauteur).
     *
     * <p>MECANISME : cache direct-mapped de 256 entrees. Une entree n'est ecrite QUE sur une
     * reponse POSITIVE : une detection (« ce chunk est-il arrive ? ») part donc toujours a la
     * source, et rien ne peut rester bloque sur une reponse perimee. {@link #height} remplace
     * `level.getHeight` en tenant la meme promesse de non-blocage : chunk absent -> sentinelle
     * « pas de donnee » (minBuildHeight), exactement ce que le jeu repond pour une heightmap
     * vide, donc tous les replis existants continuent de fonctionner.
     */
    // T72 : 256 entrees ne suffisaient pas. MESURE (everest, zone de 147 065 colonnes
    // = 576 chunks) : 5 366 134 requetes moteur pour seulement 817 779 lectures servies
    // par le cache (13 %) -- la table direct-mapped etait lessivee a chaque tranche car
    // la zone fait PLUS de chunks que le cache n'a de cases. 4096 entrees couvrent
    // n'importe quelle zone du mod (616 chunks pour le lac) et ne pesent que ~100 Ko.
    private static final int CACHE_SIZE = 4096;
    private static final int CACHE_MASK = CACHE_SIZE - 1;
    private static final ServerLevel[] C_LEVEL = new ServerLevel[CACHE_SIZE];
    private static final long[] C_KEY = new long[CACHE_SIZE];
    private static final ChunkAccess[] C_CHUNK = new ChunkAccess[CACHE_SIZE];
    private static final boolean[] C_VERIFIED = new boolean[CACHE_SIZE];

    /** Statistiques du pipeline (publiees dans [DLB-PERF]). */
    public static long statLookups = 0L, statHits = 0L;

    // T72 : memo « dernier chunk » -- les passes balaient des colonnes CONSECUTIVES,
    // qui tombent 16 fois de suite dans le meme chunk. Meme sous lessivage, ces 16
    // lectures sont desormais servies sans aucune recherche.
    private static ServerLevel lastLevel;
    private static long lastKey = Long.MIN_VALUE;
    private static ChunkAccess lastChunk;

    private static int cacheSlot(int cx, int cz) {
        int h = cx * 0x9E3779B1 ^ (cz * 0x85EBCA6B);
        h ^= h >>> 13;
        return h & CACHE_MASK;
    }

    private static long cacheKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /**
     * Chunk deja resolu, SANS jamais generer (getChunkNow seulement). Reponse positive mise en
     * cache ; un chunk absent est toujours re-verifie a la source.
     */
    public static ChunkAccess cached(ServerLevel level, int cx, int cz) {
        lookups++;
        long key = cacheKey(cx, cz);
        // T72 : 1) proximite immediate (cas de tres loin le plus frequent).
        if (lastLevel == level && lastKey == key && lastChunk != null) {
            statHits++;
            return lastChunk;
        }
        int i = cacheSlot(cx, cz);
        // 2) table direct-mapped.
        if (C_LEVEL[i] == level && C_KEY[i] == key && C_CHUNK[i] != null) {
            statHits++;
            lastLevel = level; lastKey = key; lastChunk = C_CHUNK[i];
            return C_CHUNK[i];
        }
        // 3) source (jamais bloquant : getChunkNow seulement).
        statLookups++;
        ChunkAccess c = null;
        try { c = level.getChunkSource().getChunkNow(cx, cz); } catch (Throwable ignored) { }
        if (c != null) {
            C_LEVEL[i] = level; C_KEY[i] = key; C_CHUNK[i] = c; C_VERIFIED[i] = false;
            lastLevel = level; lastKey = key; lastChunk = c;
        }
        return c;
    }

    /**
     * Equivalent de {@code height(level, type, x, z)} qui ne peut PAS charger de chunk :
     * chunk absent -> demande en tache de fond + sentinelle « pas de donnee ». Meme valeur de
     * retour que le jeu pour une heightmap vide, donc aucun appelant n'a besoin de changer.
     */
    public static int height(ServerLevel level, Heightmap.Types type, int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        long _a = DIAG ? System.nanoTime() : 0L;
        ChunkAccess c = cached(level, cx, cz);
        long _b = DIAG ? System.nanoTime() : 0L;
        if (c == null) {
            request(level, cx, cz);
            softMisses++;
            return level.getMinBuildHeight();
        }
        try {
            // =============================================================
            // T58 : LA CAUSE DES 28 s DU SCORING DE PLACEMENT
            // =============================================================
            // Bytecode vanilla de ChunkAccess.getHeight(type, x, z) :
            //     Heightmap h = this.heightmaps.get(type);
            //     if (h == null) { Heightmap.primeHeightmaps(this, EnumSet.of(type)); h = ...; }
            //     return h.getFirstAvailable(x & 15, z & 15) - 1;
            // Autrement dit : si la heightmap demandee n'a jamais ete primée, CHAQUE lecture
            // rescanne le chunk ENTIER (16x16x384 blocs) -- et, en environnement de dev, logue
            // une erreur a chaque fois. Mesure : 180 us par lecture, soit 28,6 s pour les
            // 160 000 lectures du scoring de placement (1971 candidats x 81 colonnes), le gel
            // constate AVANT l'ouverture de la fenetre d'edition (donc invisible dans
            // [DLB-PERF]).
            // Correctif : on prime UNE FOIS PAR CHUNK. Le test ci-dessous sert de memo :
            // primeHeightmaps remplit la table du chunk, donc l'appel suivant est un simple
            // `heightmaps.get()`.
            if (!c.hasPrimedHeightmap(type)) {
                primedOnDemand++;
                Heightmap.primeHeightmaps(c, java.util.EnumSet.of(type));
                if (primedOnDemand <= 5 || primedOnDemand % 500 == 0) {
                    LOGGER.warn("[DLB-SURFACE] heightmap {} absente sur le chunk {},{} -- primée une fois "
                                    + "(sans ca : rescan complet du chunk a CHAQUE lecture, 180 us mesurees) [#{}]",
                            type, cx, cz, primedOnDemand);
                }
            }
            int r = c.getHeight(type, x & 15, z & 15) + 1;
            if (DIAG) { nCache += _b - _a; nGet += System.nanoTime() - _b; if (++nCalls % 20000 == 0)
                LOGGER.warn("[DLB-DIAG] height() : {} appels, {} ms dans la recherche de chunk, {} ms dans chunk.getHeight, {} ms/chunk, {} ms/lecture",
                        nCalls, nCache / 1_000_000, nGet / 1_000_000,
                        (nGet / 1000) / Math.max(1, lookups), (nGet / 1000) / Math.max(1, nCalls)); }
            return r;
        } catch (Throwable t) { return level.getMinBuildHeight(); }
    }

    /** T58 : diagnostic (lu une fois au chargement de la classe). */
    private static final boolean DIAG = Boolean.getBoolean("dlb.diag");
    private static long nCalls = 0, nCache = 0, nGet = 0, lookups = 0;
    /** T58 : nombre de heightmaps primées a la demande (1 fois par chunk, pas par colonne). */
    public static long primedOnDemand = 0L;

    /**
     * Memo de chunk pour les boucles de POSE : evite une recherche de chunk par bloc
     * (everest : 52 979 blocs, lac : plusieurs millions). Un appelant qui DIFFERE un bloc doit
     * appeler {@link ChunkMemo#reset()} pour forcer une nouvelle verification au tick suivant.
     */
    public static final class ChunkMemo {
        int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
        boolean present;
        public void reset() { cx = Integer.MIN_VALUE; cz = Integer.MIN_VALUE; present = false; }
    }

    /**
     * T56 : etat du bloc SANS jamais generer le chunk (null si le chunk n'est pas en memoire).
     *
     * <p>POURQUOI C'EST INDISPENSABLE : `Level.getBlockState(...)` passe par
     * `Level.getChunk(x, z)` = `getChunk(..., FULL, true)`, donc il GENERAIT le chunk en
     * SYNCHRONE. Mesure en bac a sable : la mesure de qualite des candidats de placement lisait
     * l'etat du bloc au centre de chaque candidat (~2 300 candidats) et generait des chunks un
     * par un -- 28 s de gel AVANT meme l'ouverture de la fenetre d'edition, invisible dans
     * [DLB-PERF] puisque le pipeline n'avait pas commence.
     */
    public static BlockState state(ServerLevel level, int x, int y, int z) {
        ChunkAccess c = cached(level, x >> 4, z >> 4);
        if (c == null) { request(level, x >> 4, z >> 4); softMisses++; return null; }
        try { return c.getBlockState(new BlockPos(x, y, z)); } catch (Throwable t) { return null; }
    }

    /** Vrai si le chunk contenant (x,z) est en memoire, avec memo (1 recherche par chunk). */
    public static boolean present(ServerLevel level, int x, int z, ChunkMemo memo) {
        int cx = x >> 4, cz = z >> 4;
        if (cx == memo.cx && cz == memo.cz) return memo.present;
        memo.cx = cx; memo.cz = cz;
        memo.present = cached(level, cx, cz) != null;
        return memo.present;
    }

    /** Vide le cache (debut et fin de pipeline : aucune reference gardee entre deux structures). */
    public static void clearCache() {
        lastLevel = null; lastKey = Long.MIN_VALUE; lastChunk = null;
        java.util.Arrays.fill(C_CHUNK, null);
        java.util.Arrays.fill(C_LEVEL, null);
        java.util.Arrays.fill(C_KEY, 0L);
        java.util.Arrays.fill(C_VERIFIED, false);
    }

    /** Bilan lisible, publie dans [DLB-PERF]. */
    public static String statReport() {
        long total = statHits + statLookups;
        if (total == 0 && softMisses == 0) return "";
        return String.format(
                "acces terrain : %d requete(s) moteur, %d lecture(s) servies par le cache (%.0f%%), %d colonne(s) hors memoire",
                statLookups, statHits, total == 0 ? 0.0 : 100.0 * statHits / total, softMisses);
    }

    private SafeSurface() {}

    /**
     * Force la generation complete du chunk contenant (x,z) et garantit que sa
     * heightmap est utilisable. Retourne le chunk, ou null en cas d'echec.
     */
    public static ChunkAccess primeChunk(ServerLevel level, int x, int z) {
        // T49 : plus JAMAIS de generation synchrone ici. Cette methode est appelee
        // par surfaceY() pour CHAQUE colonne de terrain : bloquer une seule fois
        // par chunk (jusqu'a ~15 s mesurees en jeu sur un monde neuf) figeait le
        // serveur, et rendait caducs tous les budgets de temps (un budget teste
        // ENTRE deux appels ne peut rien contre un appel qui bloque 15 s).
        return primeChunkSoft(level, x >> 4, z >> 4);
    }

    // ==================================================================
    // T49 : OUTILS NON BLOQUANTS (zero generation synchrone)
    // ==================================================================

    /** Vrai si le chunk est deja en memoire (aucune generation declenchee). */
    public static boolean loaded(ServerLevel level, int cx, int cz) {
        // T53 : via le cache (reponses positives memorisees, un chunk absent reste
        // re-verifie a la source : les boucles de detection ne peuvent pas etre trompees).
        try { return cached(level, cx, cz) != null; } catch (Throwable t) { return false; }
    }

    // ==================================================================
    // T60 : REGISTRE DES CHUNKS PRETS (lecture STRICTEMENT non bloquante)
    // ==================================================================
    /**
     * POURQUOI : `ChunkSource.getChunkNow()` n'est pas garanti instantane. Quand le chunk
     * demande est EN COURS de generation (future en vol), il attend la fin de cette generation
     * via `managedBlock` -- mesure : 7,7 ms de moyenne par lecture sur les 2 209 cellules de la
     * grille de scoring, soit 17 s de gel dans un seul tick. Or, pour un CLASSEMENT de zones
     * candidates, attendre n'apporte rien.
     *
     * <p>On tient donc un registre : un chunk demande part en PENDING, et le callback de
     * `getChunkFuture` le bascule en READY. {@link #chunkFor} ne touche JAMAIS au chunk system
     * pour un chunk PENDING : il rend null (le candidat sera simplement note sur moins de
     * cellules). Resultat : le scoring ne peut plus bloquer, quel que soit l'etat du chunk
     * system.
     */
    private static final java.util.Set<Long> READY = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<Long> PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static long chunkKey(int cx, int cz) { return (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL); }

    // In 1.21.1 getChunkFuture calls managedBlock when invoked on the server thread.
    // Its off-thread branch instead dispatches getChunkFutureMainThread without waiting.
    // Only that thread-safe request API runs here; all chunk reads and callbacks return
    // to the server thread. One daemon dispatches requests; no thread per chunk.
    private static final java.util.concurrent.Executor CHUNK_REQUESTS =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "DLB-Chunk-Requests");
                thread.setDaemon(true);
                return thread;
            });

    private static final net.minecraft.server.level.TicketType<Long> ASYNC_TICKET =
            net.minecraft.server.level.TicketType.create("dlb_async_request", Long::compare, 300);
    private static long nextRequestTicket;

    private static void requestAsync(ServerLevel level, int cx, int cz, long key, Runnable whenDone) {
        var chunkPos = new net.minecraft.world.level.ChunkPos(cx, cz);
        long ticket = ++nextRequestTicket;
        // UNKNOWN tickets installed by getChunkFuture expire between ticks. Keep this
        // request alive until the normal chunk holder has published its FULL state.
        level.getChunkSource().addRegionTicket(ASYNC_TICKET, chunkPos, 0, ticket);
        java.util.concurrent.CompletableFuture.supplyAsync(
                () -> level.getChunkSource().getChunkFuture(cx, cz, ChunkStatus.FULL, true), CHUNK_REQUESTS)
                .thenCompose(future -> future)
                .whenComplete((result, error) -> level.getServer().execute(() ->
                        deepluckyblock.procedures.TestProcedure.schedule(level,
                                deepluckyblock.procedures.TestProcedure.currentTick(level) + 1, () -> {
                    try {
                        if (error == null && level.getChunkSource().getChunkNow(cx, cz) != null) READY.add(key);
                        else READY.remove(key);
                    } finally {
                        PENDING.remove(key);
                        // Give ChunkKeeper a tick to take over, without sharing its ticket key.
                        deepluckyblock.procedures.TestProcedure.schedule(level,
                                deepluckyblock.procedures.TestProcedure.currentTick(level) + 2,
                                () -> level.getChunkSource().removeRegionTicket(ASYNC_TICKET, chunkPos, 0, ticket));
                        whenDone.run();
                    }
                })));
    }

    /** Demande la generation d'un chunk en TACHE DE FOND (jamais bloquant). */
    public static void request(ServerLevel level, int cx, int cz) {
        long k = chunkKey(cx, cz);
        if (READY.contains(k) && level.getChunkSource().getChunkNow(cx, cz) != null) return;
        READY.remove(k);
        if (!PENDING.add(k)) return;
        requestAsync(level, cx, cz, k, () -> {});
    }

    /** T60 : comme {@link #request}, mais previent a la fin (compteurs d'avancement). */
    public static void requestThen(ServerLevel level, int cx, int cz, Runnable whenDone) {
        long k = chunkKey(cx, cz);
        // T65 : le callback doit etre appele EXACTEMENT UNE FOIS par appel. L'oublier sur les
        // sorties anticipees faisait fuir le compteur d'appels en vol de ChunkKeeper : au bout
        // de MAX_IN_FLIGHT fuites, le debit tombait a zero et PLUS AUCUNE demande n'etait
        // emise (48 chunks jamais charges, pipeline bloque en attente de sa zone -- constate en
        // test : les 48 derniers chunks d'une zone de 144).
        // T66 : on ne fait plus confiance au registre READY ici. Un chunk peut avoir ete
        // decharge entre-temps (il n'est pas encore epingle a ce stade) : croire le registre
        // faisait sauter la demande, le chunk ne revenait jamais, et le pipeline attendait sa
        // zone indefiniment (constate : les 48 derniers chunks d'une zone de 144).
        if (!PENDING.add(k)) { whenDone.run(); return; }   // deja en vol : ce demandeur n'attend rien
        requestAsync(level, cx, cz, k, whenDone);
    }

    /**
     * T66 : re-demande FORCEE. Utilisee quand un chunk reste absent alors qu'il a deja ete
     * demande (future perdu, chunk decharge entre-temps, ticket non applique). Sans ca, un
     * chunk peut rester indefiniment absent et bloquer la zone : c'est exactement ce qui
     * arrivait aux 48 derniers chunks d'une zone de 144.
     */
    public static void reRequest(ServerLevel level, int cx, int cz) {
        long k = chunkKey(cx, cz);
        READY.remove(k);
        // Do not duplicate a live future; completion clears failed requests for retry.
        request(level, cx, cz);
    }

    /** Vrai si le chunk est pret SANS jamais attendre (registre d'abord, source ensuite). */
    public static boolean readyNow(ServerLevel level, int cx, int cz) {
        long k = chunkKey(cx, cz);
        if (READY.contains(k)) return true;
        if (PENDING.contains(k)) return false;
        if (cached(level, cx, cz) != null) { READY.add(k); return true; }
        return false;
    }

    /**
     * Chunk pret, ou null -- sans JAMAIS attendre : un chunk en cours de generation n'est pas
     * interroge (c'est `getChunkNow` qui attendait, 7,7 ms mesures par lecture).
     */
    /** T62 : compteurs de diagnostic de la lecture non bloquante. */
    public static long diagChunkCalls = 0L, diagChunkNs = 0L, diagChunkWaits = 0L;

    public static ChunkAccess chunkFor(ServerLevel level, int cx, int cz) {
        long k = chunkKey(cx, cz);
        long _t0 = System.nanoTime();
        // 1) chunk EN COURS de generation : on ne l'interroge PAS (c'est l'appel qui attendait).
        if (!READY.contains(k) && PENDING.contains(k)) { softMisses++; return null; }
        // 2) chunk pret (ou jamais demande) : une seule interrogation de la source, qui ne peut
        //    pas attendre dans le cas "deja charge" et qui est le seul moyen de decouvrir les
        //    chunks charges par le jeu lui-meme (autour du joueur).
        ChunkAccess c = cached(level, cx, cz);
        long _dt = System.nanoTime() - _t0;
        diagChunkCalls++;
        diagChunkNs += _dt;
        if (_dt > 2_000_000L) diagChunkWaits++;   // > 2 ms : la source a attendu
        if (c != null) { READY.add(k); return c; }
        READY.remove(k);
        // T63 : AUCUNE demande de chargement ici. Mesure : 600 demandes de chunk depuis la
        // boucle de scoring = 17 s de gel dans un seul tick (chaque getChunkFuture(create=true)
        // cree un ticket et declenche la mise en file du chunk), alors que le CLASSEMENT des
        // zones candidates n'a pas besoin de charger quoi que ce soit : il travaille sur ce qui
        // est deja en memoire. Le chargement reste fait par le pre-chauffage et par la fenetre
        // de pre-chargement du pipeline (T46/T47), qui, eux, sont bornes et etales.
        softMisses++;
        return null;
    }

    /**
     * T61 : hauteur de surface SANS heightmap, par balayage borne des blocs du chunk.
     *
     * <p>POURQUOI : meme avec la heightmap primée, `ChunkAccess.getHeight()` coute 8,2 ms par
     * appel dans cet environnement (mesure sur 2 209 cellules : 18 s de gel dans un seul tick).
     * La heightmap passe par une abstraction BitStorage + un scan de section ; une lecture
     * directe {@code chunk.getBlockState()} est un simple acces de tableau dans une section
     * deja en memoire. Le balayage part de la DERNIERE section remplie
     * ({@code getHighestFilledSectionIndex()}) : il descend donc de quelques blocs seulement au
     * lieu de 384.
     *
     * <p>Aucune generation de chunk, aucune heightmap, aucune attente : c'est une lecture pure
     * de donnees en memoire. Utilise pour la GRILLE DE SCORING du placement (et comme secours
     * quand la heightmap est inutilisable).
     */
    public static int surfaceScan(ChunkAccess c, ServerLevel level, int x, int z) {
        return surfaceScanEx(c, level, x, z, null);
    }

    /**
     * T75 : comme {@link #surfaceScan} (cimes exclues) mais signale en plus si le
     * bloc de surface est un LIQUIDE.
     *
     * <p>POURQUOI : un lac / un ocean est PARFAITEMENT PLAT -- il gagnait donc tous
     * les scorings de « zone plate » (flat=100 %, pente=0), la structure etait placee
     * au milieu de l'eau, puis « [STRUCT5-SAFETY] eau=100 % -> recherche alt... »
     * l'envoyait ailleurs avec un ancrage fantaisiste. Le drapeau permet de refuser
     * ces candidats AVANT de choisir.
     *
     * @param wetOut si non nul, wetOut[0] recoit « la surface est un liquide »
     */
    public static int surfaceScanEx(ChunkAccess c, ServerLevel level, int x, int z, boolean[] wetOut) {
        if (wetOut != null) wetOut[0] = false;
        try {
            int top = level.getMaxBuildHeight() - 1;
            int hi = c.getHighestFilledSectionIndex();
            if (hi >= 0) top = Math.min(top, (hi + 1) << 4);
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int y = top; y > level.getMinBuildHeight(); y--) {
                BlockState s = c.getBlockState(m.set(x, y, z));
                if (s.isAir() || isCanopy(s)) continue;   // T75 : ni air, ni cime d'arbre
                if (s.blocksMotion() || !s.getFluidState().isEmpty()) {
                    if (wetOut != null) wetOut[0] = !s.getFluidState().isEmpty();
                    return y;
                }
            }
        } catch (Throwable ignored) { }
        return Integer.MIN_VALUE;
    }

    /** Lecture de hauteur sur un chunk DEJA obtenu (aucune recherche, aucune attente). */
    public static int heightOf(ChunkAccess c, int x, int z) {
        try {
            if (!c.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)) {
                primedOnDemand++;
                Heightmap.primeHeightmaps(c, java.util.EnumSet.of(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
            }
            return c.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        } catch (Throwable t) { return Integer.MIN_VALUE; }
    }

    /** Vrai si TOUTE la zone de chunks est deja en memoire. */
    public static boolean zoneLoaded(ServerLevel level, int cx0, int cz0, int cx1, int cz1) {
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++)
                if (!loaded(level, cx, cz)) return false;
        return true;
    }

    /** Demande (tache de fond) tous les chunks manquants d'une zone. */
    public static void requestZone(ServerLevel level, int cx0, int cz0, int cx1, int cz1) {
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++)
                if (!loaded(level, cx, cz)) request(level, cx, cz);
    }

    /**
     * T49 : variante NON BLOQUANTE de {@link #primeChunkAbs}.
     *
     * <p>Chunk en memoire : heightmap recalculee si necessaire, chunk renvoye.
     * Chunk absent : la generation est demandee en tache de fond et la methode
     * renvoie {@code null} (l'appelant doit alors sauter la colonne, jamais
     * attendre). C'est ce qui rend les passes de terrain insensibles a la
     * lenteur du chunk system.
     */
    public static ChunkAccess primeChunkSoft(ServerLevel level, int cx, int cz) {
        try {
            ChunkAccess chunk = cached(level, cx, cz);      // T53 : cache (une recherche par chunk)
            if (chunk == null) { request(level, cx, cz); softMisses++; return null; }
            // T53 : la verification de la heightmap (et son eventuel recalcul) n'a lieu
            // qu'UNE FOIS PAR CHUNK, plus une fois par colonne : c'etait 65 536 fois le meme
            // test pour un chunk de 16x16 lu colonne par colonne.
            int i = cacheSlot(cx, cz);
            if (!C_VERIFIED[i] && C_CHUNK[i] == chunk) {
                C_VERIFIED[i] = true;
                if (isReallyGenerated(chunk) && needsRepair(level, chunk)) {
                    Heightmap.primeHeightmaps(chunk, TYPES);
                    primedChunks++;
                    if (primedChunks <= 5 || primedChunks % 500 == 0) {
                        LOGGER.warn("[DLB-SURFACE] heightmap inutilisable sur le chunk {},{} -- recalculee "
                                        + "(sans ca, toutes les hauteurs de ce chunk valent -65 et les structures s'ancrent sous le sol). [#{}]",
                                cx, cz, primedChunks);
                    }
                }
            }
            return chunk;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Nombre de colonnes lues sur un chunk pas encore en memoire depuis T49
     * (compteur de diagnostic : si ce nombre grimpe, le pipeline tourne sur une
     * zone insuffisamment pre-chargee, ce qui est un probleme de PRE-CHARGEMENT,
     * jamais de generation synchrone).
     */
    public static long softMisses;

    /**
     * Variante BLOQUANTE (generation synchrone du chunk). T52 : plus aucun appelant
     * dans le pipeline -- conservee uniquement pour un diagnostic manuel
     * (-Ddlb.sync=1) ou une commande. Ne pas l'utiliser dans un chemin chaud.
     */
    public static ChunkAccess primeChunkAbs(ServerLevel level, int cx, int cz) {
        try {
            ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
            if (chunk == null) return null;
            // GARDE-FOU : on ne recalcule QUE si le chunk est REELLEMENT genere.
            // Le chunk source peut rendre un chunk encore en cours de chargement
            // (champ "currentlyLoading") : recalculer sa heightmap a cet instant
            // figerait une carte VIDE (et, pire, la marquerait comme valide).
            if (!isReallyGenerated(chunk)) return chunk;
            if (needsRepair(level, chunk)) {
                Heightmap.primeHeightmaps(chunk, TYPES);
                primedChunks++;
                if (primedChunks <= 5 || primedChunks % 500 == 0) {
                    LOGGER.warn("[DLB-SURFACE] heightmap inutilisable sur le chunk {},{} -- recalculee "
                                    + "(sans ca, toutes les hauteurs de ce chunk valent -65 et les structures s'ancrent sous le sol). [#{}]",
                            cx, cz, primedChunks);
                }
            }
            return chunk;
        } catch (Throwable t) {
            LOGGER.warn("[DLB-SURFACE] chunk {},{} : generation/initialisation impossible : {}", cx, cz, t.toString());
            return null;
        }
    }

    /**
     * Vrai si la heightmap de ce chunk est inutilisable : soit elle n'est pas
     * initialisee, soit elle renvoie la sentinelle (minBuildHeight) la ou le
     * monde a forcement du sol. On teste trois colonnes : un chunk entierement
     * vide est un vrai cas limite, trois colonnes vides ne le sont pas.
     */
    private static boolean needsRepair(ServerLevel level, ChunkAccess chunk) {
        try {
            if (!chunk.hasPrimedHeightmap(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES)) return true;
            int min = level.getMinBuildHeight();
            int[][] probes = {{2, 2}, {8, 8}, {13, 13}};
            for (int[] p : probes) {
                if (chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p[0], p[1]) - 1 > min) return false;
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Vrai si le chunk (coordonnees de CHUNK) est deja en memoire et genere.
     * ATTENTION a l'unite : cette methode prend des coordonnees de chunk, pas
     * des coordonnees de bloc (utiliser {@link #isLoadedAt} dans ce cas).
     */
    public static boolean isLoaded(ServerLevel level, int cx, int cz) {
        ChunkAccess chunk = cached(level, cx, cz);          // T53 : cache
        return chunk != null && isReallyGenerated(chunk);
    }

    /** Vrai si le chunk CONTENANT ce bloc est deja en memoire et genere. */
    public static boolean isLoadedAt(ServerLevel level, int x, int z) {
        return isLoaded(level, x >> 4, z >> 4);
    }

    /** Vrai si le chunk a bien ete genere jusqu'au bout (et non juste alloue). */
    public static boolean isReallyGenerated(ChunkAccess chunk) {
        try {
            return chunk.getHighestGeneratedStatus().isOrAfter(ChunkStatus.FULL);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Y du premier bloc "sol ou fluide" en descendant (secours ultime).
     *
     * <p>T55 : si le chunk n'est PAS en memoire, on ne scanne PAS et on rend la sentinelle.
     * Ce scan appelle `getBlockState` sur jusqu'a 384 blocs de haut ; or `getBlockState`
     * passe par `Level.getChunk(x, z)` = `getChunk(..., FULL, true)`, donc il GENERAIT le
     * chunk en SYNCHRONE. Mesure en bac a sable : c'etait le dernier gel du mod (28 s au
     * demarrage d'une structure, juste avant l'ouverture de la fenetre d'edition, cause par
     * la mesure de qualite des candidats de placement sur une zone non encore generee).
     */
    /** T75 : une CIME d'arbre n'est pas « le sol ». Vanilla (MOTION_BLOCKING_NO_LEAVES)
     *  saute les feuilles mais PAS les TRONCS : sur une colonne de foret, la hauteur
     *  rendue est celle de la cime -- soit 7 a 26 blocs au-dessus du sol. C'est
     *  exactement l'ecart mesure en jeu entre baseY (69 / 88) et le sol reel (62). */
    private static boolean isCanopy(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.LEAVES)
                || s.is(net.minecraft.tags.BlockTags.LOGS)
                || s.is(net.minecraft.world.level.block.Blocks.BAMBOO);
    }

    /**
     * T75 : SURFACE REELLE d'une colonne, telle que doit la lire l'ANALYSE DE ZONE :
     * le plus haut bloc qui n'est ni de l'air, ni une feuille, ni du BOIS (cime),
     * les LIQUIDES restant INCLUS (c'est precisement l'eau/lave qu'on veut voir).
     * Lecture NON BLOQUANTE : chunk absent -> sentinelle, jamais de generation.
     *
     * @return Integer.MIN_VALUE si le chunk n'est pas en memoire
     */
    public static int surfaceMaterial(ServerLevel level, int x, int z) {
        if (cached(level, x >> 4, z >> 4) == null) { request(level, x >> 4, z >> 4); softMisses++; return Integer.MIN_VALUE; }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = level.getMaxBuildHeight() - 1; y > level.getMinBuildHeight(); y--) {
            m.set(x, y, z);
            BlockState s = level.getBlockState(m);
            if (s.isAir() || isCanopy(s)) continue;
            if (s.blocksMotion() || !s.getFluidState().isEmpty()) return y;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * T75 : hauteur du SOL SOLIDE (ni air, ni cime, ni liquide) pour l'ANCRAGE d'une
     * structure. C'est LA mesure qui decide ou la structure touche le terrain : elle
     * ne doit jamais pouvoir tomber sur une cime d'arbre (structure 26 blocs dans le
     * ciel) ni sur une surface d'eau (structure posee sur un lac).
     *
     * @param fallbackY Y rendu si la colonne est illisible (chunk pas encore charge)
     */
    public static int groundY(ServerLevel level, int x, int z, int fallbackY) {
        int cx = x >> 4, cz = z >> 4;
        net.minecraft.world.level.chunk.ChunkAccess c = cached(level, cx, cz);
        if (c == null) { request(level, cx, cz); softMisses++; return fallbackY; }
        // T74 : la heightmap ne sert QUE de borne haute (elle inclut les cimes) et
        // n'est jamais la valeur rendue : le sol est lu BLOC PAR BLOC, en descendant
        // depuis cette borne. Aucune generation declenchee, lecture bornee.
        int top = height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (top <= level.getMinBuildHeight()) top = fallbackY + 40;
        int y0 = Math.min(level.getMaxBuildHeight() - 1, Math.max(top, fallbackY) + 1);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = y0; y > level.getMinBuildHeight() && y > y0 - 96; y--) {
            BlockState s = c.getBlockState(m.set(x, y, z));
            if (s.isAir() || isCanopy(s) || !s.getFluidState().isEmpty()) continue;
            if (s.blocksMotion()) return y;
        }
        return fallbackY;
    }

    /** T75 : vrai si la colonne est NOYEE (liquide au-dessus du sol) : on n'y
     *  plante ni arbre ni decor -- consigne utilisateur « placer les plantes,
     *  arbres et schematics sur le terrain seulement apres tous les fixwater,
     *  sinon ils se retrouvent sous l'eau ». */
    public static boolean isSubmerged(ServerLevel level, int x, int z) {
        int mat = surfaceMaterial(level, x, z);
        if (mat == Integer.MIN_VALUE) return false;
        BlockState s = state(level, x, mat, z);
        return s != null && !s.getFluidState().isEmpty();
    }

    /**
     * T75 : statistiques du SOL SOLIDE d'une emprise, echantillonnee tous les
     * {@code step} blocs : {MEDIANE, min, max, nb colonnes mesurees}.
     *
     * <p>POURQUOI LA MEDIANE : l'ancrage lisait UNE colonne (le centre). Un pic de
     * 2x2 blocs, un reste de pilier ou une cime sur cette seule colonne suffisait a
     * decaler la structure de plusieurs blocs dans le vide. La mediane de toute
     * l'emprise est insensible a ces accidents isoles.
     */
    public static int[] groundStats(ServerLevel level, int x0, int z0, int x1, int z1, int step, int fromY, int fallbackY) {
        int minX = Math.min(x0, x1), maxX = Math.max(x0, x1), minZ = Math.min(z0, z1), maxZ = Math.max(z0, z1);
        int st = Math.max(1, step);
        int[] tmp = new int[8192];
        int n = 0;
        for (int x = minX; x <= maxX && n < tmp.length; x += st) {
            for (int z = minZ; z <= maxZ && n < tmp.length; z += st) {
                int y = groundY(level, x, z, fromY);   // T74 : borne de depart = sol attendu
                if (y != fromY) tmp[n++] = y;
            }
        }
        if (n == 0) return new int[]{fallbackY, fallbackY, fallbackY, 0};
        int[] v = java.util.Arrays.copyOf(tmp, n);
        java.util.Arrays.sort(v);
        return new int[]{v[n / 2], v[0], v[n - 1], n};
    }

    private static int scanGroundY(ServerLevel level, int x, int z) {
        // T75 : delegue a surfaceMaterial (meme regle, cimes exclues) -- une seule
        // implementation de « qu'est-ce que la surface d'une colonne ».
        return surfaceMaterial(level, x, z);
    }

    /**
     * Hauteur de la surface en (x,z), garantie REELLE : chunk force en FULL et
     * heightmap initialisee avant lecture, avec lecture bloc par bloc en
     * dernier recours si la heightmap reste vide (ne devrait plus arriver).
     *
     * @return l'Y du bloc de surface (eau comprise, comme MOTION_BLOCKING)
     */
    public static int surfaceY(ServerLevel level, int x, int z) {
        primeChunk(level, x, z);
        int y = height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;   // T53 : sans generation possible
        if (y > level.getMinBuildHeight()) return y;

        scannedColumns++;
        // T55 : chunk absent -> scanGroundY rend la sentinelle SANS generer le chunk ;
        // les appelants retombent sur leur valeur de repli (jamais de blocage).
        int real = scanGroundY(level, x, z);
        if (real != Integer.MIN_VALUE) {
            if (scannedColumns <= 5 || scannedColumns % 100 == 0) {
                LOGGER.warn("[DLB-SURFACE] heightmap toujours vide en {},{} apres recalcule : rien a l'emplacement "
                                + "indique par la heightmap -- hauteur REELLE lue bloc par bloc : {}. [#{}]", x, z, real, scannedColumns);
            }
            return real;
        }
        LOGGER.error("[DLB-SURFACE] colonne illisible en {},{} : chunk pas encore genere (heightmap et blocs vides) -- "
                + "mesure impossible, l'appelant doit garder sa valeur de repli", x, z);
        return Integer.MIN_VALUE;
    }

    /**
     * Comme {@link #surfaceY}, mais ne renvoie JAMAIS une valeur inutilisable :
     * si la mesure est impossible (chunk pas encore genere), renvoie
     * {@code fallbackY} en le signalant clairement dans les logs. C'est la
     * variante a utiliser pour les ANCRAGES (jamais -65).
     */
    public static int surfaceY(ServerLevel level, int x, int z, int fallbackY) {
        int y = surfaceY(level, x, z);
        if (y == Integer.MIN_VALUE || y <= level.getMinBuildHeight()) {
            LOGGER.warn("[DLB-SURFACE] ancrage {},{} : mesure impossible -- repli sur Y={} (au lieu de -65, ce qui "
                    + "enterrerait la structure)", x, z, fallbackY);
            return fallbackY;
        }
        return y;
    }

    /**
     * Vrai si la hauteur de (x,z) est mesurable MAINTENANT : chunk charge,
     * genere, et heightmap utilisable (apres recalcule eventuel). Les appelants
     * qui doivent ATTENDRE plutot que de mesurer une valeur fantome utilisent
     * ce test pour reporter leur travail d'un tick (voir prepZone).
     */
    public static boolean surfaceReliable(ServerLevel level, int x, int z) {
        ChunkAccess chunk = primeChunk(level, x, z);
        if (chunk == null || !isReallyGenerated(chunk)) return false;
        if (height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1 > level.getMinBuildHeight()) return true;
        return scanGroundY(level, x, z) != Integer.MIN_VALUE;   // T55 : scan impossible sur chunk absent
    }

    /**
     * Capture des hauteurs AVANT smooth sur toute une zone, en soignant chaque
     * chunk (voir primeChunkAbs) une seule fois par chunk. Remplace les boucles
     * {@code height(level, ...)} qui lisaient 83 % de colonnes fantomes.
     *
     * @return tableau [w][h] des hauteurs (index 0 = x0, index 1 = z0)
     */
    public static int[][] captureHeightmap(ServerLevel level, int x0, int z0, int w, int h) {
        int[][] out = new int[w][h];
        Set<Long> done = new HashSet<>();
        int floor = floorOf(level);
        int bad = 0;
        for (int i = 0; i < w; i++) {
            int x = x0 + i;
            for (int j = 0; j < h; j++) {
                int z = z0 + j;
                long key = ((long) (x >> 4) << 32) ^ (z >> 4);
                // T49 : non bloquant (demande en tache de fond si absent).
                if (done.add(key)) primeChunkSoft(level, x >> 4, z >> 4);
                int v = height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                if (v < floor) bad++;
                out[i][j] = v;
            }
        }
        if (bad > 0) {
            repairedZones++;
            LOGGER.warn("[DLB-SURFACE] capture de zone {}x{} : {} colonnes sur {} restent illisibles apres recalcule "
                            + "(le despeckle de la heightmap les remplace par la mediane des voisins). [#{}]",
                    w, h, bad, w * h, repairedZones);
        }
        return out;
    }

    /**
     * Median des hauteurs echantillonnees sur un carre centre en (cx,cz).
     * Utilisable APRES le pre-chargement de la zone (hauteurs alors reelles).
     * Ne force aucune generation : c'est un outil de mesure de confort, pas
     * d'ancrage.
     */
    public static int medianSurfaceY(ServerLevel level, int cx, int cz, int half, int step) {
        if (step < 1) step = 1;
        if (half < 0) half = 0;
        int floor = level.getMinBuildHeight();
        int[] values = new int[((2 * half) / step + 1) * ((2 * half) / step + 1)];
        int n = 0;
        for (int dx = -half; dx <= half; dx += step) {
            for (int dz = -half; dz <= half; dz += step) {
                int y = height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx + dx, cz + dz) - 1;
                if (y <= floor) continue;
                values[n++] = y;
            }
        }
        if (n == 0) return Integer.MIN_VALUE;
        int[] valid = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(valid);
        return valid[n / 2];
    }

    /** Statistiques de diagnostic. */
    public static String stats() {
        return "chunks reinitialises=" + primedChunks
                + ", rattrapages bloc-par-bloc=" + scannedColumns
                + ", zones capturees en rattrapage=" + repairedZones;
    }

    /** Remise a zero de tous les compteurs (debut de pipeline). */
    public static void resetStats() {
        primedChunks = 0L; scannedColumns = 0L; repairedZones = 0L;
        statLookups = 0L; statHits = 0L; softMisses = 0L;   // T53 : cache
    }
}
