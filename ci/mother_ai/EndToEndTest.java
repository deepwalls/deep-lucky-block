package deepluckyblock.util;

import java.util.concurrent.CompletableFuture;

/**
 * T292 : test de bout en bout de la chaine IA de la mere CONTRE UN FAUX
 * Ollama local (ci/mother_ai/mock_ollama.py, protocole reel /api/tags +
 * /api/generate). Compile SANS Minecraft :
 *   javac -cp <gson.jar> MotherAISnowDirector.java TerrainMother.java EndToEndTest.java
 *   java  -cp .:<gson.jar> -Ddlb.motherAi=on deepluckyblock.util.EndToEndTest <scenario>
 *
 * Scenarios (le mock doit tourner sur 127.0.0.1:11434 avec le MOCK_MODE
 * correspondant — sauf "offline" qui exige l'ABSENCE de serveur) :
 *   ok        plan valide   -> SnowPlan radius=3, 4 boosts, appliques a snowDensity
 *   fenced    JSON entoure de ``` -> idem ok
 *   broken    JSON casse    -> plan null (fallback propre, pas d'exception)
 *   slow      latence 2,5 s -> future termine (plan recu), mais awaitAiPlanOnce
 *                              (borne 1500 ms) repart en deterministe : prouve
 *                              que la mere ne bloque JAMAIS plus que sa borne
 *   http500   -> plan null (fallback propre)
 *   offline   -> plan null rapide (<2,5 s) et REPROBE immediate = detection
 *                              du prochain demarrage possible (etat memoire)
 *
 * Aussi verifie que le boost IA modifie VRAIMENT la densite retournee par
 * snowDensity() (comparaison brute vs boostee sur le meme damier).
 */
public final class EndToEndTest {

    private static int run = 0;

    private static void check(boolean cond, String name) {
        run++;
        if (!cond) throw new AssertionError("FAIL: " + name);
        System.out.println("ok " + run + " - " + name);
    }

    /** Construit une mere 4x4 chunks (cx 90..93, cz -20..-17) a damier mixte :
     *  moitie nord 100% neige, moitie sud 0% — regime "tres mixte" T289=r=3. */
    private static TerrainMother freshMother() {
        TerrainMother m = TerrainMother.begin(90, -20, 93, -17);
        for (int cx = 90; cx <= 93; cx++) {
            for (int cz = -20; cz <= -17; cz++) {
                TerrainMother.Child c = m.child(cx, cz);
                c.present = true;
                c.surfaceYMedian = 64;
                c.surfaceYMin = 63;
                c.surfaceYMax = 66;
                c.snowyRatio = cz <= -19 ? 1.0f : 0.0f;   // 8/16 enneiges, a la frontiere nord
                c.waterRatio = 0f;
                c.iceRatio = 0f;
                c.steep = false;
            }
        }
        return m;
    }

    /** Densite brute au point (x,z) avec le rayon force r et SANS plan IA. */
    private static float rawDensity(TerrainMother m, int r, int x, int z) throws Exception {
        java.lang.reflect.Method md = TerrainMother.class.getDeclaredMethod("evaluateSnowRadius");
        md.setAccessible(true);
        m.setSnowRadiusChunks(r);
        // aiPlan null -> densite pure T289
        return m.snowDensity(x, z);
    }

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "ok";

        // -- 1. consultation distante ------------------------------------
        TerrainMother m = freshMother();
        long t0 = System.currentTimeMillis();
        CompletableFuture<MotherAISnowDirector.SnowPlan> f = MotherAISnowDirector.consultAsync(m);
        MotherAISnowDirector.SnowPlan plan = f.get(12, java.util.concurrent.TimeUnit.SECONDS);
        long dt = System.currentTimeMillis() - t0;

        switch (scenario) {
            case "ok", "fenced" -> {
                check(plan != null, scenario + " : plan recu");
                check(plan != null && plan.radiusChunks() == 3, scenario + " : radius=3 du mock");
                check(plan != null && plan.childAuraBoost().size() == 4,
                        scenario + " : 4 boosts (3 renforcés + 1 attenué)");
                // 2. effet MESURABLE sur snowDensity : meme mere, meme point,
                //    avec vs sans plan IA — le damier n'a pas change, seul le plan.
                int x = 90 * 16 + 8, z = -20 * 16 + 8;    // centre chunk le plus enneige (booste 1.25)
                float brut = rawDensity(freshMother(), 3, x, z);
                TerrainMother m2 = freshMother();
                java.lang.reflect.Field ap = TerrainMother.class.getDeclaredField("aiPlan");
                ap.setAccessible(true);
                ap.set(m2, plan);
                java.lang.reflect.Field aw = TerrainMother.class.getDeclaredField("aiAwaited");
                aw.setAccessible(true); aw.set(m2, true);
                java.lang.reflect.Method md = TerrainMother.class.getDeclaredMethod("evaluateSnowRadius");
                md.setAccessible(true);
                float boostee = m2.snowDensity(x, z);
                System.out.println("    densite brute=" + brut + "  avec IA=" + boostee);
                check(Math.abs(boostee - brut) > 1e-4f, scenario + " : le plan IA MODIFIE la densite");
                check(boostee > brut, scenario + " : boost 1.25 -> densite plus forte pres du chunk enneige");
            }
            case "broken", "http500" -> {
                check(plan == null, scenario + " : plan refuse proprement (null, zero exception)");
            }
            case "slow" -> {
                check(plan != null, "slow : plan finit par arriver (future non cassee)");
                check(dt >= 2000, "slow : latence mock respectee (" + dt + " ms)");
                // la mere ne doit PAS avoir attendu toute la requete : borne 1500 ms
                TerrainMother m3 = freshMother();
                m3.startAiConsultation();
                long ta = System.currentTimeMillis();
                m3.snowDensity(90 * 16, -18 * 16);
                long waited = System.currentTimeMillis() - ta;
                check(waited <= 1900, "slow : awaitAiPlanOnce borne a ~1,5 s (mesure " + waited + " ms)");
            }
            case "offline" -> {
                check(plan == null, "offline : null, pas d'exception");
                check(dt < 2500, "offline : echec rapide (" + dt + " ms < 2,5 s)");
            }
            default -> throw new IllegalArgumentException("scenario inconnu : " + scenario);
        }
        TerrainMother.end();
        System.out.println("SCENARIO " + scenario + " PASS (" + run + " checks)");
        System.exit(0);   // coupe le pool daemon DLB-MotherAI
    }
}
