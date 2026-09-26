package deepluckyblock.util;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MAINTIENT LES CHUNKS D'UNE ZONE EN MEMOIRE PENDANT TOUT LE PIPELINE.
 *
 * <p>POURQUOI (constate en test dedie, 19-20/09) : les passes de terrain
 * (prepZone, carve, decorate, smooth, eau, decors) lisent et ecrivent des blocs
 * avec {@code level.getBlockState()/setBlock()}. Or, en 1.21.1, ces appels
 * <b>chargent et generent le chunk si besoin, de facon synchrone, sur le thread
 * serveur</b> (voir {@code Level.getChunk(x, z)} qui appelle
 * {@code getChunk(x, z, ChunkStatus.FULL, true)}). Consequence mesuree : quand la
 * zone d'une structure n'est plus en memoire -- ce qui arrive des que la
 * structure est loin du joueur, ou simplement quelques dizaines de secondes
 * apres un pre-chargement, les tickets temporaires du chunk system ayant
 * expire --, la premiere colonne touchee relance une generation complete.
 * Un seul tick a alors pris 60,05 s (watchdog : "A single server tick took
 * 60.05 seconds"), puis les passes rampaient (cleanupZone : 30 589 colonnes en
 * 171 000 ms).
 *
 * <p>CORRECTIF EN DEUX PARTIES :
 * <ol>
 *   <li>le pre-chargement de la zone (deja etale) est <b>epingle</b> : des que
 *       des chunks sont en memoire, un ticket est pose dessus pour qu'ils ne
 *       soient plus decharges tant que le pipeline travaille ;</li>
 *   <li>les tickets sont poses <b>progressivement</b> (quelques chunks par
 *       tick, uniquement ceux deja charges), jamais en une salve : une salve de
 *       tickets PORTAL demandant d'un coup 21x21 chunks a provoque exactement le
 *       meme gel de 60 s (verifie en test).</li>
 * </ol>
 *
 * <p>Le type de ticket utilise est {@code TicketType.PORTAL} : c'est un ticket
 * VANILLA, d'une duree de 300 ticks (15 s) qui se libere tout seul. Aucun etat
 * persistant n'est ecrit dans le monde (contrairement a {@code setChunkForced}
 * qui enregistre les chunks forces dans level.dat) : si le serveur s'arrete
 * brutalement, les chunks se dechargent normalement, sans rien laisser derriere.
 * Le maintien est rafraichi toutes les 5 s par {@link #keep(ServerLevel)} tant
 * que la zone est active, et la zone est relachee apres 10 s sans appel.
 */
public final class ChunkKeeper {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * DEBIT DU PRE-CHARGEMENT, adapte au nombre de coeurs disponibles.
     *
     * <p>Mesure du 20/09 (everest sur zone jamais generee, bac a sable 2 vCPU) :
     * 169 chunks en 22 s avec 4 demandes/tick et 4 en vol. Ce debit est
     * volontairement PRUDENT depuis l'origine (une salve de tickets avait gele
     * le serveur), mais il plafonne le pre-chargement : sur une machine a 8-16
     * coeurs, les generations peuvent tourner beaucoup plus en parallele sans
     * bloquer le thread serveur, puisque les demandes passent par
     * {@code getChunkFuture} (NON bloquant). On suit donc le nombre de coeurs,
     * en gardant les memes bornes basses qu'avant sur une petite machine.
     *
     * <p>Les TICKETS (PIN_PER_TICK) restent volontairement bas : c'est leur pose
     * en salve qui avait provoque un gel, pas les demandes asynchrones.
     */
    private static final int CPUS = Math.max(2, Runtime.getRuntime().availableProcessors());
    /**
     * T46 : DEMANDES DE CHARGEMENT PAR TICK.
     *
     * <p>Mesure en jeu du 23/09 (Crimson Lake, 783 chunks a generer) : avec
     * 4-16 demandes/tick et surtout une demande limitee a la TETE de la liste,
     * le moteur ne livrait qu'1 a 2 chunks par 30 s (24 minutes sans finir).
     * Les demandes passent par {@code getChunkFuture} (NON bloquant) : c'est la
     * generation elle-meme qui est parallele, ici on ne fait que la declencher.
     * On dimensionne donc le debit sur les coeurs, avec des bornes hautes mais
     * saines (le gel historique venait des generations SYNCHRONES, pas de ca).
     */
    // T76 : ANTI-GEL. 24 demandes/tick et 48 chunks en vol laissaient le
    // generateur distancer le thread principal : les chunks s'empilaient en
    // attente d'application (statut FULL, main thread) et le tick suivant payait
    // tout le retard d'un coup. Mesure : 12 979 ms de gel in-game, 3439 ms et
    // 2675 ms en bac a sable avec 6/tick. On borne donc ce qui est EN VOL, pas
    // ce qui est genere. Reglable par -Ddlb.requestPerTick=N.
    // T76b : 1 demande/tick et 2 chunks en vol. Mesure du palier intermediaire
    // (2/tick, 2 en vol) : pire tick de pre-chargement 3439 ms -> 1602 ms et
    // pre-chargement 7,5 s -> 6,5 s, donc mieux sur les DEUX tableaux. Or le
    // cout par chunk applique reste de l'ordre de plusieurs centaines de ms :
    // c'est le nombre de chunks APPLIQUES dans un tick qu'il faut ramener a 1
    // pour ne plus jamais depasser la seconde. Le debit n'en souffre pas : la
    // generation est parallele, on ne fait que lisser la LIVRAISON.
    private static final int REQUEST_PER_TICK = Math.max(1, Math.min(8, Integer.getInteger("dlb.requestPerTick", 8)));
    private static final int PIN_PER_TICK = 8;
    /**
     * Demandes de chargement asynchrones EN VOL, tout au plus.
     *
     * <p>T50 : plafond resserre (48 au lieu de 192). Mesure en bac a sable : un
     * pre-chauffage qui lache 750 demandes d'un coup fait gonfler la memoire du
     * serveur jusqu'a l'OOM avant la fin du pipeline. Les 48 premiers chunks
     * suffisent a sature les coeurs (chacun dure quelques dizaines de ms) : le
     * debit reste de plusieurs centaines de chunks par minute, sans risque.
     */
    // T76 : 48 -> 4 au maximum (reglable par -Ddlb.maxInFlight=N). Le plafond de
    // 48 datait du diagnostic OOM ; il bornait la MEMOIRE, pas le travail du
    // thread principal -- qui, lui, gelait le jeu. Le debit reste de plusieurs
    // dizaines de chunks par seconde (une generation dure quelques dizaines de ms).
    private static final int MAX_IN_FLIGHT = Math.max(2, Math.min(32, Integer.getInteger("dlb.maxInFlight", 16)));
    /** Le maintien est rafraichi tous les 5 s (le ticket PORTAL dure 15 s). */
    private static final int REFRESH_TICKS = 100;
    /** T66 : intervalle des re-demandes forcees pour les chunks toujours absents (5 s). */
    private static final int RETRY_TICKS = 100;
    /** Zone relachee apres 10 s sans nouvel appel a keep(). */
    private static final int GRACE_TICKS = 200;
    /**
     * Duree MAXIMALE pendant laquelle une zone reste epinglee, meme sans
     * nouvel appel (garde-fou : pipeline casse en cours de route).
     *
     * C'est ce delai qui remplace l'ancienne "grace de 10 s" : entre la fin du
     * pre-chargement et le paste, puis entre le paste et le decorate, il peut
     * s'ecouler MINUTES sans qu'aucune passe n'appelle keep(). Avec l'ancienne
     * grace, la zone etait relachee au milieu du pipeline, les chunks se
     * dechargeaient, et les passes suivantes sautaient leurs colonnes (mesure
     * en jeu : « fixWaterNearStructure : 13708 colonnes sautees (chunk absent) »
     * sur 34452, soit 40 % de la zone non traitee).
     */
    private static final long HOLD_TIMEOUT_MS = 600_000L;
    /** T53 : nombre de tickets rendus au chunk system par tick lors d'une liberation. */
    private static final int TICKET_DRAIN_PER_TICK = 96;
    /** Niveaux dont la zone est rafraichie automatiquement (une tache par niveau). */
    private static final java.util.Set<ServerLevel> TICKING =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** Garde-fou : on n'epingle jamais plus de chunks que ca par zone. */
    private static final int MAX_PINNED = 4096;

    private static final class Zone {
        final ServerLevel level;
        int cx0, cz0, cx1, cz1;
        long lastRefresh, activeUntil, holdStartMs;
        int requested;
        long lastWorkTick = Long.MIN_VALUE;
        /**
         * T53 : demandes EN VOL. Compteur ATOMIQUE : il etait incremente sur le thread serveur
         * et decremente par le callback du chunk system (autre thread). Sur un int nu, des
         * decrements pouvaient se perdre : le compteur restait haut, le budget tombait a 0 et le
         * pre-chargement S'ARRETAIT (bug silencieux : plus aucune demande, sans erreur).
         */
        final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        int reqCursor;   // T46 : position de la demande dans `pending` (round-robin)
        int refreshCursor;   // T53 : position du rafraichissement de tickets (round-robin)
        int retryCursor;     // T66 : position de la re-demande des chunks absents (round-robin)
        long lastRetry;      // T66 : derniere salve de re-demandes
        /** T46 : false pendant un PRE-CHAUFFAGE -- on demande la generation sans
         *  epingler (sinon 750 chunks seraient gardes en RAM avant meme que le
         *  pipeline ne commence). Les chunks restent alors dans `pending`
         *  jusqu'a ce qu'un pipeline normal les epingle. */
        boolean pinEnabled = true;
        final List<ChunkPos> pending = new ArrayList<>();
        final List<ChunkPos> pinned = new ArrayList<>();

        Zone(ServerLevel level) { this.level = level; }
    }

    /** Une zone par niveau (toutes les structures passent par le thread serveur). */
    private static final Map<ServerLevel, Zone> ZONES = new IdentityHashMap<>();

    private ChunkKeeper() {}

    /**
     * Installe (une seule fois par niveau) une tache recurrente qui rafraichit
     * les tickets et poursuit l'epinglage, meme quand aucune passe de terrain
     * n'appelle {@link #keep(ServerLevel)} (typiquement pendant le paste).
     */
    private static void ensureTicker(ServerLevel level) {
        if (!TICKING.add(level)) return;
        deepluckyblock.procedures.TestProcedure.schedule(level,
                deepluckyblock.procedures.TestProcedure.currentTick(level) + REFRESH_TICKS, () -> {
                    TICKING.remove(level);
                    if (!ZONES.containsKey(level)) return;
                    keep(level);
                    if (ZONES.containsKey(level)) ensureTicker(level);
                });
    }

    /**
     * Declare (ou etend) la zone a maintenir en memoire pour le niveau donne.
     * A appeler des le debut du pipeline et a chaque tranche de travail.
     */
    public static void track(ServerLevel level, BlockPos min, BlockPos max) {
        track(level, min, max, true);
    }

    /**
     * T46 : PRE-CHAUFFAGE -- demande la generation d'une zone SANS la garder en
     * memoire. Utilise pendant les 60 s d'annonce de l'evenement : la generation
     * (le vrai cout) se fait pendant que le joueur attend, mais on n'epingle pas
     * 700+ chunks d'avance ; le pipeline les rechargera (depuis le disque, tres
     * rapide) quand il commencera.
     */
    public static void warmOnly(ServerLevel level, BlockPos min, BlockPos max) {
        track(level, min, max, false);
    }

    public static void track(ServerLevel level, BlockPos min, BlockPos max, boolean pin) {
        if (level == null || min == null || max == null) return;
        Zone z = ZONES.get(level);
        if (z == null) {
            z = new Zone(level);
            z.pinEnabled = pin;          // T46
            ZONES.put(level, z);
            z.cx0 = min.getX() >> 4; z.cx1 = max.getX() >> 4;
            z.cz0 = min.getZ() >> 4; z.cz1 = max.getZ() >> 4;
            fillPending(z);
            LOGGER.info("[DLB-CHUNKS] zone {} -> {} tenue en memoire pendant le pipeline ({} chunks)",
                    min.toShortString(), max.toShortString(), z.pending.size());
            // T10 : ouvre la fenetre d'edition (chute de blocs + fluides
            // neutralises dans la zone) en meme temps que le maintien memoire.
            deepluckyblock.util.TerrainEditClamp.start(level,
                    min.getX(), Math.max(min.getY() - 64, level.getMinBuildHeight()), min.getZ(),
                    max.getX(), Math.min(max.getY() + 64, level.getMaxBuildHeight() - 1), max.getZ());
        } else {
            z.pinEnabled = pin;          // T46 : un pipeline normal reactive le maintien
            int cx0 = min.getX() >> 4, cx1 = max.getX() >> 4;
            int cz0 = min.getZ() >> 4, cz1 = max.getZ() >> 4;
            if (cx0 < z.cx0 || cz0 < z.cz0 || cx1 > z.cx1 || cz1 > z.cz1) {
                z.cx0 = Math.min(z.cx0, cx0); z.cx1 = Math.max(z.cx1, cx1);
                z.cz0 = Math.min(z.cz0, cz0); z.cz1 = Math.max(z.cz1, cz1);
                fillPending(z);
                LOGGER.info("[DLB-CHUNKS] zone etendue : {} chunks a maintenir", z.pending.size());
            }
        }
        // Avancement du depot : un rapport de temps en temps seulement.
        z.activeUntil = level.getGameTime() + GRACE_TICKS;
        z.holdStartMs = z.holdStartMs == 0L ? System.currentTimeMillis() : z.holdStartMs;
        // Rafraichissement AUTONOME : la zone est maintenue pendant TOUT le
        // pipeline (prepZone -> paste -> decorate), meme quand aucune passe ne
        // tourne. Sans ca, les tickets PORTAL (15 s) expiraient pendant un paste
        // de 30 s et les chunks se dechargeaient sous les passes suivantes.
        ensureTicker(level);
    }

    /**
     * A appeler a chaque tranche de travail (tick) : demande le chargement de
     * quelques chunks pas encore en memoire, epingle ceux qui le sont, et
     * rafraichit les tickets toutes les 5 s.
     */
    public static void keep(ServerLevel level) {
        Zone z = ZONES.get(level);
        if (z == null) return;
        long now = level.getGameTime();
        // Multiple terrain passes and the autonomous ticker share one per-tick budget.
        if (z.lastWorkTick == now) return;
        z.lastWorkTick = now;
        // Zone TENUE jusqu'a release() explicite (fin de pipeline) : l'ancienne
        // expiration a 10 s relachait la zone pendant le paste.
        long heldMs = System.currentTimeMillis() - z.holdStartMs;
        if (heldMs > HOLD_TIMEOUT_MS) {
            LOGGER.warn("[DLB-CHUNKS] zone tenue en memoire depuis {} s sans fin de pipeline -- "
                    + "relachee par garde-fou ({} chunks)", heldMs / 1000, z.pinned.size());
            release(level);
            return;
        }

        // 1) demandes de chargement asynchrones (jamais bloquant), petit debit.
        // On ne demande QUE le nombre de chunks autorise par le debit choisi :
        // getChunkFuture est idempotent (un chunk deja demande rend le meme
        // future), donc les tetes de liste non encore chargees sont simplement
        // redemandees au tick suivant -- aucun travail de generation en double.
        // T46 : DEMANDE REPARTIE SUR TOUTE LA ZONE (round-robin).
        // Avant, on ne demandait que la TETE de la liste : un chunk de tete qui
        // traine bloquait TOUTES les autres demandes, donc la generation restait
        // serie (mesure : 2 chunks / 30 s sur 783 a generer). En balayant la
        // liste entiere, le moteur recoit des dizaines de generations en
        // parallele -- exactement ce qu'il sait faire.
        // T65 : garde-fou anti-fuite du compteur d'appels en vol (un compteur fausse a la hausse
        // arrete TOUTES les demandes : plus aucun chunk ne se charge et le pipeline attend sa
        // zone indefiniment).
        if (z.inFlight.get() > MAX_IN_FLIGHT * 2) {
            LOGGER.warn("[DLB-CHUNKS] compteur d'appels en vol incoherent ({}) -- remis a zero (garde-fou T65)",
                    z.inFlight.get());
            z.inFlight.set(0);
        }
        int budget = Math.min(REQUEST_PER_TICK, MAX_IN_FLIGHT - z.inFlight.get());
        if (budget > 0 && !z.pending.isEmpty()) {
            int n = z.pending.size();
            if (z.reqCursor >= n) z.reqCursor = 0;
            for (int i = 0; i < budget; i++) {
                ChunkPos cp = z.pending.get(z.reqCursor % n);
                z.reqCursor = (z.reqCursor + 1) % n;
                if (level.getChunkSource().getChunkNow(cp.x, cp.z) != null) continue;   // deja charge : rien a demander
                z.requested++;
                // T60 : la demande passe par SafeSurface, qui tient le registre PENDING/READY.
                // Sans ca, les chunks demandes ici etaient "en vol" du point de vue du registre :
                // une lecture de terrain croyait le chunk jamais demande et interrogeait la
                // source, qui ATTENDAIT la fin de la generation (7,7 ms par lecture mesurees,
                // 17 s pour une grille de 2 209 cellules).
                z.inFlight.incrementAndGet();
                deepluckyblock.util.SafeSurface.requestThen(level, cp.x, cp.z, () -> z.inFlight.decrementAndGet());
            }
        }

        // 1bis) T66 : RETENTE periodiquement les chunks toujours absents (toutes les 5 s). Une
        // demande peut se perdre (future termine sans le chunk, chunk decharge juste apres sa
        // generation) : sans nouvelle tentative, ces chunks ne reviennent JAMAIS et la zone ne
        // se complete jamais. Mesure : 48 chunks sur 144 restaient absents indefiniment.
        if (now - z.lastRetry >= RETRY_TICKS && !z.pending.isEmpty()) {
            z.lastRetry = now;
            int n = z.pending.size();
            if (z.retryCursor >= n) z.retryCursor = 0;
            int tries = Math.min(REQUEST_PER_TICK, n);
            for (int i = 0; i < tries; i++) {
                ChunkPos cp = z.pending.get(z.retryCursor);
                z.retryCursor = (z.retryCursor + 1) % n;
                if (level.getChunkSource().getChunkNow(cp.x, cp.z) != null) continue;
                deepluckyblock.util.SafeSurface.reRequest(level, cp.x, cp.z);
            }
        }

        // 2) on epingle (ticket PORTAL) les chunks deja charges qui ne le sont pas encore.
        int pinnedNow = 0;
        if (!z.pinEnabled) return;   // T46 : pre-chauffage -> generation seule
        Iterator<ChunkPos> it = z.pending.iterator();
        while (it.hasNext() && pinnedNow < PIN_PER_TICK && z.pinned.size() < MAX_PINNED) {
            ChunkPos cp = it.next();
            ChunkAccess chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
            if (chunk == null || !SafeSurface.isReallyGenerated(chunk)) continue;
            it.remove();
            z.pinned.add(cp);
            try {
                level.getChunkSource().addRegionTicket(TicketType.PORTAL, cp, 0, cp.getWorldPosition());
                pinnedNow++;
            } catch (Throwable t) {
                z.pinned.remove(cp);
            }
        }
        if (pinnedNow > 0 && (z.pending.isEmpty() || z.pinned.size() % 24 == 0)) {
            LOGGER.info("[DLB-CHUNKS] {}/{} chunks epingles (maintien en memoire){}",
                    z.pinned.size(), z.pinned.size() + z.pending.size(),
                    z.pending.isEmpty() ? " -- zone complete" : "");
        }

        // 3) rafraichissement des tickets (le ticket PORTAL vit 300 ticks / 15 s).
        // T53 : PAR TRANCHES, dimensionnees sur le temps ecoule, de sorte qu'un tour complet
        // dure ~150 ticks (7,5 s) quel que soit le rythme des appels. AVANT : les 616 tickets
        // etaient retires et reposes d'un coup toutes les 5 s -- 1232 operations de scheduling
        // du chunk system dans un seul tick, a l'origine de micro-freezes reguliers.
        if (!z.pinned.isEmpty()) {
            int n = z.pinned.size();
            long dt = Math.max(1L, now - z.lastRefresh);
            int need = (int) Math.min(Math.ceil(n * (dt / 150.0)) + 1L, Math.max(1, n / 8));
            for (int k = 0; k < need; k++) {
                if (z.refreshCursor >= n) z.refreshCursor = 0;
                ChunkPos cp = z.pinned.get(z.refreshCursor++);
                try {
                    level.getChunkSource().removeRegionTicket(TicketType.PORTAL, cp, 0, cp.getWorldPosition());
                    level.getChunkSource().addRegionTicket(TicketType.PORTAL, cp, 0, cp.getWorldPosition());
                } catch (Throwable ignored) { }
            }
            z.lastRefresh = now;
        }
    }

    /** Vrai si une zone est actuellement tenue pour ce niveau. */
    public static boolean held(ServerLevel level) {
        return level != null && ZONES.containsKey(level);
    }

    /** Libere les tickets de la zone du niveau (fin de pipeline). */
    public static void release(ServerLevel level) {
        Zone z = ZONES.remove(level);
        deepluckyblock.util.TerrainEditClamp.stop(level, "fin de pipeline");
        if (z == null) return;
        int total = z.pinned.size();
        // T53 : retrait PAR TRANCHES (un retrait de 616 tickets dans un meme tick fait
        // recalculer tout le scheduling du chunk system d'un coup). Les tickets restants
        // expirent de toute facon seuls au bout de 15 s.
        drainTickets(level, z.pinned);
        LOGGER.info("[DLB-CHUNKS] zone relachee ({} chunks rendus au chunk system, en tranches)", total);
    }

    /**
     * Vrai si TOUS les chunks de la zone sont deja en memoire et generes.
     * C'est le "portillon" utilise avant les phases lourdes : une phase ne
     * demarre que quand sa zone est prete, ce qui garantit qu'aucun
     * {@code getBlockState()} de la phase ne declenchera de generation.
     */
    public static boolean zoneLoaded(ServerLevel level, BlockPos min, BlockPos max) {
        if (level == null || min == null || max == null) return true;
        for (int cx = min.getX() >> 4; cx <= max.getX() >> 4; cx++) {
            for (int cz = min.getZ() >> 4; cz <= max.getZ() >> 4; cz++) {
                ChunkAccess chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null || !SafeSurface.isReallyGenerated(chunk)) return false;
            }
        }
        return true;
    }

    /**
     * Vrai si la zone suivie pour ce niveau est entierement en memoire (tous les
     * chunks demandes ont ete epingles), ou s'il n'y a aucune zone suivie.
     * C'est le portillon generique : tant qu'il est faux, les passes de terrain
     * peuvent se reporter -- plutot que de toucher un chunk absent et de
     * declencher une generation bloquante.
     */
    public static boolean zoneComplete(ServerLevel level) {
        Zone z = ZONES.get(level);
        return z == null || z.pending.isEmpty();
    }

    /** Diagnostic lisible dans les logs. */
    public static String stats(ServerLevel level) {
        Zone z = ZONES.get(level);
        if (z == null) return "aucune zone suivie";
        return "zone " + z.cx0 + "," + z.cz0 + " -> " + z.cx1 + "," + z.cz1
                + " : " + z.pinned.size() + " chunks epingles, " + z.pending.size()
                + " en attente, " + z.requested + " demandes de chargement";
    }

    /**
     * T53 : rend les tickets d'une liste au chunk system, par tranches de
     * {@link #TICKET_DRAIN_PER_TICK} tickets par tick (jamais une salve).
     */
    private static void drainTickets(ServerLevel level, List<ChunkPos> list) {
        int n = 0;
        Iterator<ChunkPos> it = list.iterator();
        while (it.hasNext() && n < TICKET_DRAIN_PER_TICK) {
            ChunkPos cp = it.next();
            it.remove();
            n++;
            try {
                level.getChunkSource().removeRegionTicket(TicketType.PORTAL, cp, 0, cp.getWorldPosition());
            } catch (Throwable ignored) { }
        }
        if (!list.isEmpty()) {
            deepluckyblock.procedures.TestProcedure.schedule(level,
                    deepluckyblock.procedures.TestProcedure.currentTick(level) + 1,
                    () -> drainTickets(level, list));
        }
    }

    private static void fillPending(Zone z) {
        // T53 : appartenance en HashSet (l'ancien `contains` sur les deux listes etait en
        // O(n) par chunk, soit ~380 000 comparaisons pour la zone du lac, et 16 millions
        // si la zone atteignait le plafond de 4096 chunks).
        java.util.Set<ChunkPos> known = new java.util.HashSet<>(z.pinned);
        known.addAll(z.pending);
        for (int cx = z.cx0; cx <= z.cx1; cx++) {
            for (int cz = z.cz0; cz <= z.cz1; cz++) {
                ChunkPos cp = new ChunkPos(cx, cz);
                if (!known.add(cp)) continue;
                z.pending.add(cp);
            }
        }
    }
}
