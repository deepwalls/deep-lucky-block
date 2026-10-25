package deepluckyblock.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * T289 (ordre client 21/10 : « on analyse les chunks de maniere individuelle
 * ET croisee — des cerveaux independants qui travaillent sur une partie
 * chacun, mais qui communiquent avec notre cerveau Mother ; la mere s'occupe
 * a la fin de regrouper les informations, demande a tous de se load
 * entierement, et place la structure/les arbres avec les informations de ses
 * enfants ») : MERE/ENFANTS de zone.
 *
 * <p><b>ENFANT</b> = {@link Child} : un cerveau par chunk (16x16). Il analyse
 * SON chunk a l'arrivee (y de surface median/max, ratio neige/glace/eau,
 * mur-crevasse interne), fige ces mesures VIERGES (avant toute edition) et
 * repond ensuite instantanement aux questions de la mere : {@code isSnowy()},
 * surfaceY, etc. Plus aucune relecture du monde n'est necessaire pendant les
 * passes : chaque enfant est la memoire de sa parcelle.
 *
 * <p><b>MERE</b> = {@link TerrainMother} : la grille vue du ciel (damier
 * d'enfants). Elle est SEULE responsable des chunks de la STRUCTURE et
 * commande les enfants du pourtour : (1) chargement — tout enfant visite est
 * garde « actif et ouvert » dans la grille, (2) veto de zone A1 renforce —
 * la zone n'est acceptee que si l'ecart de surface mediane entre CHUNKS
 * ADJACENTS de l'emprise ne depasse JAMAIS 2 blocs (ordre client : « pour
 * eviter les terrains qui s'affaissent d'un coup »), (3) densite de neige —
 * la mere lisse la grille isSnowy des enfants avec un noyau gaussien
 * multi-chunks (facon HeightMapFilter/GaussianKernel de WorldEdit, projet
 * public de reference) : le fondu court sur 1 a 3 chunks selon le damier,
 * centre SUR les frontieres, sans aucune ligne dure (l'ancien fondu par
 * paire de chunks est abandonne — il creusait une moyenne qui excluait le
 * chunk courant, d'ou la « giga ligne de snow a la derniere ligne du chunk,
 * pile contre le chunk sec »).
 *
 * <p>La mere ne pose et ne retire RIEN elle-meme : elle repond, et ce sont
 * les passes historiques (smooth, sweep neige, selecteur de zone) qui
 * ecrivent — elles ne decident plus a l'aveugle.
 */
public final class TerrainMother {

    // ------------------------------------------------------------------
    // ENFANT
    // ------------------------------------------------------------------
    public static final class Child {
        public final int cx, cz;
        /** y de surface median (echantillon 4x4, MOTION noleaves) fige au scout. */
        public int surfaceYMedian = Integer.MIN_VALUE;
        public int surfaceYMin = Integer.MIN_VALUE;
        public int surfaceYMax = Integer.MIN_VALUE;
        /** ratio de colonnes a peau neigeuse (mesure vierge du chunk sain). */
        public float snowyRatio;
        /** ratios eau/glace photographies au scout. */
        public float waterRatio, iceRatio;
        /** true = mur quasi vertical ou crevasse interne (> 24 bl) detecte. */
        public boolean steep;
        public boolean present = true;

        Child(int cx, int cz) { this.cx = cx; this.cz = cz; }

        /** QUESTION MERE « isSnowy » : le chunk est-il globalement enneige ? */
        public boolean isSnowy() { return snowyRatio >= 0.5f; }
        /** Etendue interne du chunk (max-min) — mur/crevasse si enorme. */
        public int span() {
            return surfaceYMin == Integer.MIN_VALUE ? 0 : surfaceYMax - surfaceYMin;
        }
    }

    // ------------------------------------------------------------------
    // MERE
    // ------------------------------------------------------------------
    private static volatile TerrainMother CURRENT;

    /** Mere active (une seule chaine terrain a la fois, garanti par la fifo). */
    public static TerrainMother current() { return CURRENT; }

    /** Installe la mere de la zone (boite = zone finale tenue, en chunks). */
    public static TerrainMother begin(int gx0, int gz0, int gx1, int gz1) {
        TerrainMother m = new TerrainMother(gx0, gz0, gx1, gz1);
        CURRENT = m;
        return m;
    }

    /** Dissout la mere (fin de chaine). */
    /** Fin de fenetre : la mere est liberee (et toute IA en vol annulee). */
    public static void end() {
        TerrainMother m = CURRENT;
        if (m != null) {
            java.util.concurrent.CompletableFuture<MotherAISnowDirector.SnowPlan> f = m.aiFuture;
            if (f != null && !f.isDone()) f.cancel(true);
            m.aiPlan = null;
        }
        CURRENT = null;
    }

    private final int gx0, gz0, gw, gh;
    private final Child[] grid;

