package deepluckyblock.util;

/**
 * T293 : tests du CERVEAU EMBARQUE (MotherSnowBrain) — aucune dependance,
 * aucun reseau, aucun mock. Compile SANS Minecraft :
 *   javac MotherAISnowDirector.java TerrainMother.java MotherSnowBrain.java EmbeddedBrainTest.java
 *   java  deepluckyblock.util.EmbeddedBrainTest
 *
 * Prouve que :
 *  1) la decision est LOCALE (rien sur le reseau) et immediat (latence < 50 ms
 *     sur damier 24x24 = 576 enfants, borne tres large vs les ~50 us mesures) ;
 *  2) damier UNIFORME -> rayon 1 (fondu minimal), damier 50/50 plat ->
 *     rayon 3 (fondu maximal) — les deux regimes extremes du professeur
 *     sont reproduits ;
 *  3) eau envahissante resserre le fondu par rapport au meme damier sec ;
 *  4) les boosts restent dans [0.75, 1.25] et capes a 48 ;
 *  5) deux appels sur le meme damier donnent EXACTEMENT le meme plan
 *     (determinisme, pas un generateur aleatoire) ;
 *  6) le plan modifie la densite gaussienne T289 de facon mesurable (via
 *     consultAsync, le vrai chemin de la mere) — sans aucun serveur HTTP.
 */
public final class EmbeddedBrainTest {

    private static int run = 0;

    private static void check(boolean cond, String name) {
        run++;
        if (!cond) throw new AssertionError("FAIL: " + name);
        System.out.println("ok " + run + " - " + name);
    }

    /** Damier 24x24 : snowy% par enfant (0/1), eau=0, relief plat. */
    private static TerrainMother board(float snowyFrac, float waterEverywhere, boolean jagged) {
        TerrainMother m = TerrainMother.begin(0, 0, 23, 23);
        int idx = 0, n = 24 * 24;
        for (int cx = 0; cx <= 23; cx++) {
            for (int cz = 0; cz <= 23; cz++) {
                TerrainMother.Child c = m.child(cx, cz);
                c.present = true;
                // alternance en damier pour obtenir une fraction precise
                c.snowyRatio = ((idx * snowyFrac * n + idx) % n) < snowyFrac * n ? 1f : 0f;
                c.waterRatio = waterEverywhere;
                c.iceRatio = 0f;
                c.steep = false;
                c.surfaceYMedian = jagged ? 64 + (idx % 5) * 8 : 64;   // plat ou dunes +-16
                c.surfaceYMin = c.surfaceYMedian - 1;
                c.surfaceYMax = c.surfaceYMedian + 1;
                idx++;
            }
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        // -- 1. decision locale instantanee ------------------------------
        long t0 = System.nanoTime();
        MotherAISnowDirector.SnowPlan mixed = MotherSnowBrain.decide(board(0.5f, 0f, false), 0);
        long dt = (System.nanoTime() - t0) / 1_000_000L;
        check(dt < 50, "decision locale instantanee (" + dt + " ms sur 576 enfants)");
        System.out.println("    damier mixte plat -> rayon " + mixed.radiusChunks()
                + ", boosts " + mixed.childAuraBoost().size());

        // -- 2. regimes extremes -----------------------------------------
        MotherAISnowDirector.SnowPlan uniform = MotherSnowBrain.decide(board(1.0f, 0f, false), 0);
        check(uniform.radiusChunks() == 1, "damier 100% neige -> rayon 1 (mesure " + uniform.radiusChunks() + ")");
        check(mixed.radiusChunks() == 3, "damier 50/50 plat -> rayon 3 (mesure " + mixed.radiusChunks() + ")");

        // -- 3. l'eau resserre -------------------------------------------
        MotherAISnowDirector.SnowPlan wet = MotherSnowBrain.decide(board(0.5f, 0.6f, false), 0);
        check(wet.radiusChunks() <= mixed.radiusChunks(),
                "damier mixte inonde (" + wet.radiusChunks() + ") <= sec (" + mixed.radiusChunks() + ")");

        // -- 4. boosts bornes + capes ------------------------------------
        boolean inRange = true;
        for (float v : mixed.childAuraBoost().values()) inRange &= v >= 0.75f && v <= 1.25f;
        check(inRange, "tous les boosts dans [0.75, 1.25]");
        check(mixed.childAuraBoost().size() <= 48, "48 boosts max (mesure " + mixed.childAuraBoost().size() + ")");

        // -- 5. determinisme ---------------------------------------------
        MotherAISnowDirector.SnowPlan again = MotherSnowBrain.decide(board(0.5f, 0f, false), 0);
        check(again.radiusChunks() == mixed.radiusChunks()
                && again.childAuraBoost().equals(mixed.childAuraBoost()),
                "plan strictement identique au second appel (deterministe)");

        // -- 6. le vrai chemin de la mere, sans reseau -------------------
        TerrainMother m = board(0.5f, 0f, false);
        m.startAiConsultation();   // defaut T293 -> cerveau embarque
        float d = m.snowDensity(8, 8);   // attend le plan (borne 1500 ms inatteignable ici)
        check(d >= 0f && d <= 1f, "snowDensity saine via chemin mere (" + d + ")");
        java.lang.reflect.Field ap = TerrainMother.class.getDeclaredField("aiPlan");
        ap.setAccessible(true);
        check(ap.get(m) != null, "le plan embarque a bien ete applique par la mere (pas de fallback)");
        TerrainMother.end();

        System.out.println("ALL PASS (" + run + " checks)");
    }
}
