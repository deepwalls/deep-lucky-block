package deepluckyblock.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * DebugLog — point d'entree UNIQUE pour les logs de debug du mod.
 *
 * CONTEXTE (rapporte en jeu, log fourni) : l'ancien systeme de debug
 * ([SLB-DEBUG-TOOLTIP] / [SLB-DEBUG] / [SLB-DEBUG-ENCH]) spammait la console
 * en continu -- un seul log de session normale contenait 9705 lignes
 * [SLB-DEBUG-TOOLTIP] sur 10821 lignes totales (90%), rendant tout diagnostic
 * reel impossible (le vrai warning utile etait noye dans le bruit).
 *
 * Remplacement : UN SEUL flag togglable ({@link #ENABLED}, actuellement
 * {@code true}), qui ne couvre QUE deux categories utiles et CIBLEES :
 *   - {@link #structure} : le processus de generation de structure/terrain
 *     (smooth, clear, naturalize, mountain-wall...), utile pour diagnostiquer
 *     les bugs de spawn de structure.
 *   - {@link #heart} : les enchantements de coeur elementaires -- comment ils
 *     s'attachent (quel element, quel niveau, sur quel joueur), comment c'est
 *     vu cote joueur (HUD, regen, perte).
 *
 * Volontairement PAS d'equivalent pour le rendu de tooltip (appele a CHAQUE
 * frame ou le tooltip est visible -> spam garanti quel que soit le flag) : ce
 * genre de debug doit rester un println() temporaire retire avant de livrer,
 * jamais un flag permanent.
 */
public final class DebugLog {

    private DebugLog() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * T221 : relais unique des logs d'INFORMATION du mod.
     *
     * Toutes les classes qui ecrivaient directement LOGGER.info(...) passent
     * desormais par ici : un seul interrupteur ({@link #ENABLED}) suffit a
     * rendre la console propre pour la publication, sans toucher a un seul
     * LOGGER.warn / LOGGER.error (les vrais problemes restent visibles).
     */
    public static void info(Logger logger, String msg, Object... args) {
        if (!ENABLED) return;
        if (args == null || args.length == 0) logger.info(msg);
        else logger.info(msg, args);
    }

    /** Interrupteur general. false = silence total (hors erreurs/warnings reels).
     *
     * ⛔ T221 (PUBLICATION) : passe a false sur demande en jeu -- « tu vois dans
     *    les logs tout les debugs etc, infos inutiles au joueur ? on le
     *    desactive car je vais publier cette version ». Les WARN et les ERROR
     *    reels, eux, continuent TOUJOURS d'etre ecrits : seule l'information
     *    de diagnostic disparait. Remettre true pour re-ouvrir le diagnostic. */
    public static boolean ENABLED = false;

    // T232 (tests sandbox/lab) : -Ddlb.debug=1 (ou env DLB_DEBUG=1) rallume les
    // logs d'info + structure a la VOLee, sans toucher au defaut de
    // publication T221. Strictement additif (rien ne change sans le flag).
    static {
        if (System.getProperty("dlb.debug") != null || System.getenv("DLB_DEBUG") != null) {
            ENABLED = true;
            STRUCTURE = true;
        }
    }

    /**
     * Categorie (a) : generation de structure / terrain.
     *
     * CONSERVEE ACTIVE. Volume mesure : 54 lignes sur toute une session
     * (dernierslogs6), soit une poignee de lignes par structure generee. Ce
     * sont des compteurs de bilan (prepZone, smooth, naturalize, scatter,
     * cleanup) qui restent le seul moyen de diagnostiquer un spawn rate, et
     * le terrain est encore un chantier ouvert.
     */
    public static boolean STRUCTURE = false; // T221 : silence pour la publication

    /**
     * Categorie (b) : enchantements de coeur elementaires.
     *
     * CONSERVEE ACTIVE. Le rendu des coeurs est le dernier gros bug ouvert
     * (coeurs vanilla affiches au lieu des elementaux, animations ecrasees,
     * texture non retablie apres regen) : ces traces servent directement au
     * diagnostic. Volume raisonnable et proportionnel a l'action du joueur
     * (perte / regeneration), pas un spam par frame.
     */
    public static boolean HEART = false; // T221 : silence pour la publication

    // ------------------------------------------------------------------
    // T16 : FIL ROUGE DE PHASE.
    // ------------------------------------------------------------------
    /**
     * Derniere phase connue du pipeline terrain (ex. « prepZone 2/11 :
     * prefillFoundation 37/60 »).
     *
     * POURQUOI : une structure gelee (watchdog « A single server tick took
     * 60.27 seconds ») ne dit JAMAIS quelle phase a accapare le thread
     * principal -- le log s'arrete sur la derniere ligne ecrite, et
     * l'instrumentation existante est en fin de phase, donc muette pendant
     * l'accident. Ce libelle est mis a jour par step() et par les boucles
     * decoupees, et lu par la sonde de tick de TerrainEditClamp : des la
     * premiere periode de tick anormale, le log nomme la phase coupable.
     * Cout : une ecriture de reference volatile par tranche, nul en pratique.
     */
    private static volatile String PHASE = "aucune (serveur au repos)";

    // ------------------------------------------------------------------
    // T47 : CHRONOMETRAGE DU PIPELINE (« ou passent les secondes ? »).
    // ------------------------------------------------------------------
    // Demande en jeu du 23/09 : « ca prends des temps de fou ! revois tout pour
    // desactiver ce qui nous fait perdre du temps ». Un log qui ne donne que des
    // etapes, sans leur duree, oblige a deviner. perfBegin() est appele a
    // l'ouverture de la fenetre d'edition (debut du pipeline) et perfSummary()
    // a sa fermeture : la ligne [DLB-PERF] classe alors les phases par duree
    // decroissante. Cout : une comparaison de chaine quand le libelle change.
    private static volatile boolean PERF_ON = false;
    private static volatile long PERF_T0 = 0L;
    private static final java.util.Map<String, long[]> PERF = new java.util.HashMap<>();
    private static volatile String PERF_CUR = null;
    private static volatile long PERF_CUR_T0 = 0L;

    public static void setPhase(String label) {
        if (label == null) return;
        PHASE = label;
        if (PERF_ON) perfSwitch(label);
    }

    /** T47 : demarre (ou redemarre) le chronometre du pipeline. */
    public static void perfBegin() {
        // T53 : compteurs du cache de chunks remis a zero a chaque pipeline (le bilan
        // [DLB-PERF] donne alors le bilan exact de CETTE structure).
        deepluckyblock.util.SafeSurface.resetStats();
        deepluckyblock.util.SafeSurface.clearCache();
        synchronized (PERF) {
            PERF.clear();
            PERF_CUR = null;
            PERF_CUR_T0 = System.currentTimeMillis();
            PERF_T0 = PERF_CUR_T0;
            PERF_ON = true;
        }
    }

    private static void perfSwitch(String label) {
        String key = perfKey(label);
        if (key.equals(PERF_CUR)) return;
        long now = System.currentTimeMillis();
        synchronized (PERF) {
            if (PERF_CUR != null) {
                long[] v = PERF.computeIfAbsent(PERF_CUR, k -> new long[2]);
                v[0] += now - PERF_CUR_T0;
                v[1]++;
            }
            PERF_CUR = key;
            PERF_CUR_T0 = now;
        }
    }

    /** Regroupe les tranches d'une meme phase : « prepZone 3/11 : smooth terrain » -> « prepZone smooth terrain ». */
    private static String perfKey(String label) {
        int i = label.indexOf(':');
        String s = i > 0 ? label.substring(0, i) : label;
        while (true) {
            String t = s.replaceAll("\\s+\\d+/\\d+", "");
            if (t.equals(s)) break;
            s = t;
        }
        s = s.replaceAll("\\s+\\d+$", "").trim();
        return s.length() > 44 ? s.substring(0, 44) : s;
    }

    /**
     * T47 : bilan chiffre du pipeline, classes par duree. Renvoie "" si aucun
     * pipeline n'a ete chronometre.
     */
    public static String perfSummary() {
        if (!PERF_ON) return "";
        PERF_ON = false;
        long now = System.currentTimeMillis();
        synchronized (PERF) {
            if (PERF_CUR != null) {
                long[] v = PERF.computeIfAbsent(PERF_CUR, k -> new long[2]);
                v[0] += now - PERF_CUR_T0;
                v[1]++;
                PERF_CUR = null;
            }
            long total = Math.max(1L, now - PERF_T0);
            java.util.List<java.util.Map.Entry<String, long[]>> list =
                    new java.util.ArrayList<>(PERF.entrySet());
            list.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("TACHE COMPLETE en %.1f s -- ou est passe le temps : ", total / 1000.0));
            int shown = 0;
            long accounted = 0;
            for (java.util.Map.Entry<String, long[]> e : list) {
                if (shown++ >= 8) break;
                accounted += e.getValue()[0];
                sb.append(String.format("%s %.1f s (%d%%) | ", e.getKey(),
                        e.getValue()[0] / 1000.0, Math.round(100.0 * e.getValue()[0] / total)));
            }
            long idle = total - accounted;
            if (idle > 200) sb.append(String.format("attente/tick system %.1f s", idle / 1000.0));
            // T53 : combien de recherches de chunk ont ete evitees (cache de SafeSurface).
            String terrain = deepluckyblock.util.SafeSurface.statReport();
            if (!terrain.isEmpty()) sb.append(terrain);
            return sb.toString().replaceAll("\\s+\\|\\s*$", "");
        }
    }

    /** Libelle de la phase en cours (jamais null). */
    public static String phaseNow() {
        return PHASE;
    }

    /** Categorie (a) : generation de structure / terrain (smooth, clear, naturalize...). */
    public static void structure(String fmt, Object... args) {
        if (ENABLED && STRUCTURE) LOGGER.info("[DLB-STRUCTURE] " + fmt, args);
    }

    /** Categorie (b) : enchantements de coeur (attache, niveau, regen, HUD cote joueur). */
    public static void heart(String fmt, Object... args) {
        if (ENABLED && HEART) LOGGER.info("[DLB-HEART] " + fmt, args);
    }
}
