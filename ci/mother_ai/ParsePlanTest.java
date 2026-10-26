package deepluckyblock.util;

import java.util.Map;

/**
 * T292 : tests du parseur IA de la mere (parsePlan). Compile SANS Minecraft :
 * javac -cp <gson.jar> MotherAISnowDirector.java TerrainMother.java ParsePlanTest.java
 * puis  java -cp .:<gson.jar> deepluckyblock.util.ParsePlanTest
 *
 * Couvre : JSON valide, fences markdown autour, radius hors bornes (clamp 1..3
 * et 0 = non tranche), facteurs >1.25 / <0.75 (clamp), plus de 48 boosts,
 * JSON casse, champ response absent/vide -> null. Zero dependance de test,
 * main() leve AssertionError au premier echec, sinon "ALL PASS (N/8)".
 */
public final class ParsePlanTest {

    private static int run = 0;

    private static void check(boolean cond, String name) {
        run++;
        if (!cond) throw new AssertionError("FAIL: " + name);
        System.out.println("ok " + run + " - " + name);
    }

    private static String wrap(String inner) {
        // format /api/generate de Ollama : {"response":"<json, escaped>"}
        String esc = inner.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "{\"model\":\"m\",\"response\":\"" + esc + "\",\"done\":true}";
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xffffffffL);
    }

    public static void main(String[] args) {
        // 1) plan nominal : radius 2 + deux boosts dans les bornes
        MotherAISnowDirector.SnowPlan p = MotherAISnowDirector.parsePlan(
                wrap("{\"radius\":2,\"boost\":[[96,-8,1.1],[95,-9,0.9]]}"), "m", 42L);
        check(p != null, "plan nominal parse");
        check(p.radiusChunks() == 2, "radius=2 conserve");
        check(p.childAuraBoost().size() == 2, "2 boosts gardes");
        check(Math.abs(p.childAuraBoost().get(key(96, -8)) - 1.1f) < 1e-6, "boost 1.1 exact (key negative cz)");

        // 2) fences markdown autour du JSON (habitude des petits modeles)
        p = MotherAISnowDirector.parsePlan(
                wrap("Voici mon plan :\n```json\n{\"radius\":3,\"boost\":[]}\n```"), "m", 1L);
        check(p != null && p.radiusChunks() == 3, "fences markdown isolees, radius=3");

        // 3) radius hors bornes : 9 -> 3 ; -4 -> 0 (= la mere decide toute seule)
        p = MotherAISnowDirector.parsePlan(wrap("{\"radius\":9,\"boost\":[]}"), "m", 1L);
        check(p != null && p.radiusChunks() == 3, "radius 9 clamp a 3");
        p = MotherAISnowDirector.parsePlan(wrap("{\"radius\":-4,\"boost\":[]}"), "m", 1L);
        check(p != null && p.radiusChunks() == 0, "radius -4 -> 0 (non decide)");

        // 4) facteurs de boost clamps : 5.0 -> 1.25 ; 0.1 -> 0.75
        p = MotherAISnowDirector.parsePlan(wrap("{\"radius\":1,\"boost\":[[10,20,5.0],[10,21,0.1]]}"), "m", 1L);
        check(p != null
                && Math.abs(p.childAuraBoost().get(key(10, 20)) - 1.25f) < 1e-6
                && Math.abs(p.childAuraBoost().get(key(10, 21)) - 0.75f) < 1e-6, "boosts clamps 1.25 / 0.75");

        // 5) plus de 48 boosts -> seuls 48 gardes
        StringBuilder sb = new StringBuilder("{\"radius\":1,\"boost\":[");
        for (int i = 0; i < 80; i++) sb.append(i == 0 ? "[" : ",[").append(i).append(',').append(i).append(",1.0]");
        sb.append("]}");
        p = MotherAISnowDirector.parsePlan(wrap(sb.toString()), "m", 1L);
        check(p != null && p.childAuraBoost().size() == 48, "80 boosts proposes -> 48 retenus");

        // 6) JSON interieur casse -> null (fallback deterministe cote mere)
        check(MotherAISnowDirector.parsePlan(wrap("{radius: 2, boost: [}"), "m", 1L) == null,
                "JSON casse -> null");

        // 7) response vide / absent -> null
        check(MotherAISnowDirector.parsePlan("{\"response\":\"\"}", "m", 1L) == null, "response vide -> null");
        check(MotherAISnowDirector.parsePlan("{\"model\":\"m\"}", "m", 1L) == null, "response absent -> null");

        // 8) corps exterieur non JSON -> null (page d'erreur proxy, etc.)
        check(MotherAISnowDirector.parsePlan("<html>502 bad gateway</html>", "m", 1L) == null,
                "corps non JSON -> null");

        System.out.println("ALL PASS (" + run + " checks)");
    }
}