    private TerrainMother(int gx0, int gz0, int gx1, int gz1) {
        this.gx0 = gx0; this.gz0 = gz0;
        this.gw = gx1 - gx0 + 1; this.gh = gz1 - gz0 + 1;
        this.grid = new Child[gw * gh];
    }

    /** Enfant du chunk s'il est dans la grille (null = hors zone suivie). */
    public Child child(int cx, int cz) {
        int i = cx - gx0, j = cz - gz0;
        if (i < 0 || j < 0 || i >= gw || j >= gh) return null;
        int k = j * gw + i;
        Child c = grid[k];
        if (c == null) { c = new Child(cx, cz); grid[k] = c; }
        return c;
    }

    /** Tous les enfants effectivement scouts (pour les logs/diagnostics). */
    public List<Child> allChildren() {
        List<Child> out = new ArrayList<>();
        for (Child c : grid) if (c != null && c.present) out.add(c);
        return out;
    }

    // ------------------------------------------------------------------
    // MERE — veto A1 renforce (ordre client) : ecart median entre chunks
    // adjacents de l'emprise <= 2 blocs, sinon le terrain « s'affaisse
    // d'un coup » sous la structure.
    // ------------------------------------------------------------------
    /**
     * Plus grand ecart de {@code surfaceYMedian} entre deux chunks adjacents
     * (4-voisinage) du rectangle de chunks inclus. Renvoie
     * {@link Integer#MIN_VALUE} si trop peu d'enfants renseignes (zone non
     * chargee : on ne juge pas).
     */
    public int worstAdjacentGap(int scx0, int scz0, int scx1, int scz1) {
        int worst = Integer.MIN_VALUE;
        boolean any = false;
        for (int cx = scx0; cx <= scx1; cx++) {
            for (int cz = scz0; cz <= scz1; cz++) {
                Child c = child(cx, cz);
                if (c.surfaceYMedian == Integer.MIN_VALUE) continue;
                Child e = (cx + 1 <= scx1) ? child(cx + 1, cz) : null;
                Child s = (cz + 1 <= scz1) ? child(cx, cz + 1) : null;
                if (e != null && e.surfaceYMedian != Integer.MIN_VALUE) {
                    worst = Math.max(worst, Math.abs(c.surfaceYMedian - e.surfaceYMedian));
                    any = true;
                }
                if (s != null && s.surfaceYMedian != Integer.MIN_VALUE) {
                    worst = Math.max(worst, Math.abs(c.surfaceYMedian - s.surfaceYMedian));
                    any = true;
                }
            }
        }
        return any ? worst : Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------
    // MERE — densite de neige lissee (damier d'enfants, noyau gaussien)
    // ------------------------------------------------------------------
    /** Rayon du fondu en CHUNKS, choisi par la mere selon le damier (1..3). */
    private int snowRadiusChunks = 2;
    private boolean snowRadiusLocked = false;

    // ------------------------------------------------------------------
    // T290 (ordre client 25/10 : « new idee, add ia to mother ») : la mere
    // peut CONSULTER un modele local (Ollama) — voir MotherAISnowDirector.
    // L'avis de l'IA n'est qu'un NUDGE borne : rayon 1..3 et boosts d'aura
    // par enfant (0.75..1.25). Le gaussien deterministe T289 reste le dos
    // de la mecanique ; sans Ollama, rien ne change jamais.
    // ------------------------------------------------------------------
    private volatile java.util.concurrent.CompletableFuture<MotherAISnowDirector.SnowPlan> aiFuture = null;
    private volatile MotherAISnowDirector.SnowPlan aiPlan = null;
    private volatile boolean aiAwaited = false;

    /** Lance la consultation IA (no-op si deja lancee pour cette zone). */
    public void startAiConsultation() {
        if (aiFuture != null) return;
        aiFuture = MotherAISnowDirector.consultAsync(this);
    }

    /**
     * Attend le plan IA UNE SEULE FOIS (au plus 1500 ms), a la premiere
     * demande de densite de la zone. Objectif : toutes les decisions de
     * neige d'une meme zone partagent le meme regime (jamais de plan qui
     * bascule au milieu d'un sweep → pas de ligne nouvelle). Passe le
     * delai, la zone reste sur le regime deterministe.
     */
    private void awaitAiPlanOnce() {
        if (aiAwaited) return;
        aiAwaited = true;
        if (aiFuture == null) return;
        try {
            MotherAISnowDirector.SnowPlan p = aiFuture.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (p != null) {
                aiPlan = p;
                if (p.radiusChunks() > 0) setSnowRadiusChunks(p.radiusChunks());
                System.out.println("[DLB T290] IA mere : plan applique (rayon " + p.radiusChunks()
                        + ", boosts " + p.childAuraBoost().size() + ", latence " + p.latencyMs() + " ms).");
            } else {
                System.out.println("[DLB T290] IA mere : pas de plan (desactivee/echec) — regime deterministe T289.");
            }
        } catch (java.util.concurrent.TimeoutException te) {
            System.out.println("[DLB T290] IA mere : pas de reponse sous 1500 ms — regime deterministe T289 (le modele continue en tache de fond, ignore).");
            aiFuture.cancel(true);
        } catch (Throwable t) {
            System.out.println("[DLB T290] IA mere : attente en echec (" + t.getClass().getSimpleName() + ") — regime deterministe T289.");
        }
    }

    /** Permet a la selection de la zone d'imposer le rayon (1..3). */
    public void setSnowRadiusChunks(int r) {
        snowRadiusChunks = Math.max(1, Math.min(3, r));
        snowRadiusLocked = true;
    }

    /**
     * La mere LIT SON DAMIER et choisit le rayon du fondu de neige (ordre
     * client : « calcule une aura qui s'echappe en snow, peut etre de
     * 3 chunks dans un, tout depends de la zone quadrillee vue par maman ») :
     * <ul>
     *  <li>damier quasi uniforme (&gt;= 90 % ou &lt;= 10 % d'enfants enneiges)
     *      : r=1 — le fondu ne sert qu'a casser la couture pure ;</li>
     *  <li>damier tres mixed (30-70 %) : r=3 — grandes congeres de
     *      transition, l'aura d'un bloc de neige porte loin ;</li>
     *  <li>entre les deux : r=2 — compromis historique doux.</li>
     * </ul>
     */
    private void evaluateSnowRadius() {
        if (snowRadiusLocked) return;
        int snowy = 0, total = 0;
        for (Child c : grid) {
            if (c != null && c.present && c.surfaceYMedian != Integer.MIN_VALUE) {
                total++;
                if (c.isSnowy()) snowy++;
            }
        }
        if (total == 0) return;
        float frac = snowy / (float) total;
        if (frac >= 0.90f || frac <= 0.10f) snowRadiusChunks = 1;
        else if (frac >= 0.30f && frac <= 0.70f) snowRadiusChunks = 3;
        else snowRadiusChunks = 2;
    }

    /**
     * Densite de neige lisse en (x, z) — moyenne gaussienne des
     * {@code snowyRatio} des enfants environnants. Le noyau est celui de
     * GaussianKernel (WorldEdit) : exp(-(d2)/(2*sigma^2)), avec sigma = rayon
     * en chunks / 2. Distance en ESPACE BLOC au centre de chaque chunk
     * voisin : la couverture passe en douceur d'un damier a l'autre, la
     * frontiere tombe au MILIEU du joint, symetrique des deux cotes. Aucune
     * parite de perimetre, aucune ligne droite : un enfant enneige rayonne
     * son aura sur 1 a 3 chunks dans tous les sens.
     *
     * <p>Renvoie -1 si AUCUN enfant voisin n'est renseigne (grille vide :
     * l'appelant garde alors son comportement historique).
     */
    public float snowDensity(int x, int z) {
        awaitAiPlanOnce();
        evaluateSnowRadius();
        final int r = snowRadiusChunks;
        final double sigma2 = 2.0 * (r * 8.0) * (r * 8.0);   // (r chunks/2)^2 * 2, en blocs
        final MotherAISnowDirector.SnowPlan plan = aiPlan;   // copy volatile
        double sum = 0, wsum = 0;
        int ccx = x >> 4, ccz = z >> 4;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                Child c = child(ccx + dx, ccz + dz);
                if (c == null || !c.present || c.surfaceYMedian == Integer.MIN_VALUE) continue;
                double ddx = (ccx + dx) * 16 + 8 - x;
                double ddz = (ccz + dz) * 16 + 8 - z;
                double w = Math.exp(-(ddx * ddx + ddz * ddz) / sigma2);
                if (plan != null) {
                    // T290 : nudge IA borne (0.75..1.25) — l'enfant « parle »
                    // un peu plus ou moins fort dans son aura, jamais plus.
                    Float boost = plan.childAuraBoost().get((((long) c.cx) << 32) | (c.cz & 0xffffffffL));
                    if (boost != null) w *= boost;
                }
                sum += c.snowyRatio * w;
                wsum += w;
            }
        }
        return wsum == 0 ? -1f : (float) (sum / wsum);
    }

    // ------------------------------------------------------------------
    // MERE — cache d'enfants STANDALONE pour la selection de zone
    // (requests en-dehors de toute chaine : le selecteur sonde des
    // candidates avant meme le preload ; c'est le meme damier, une fois).
    // ------------------------------------------------------------------
    private static final Map<Long, Child> STANDALONE = new HashMap<>();

    /** Enfant de secours pour les mesures hors-chaine (selection de zone). */
    public static Child standaloneChild(int cx, int cz) {
        long k = (((long) cx) << 32) | (cz & 0xffffffffL);
        return STANDALONE.computeIfAbsent(k, kk -> new Child(cx, cz));
    }

    /** Evite la croissance infinie du cache de selection (borne douce). */
    public static void pruneStandalone() {
        if (STANDALONE.size() > 20_000) STANDALONE.clear();
    }
}
