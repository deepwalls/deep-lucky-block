package deepluckyblock.util;

import java.util.HashMap;
import java.util.Map;

/**
 * T293 (ordre client : « je ne dois avoir a RIEN installer — le mod se
 * met dans mods/ et fonctionne, cree tout de lui-meme ») : LE CERVEAU
 * EMBARQUE de la mere. Remplace la consultation Ollama par defaut :
 * deux petits reseaux de neurones entraines hors-ligne
 * (ci/mother_ai/train_embedded_brain.py) vivent dans CE fichier, en
 * constantes. La forward pass est du Java pur : zero dependance, zero
 * reseau, zero installation — environ 50 microsecondes par damier.
 *
 * <p>1) RADIUS-NET (8 features globales -> rayon 1/2/3) : fondu large sur
 * damier mixte ET plat, resserre sur eau/falaise/uniforme ; il lisse les
 * frontieres de decision du professeur-a-regles (pas de saut sec).
 * 2) BOOST-NET (6 features par enfant -> aura [0.75, 1.25]) : phare
 * neigeux isole qui parle fort, ilot sec qui s'efface, eau musellee,
 * escarpe discret.</p>
 *
 * <p>Poids RADIUS entraines : acc professeur 0.9161. BOOST RMSE 0.0264.</p>
 */
public final class MotherSnowBrain {

    private MotherSnowBrain() {}

    // ---- RADIUS-NET : 8 -> 24 (tanh) -> 3 (argmax + 1) ----
    private static final float[] R_W1 = new float[]{0.005614f,0.192252f,0.050896f,0.130368f,0.215821f,-0.110712f,0.062111f,0.058547f,-0.208721f,0.324545f,0.075713f,-0.332005f,-0.038074f,0.384839f,0.038334f,0.194712f,-0.334152f,-0.019394f,0.227571f,0.392110f,0.260975f,0.169655f,-0.440095f,0.144275f,-0.066099f,-0.063129f,-0.365760f,0.460724f,-0.215975f,0.456182f,-0.420277f,0.031391f,0.730945f,0.255683f,0.046313f,-0.070826f,-0.248966f,-0.088481f,0.266050f,0.130097f,-0.446899f,-0.673201f,-0.278302f,0.452838f,-0.658603f,1.020478f,-0.219379f,-0.419214f,-0.091597f,-0.549609f,0.262115f,-0.474892f,-0.196110f,0.732518f,0.155207f,-0.110733f,-0.122680f,-0.256450f,-0.438597f,-0.276823f,0.340396f,-0.614244f,-0.191170f,-0.101149f,0.369928f,-0.393336f,-0.114338f,-0.035278f,0.059888f,0.066047f,0.132188f,0.085868f,-0.348888f,-0.038901f,-0.188017f,0.814040f,-0.430438f,-0.060179f,-0.008835f,-0.123371f,0.327963f,0.542811f,-0.695469f,0.184825f,-0.347073f,0.061979f,0.354026f,0.444807f,-0.251860f,-0.174532f,0.168286f,-0.065755f,-0.089894f,0.581239f,-0.308562f,-0.023540f,-0.086396f,-0.440995f,0.457634f,0.695162f,-0.130521f,0.353654f,-0.069334f,0.375714f,0.558386f,-0.453795f,0.107169f,-0.180708f,-0.321318f,0.239673f,0.010243f,-0.237126f,-0.204792f,0.011088f,-0.012574f,-0.231590f,-0.407600f,0.453333f,0.110751f,0.044136f,-0.422785f,-0.294319f,-0.049164f,-0.107687f,-0.019184f,0.337371f,0.095791f,-0.194664f,-0.029045f,0.350445f,-0.010721f,0.240373f,0.071810f,0.049249f,0.321438f,-0.273086f,-0.401315f,-0.527746f,-0.198808f,-0.467037f,0.146796f,0.065164f,-0.101827f,-0.254248f,0.014156f,-0.720937f,-0.212598f,-0.100661f,-0.296154f,0.162038f,-0.274667f,0.088278f,0.414248f,-0.067448f,-0.541009f,0.246291f,0.190928f,-0.134706f,-0.555982f,0.913115f,-0.513678f,-0.221447f,-0.256089f,-0.125409f,0.152079f,0.094699f,0.212541f,0.396881f,-0.502907f,-0.309132f,-0.563594f,-1.482198f,1.157338f,-1.224267f,0.030894f,0.734827f,-1.924705f,-0.878401f,0.255826f,-0.457393f,0.725714f,-0.672333f,-0.619549f,0.292590f,0.043362f,1.607265f,0.629825f,0.967354f,0.660644f,-1.907345f,-0.218912f,0.219962f};   // 8*24
    private static final float[] R_B1 = new float[]{0.155096f,-0.083904f,0.252931f,0.650013f,-0.575589f,0.624546f,-0.093108f,-0.220891f,0.834319f,0.337949f,-0.412479f,0.094384f,-0.039933f,0.136507f,0.413995f,0.164809f,-0.335884f,-0.351713f,-0.179378f,-0.283980f,-0.258435f,0.914767f,0.041611f,-0.164494f};   // 24
    private static final float[] R_W2 = new float[]{0.250327f,0.095047f,-0.332072f,0.185335f,-0.185272f,0.054772f,0.278625f,-0.035520f,-0.472905f,1.081710f,0.264220f,-1.668981f,-0.502999f,-0.298374f,1.275861f,0.925861f,0.539486f,-1.048543f,-0.085714f,-0.254468f,0.067330f,-0.497069f,0.132973f,0.455875f,1.757230f,0.171782f,-1.958164f,0.367896f,0.001875f,-0.940519f,-0.061165f,-0.461631f,0.417863f,0.327866f,-0.057094f,-0.337577f,-0.829708f,0.273217f,0.279360f,0.493833f,-0.094831f,-0.575868f,0.447469f,-0.169348f,-0.651587f,-0.199992f,0.435797f,-0.184856f,0.142960f,-0.355097f,0.575246f,-1.483438f,0.242608f,1.331104f,-0.727640f,-0.256726f,0.317374f,-0.816605f,0.047581f,0.527324f,-0.650948f,0.325865f,0.669048f,1.834539f,0.728208f,-1.851709f,0.081184f,0.039567f,-0.077406f,-0.373036f,-0.142677f,0.155750f};   // 24*3
    private static final float[] R_B2 = new float[]{-0.048124f,0.678475f,-0.630351f};   // 3

    // ---- BOOST-NET : 6 -> 24 (tanh) -> 1 (tanh) ----
    private static final float[] B_W1 = new float[]{-0.553300f,-0.578313f,0.263920f,-0.174983f,0.837646f,0.369980f,0.121921f,-0.220208f,0.049045f,0.144916f,-0.616453f,0.498866f,0.253342f,0.791820f,0.264380f,0.191319f,0.717418f,-0.610986f,-0.303859f,0.186205f,-0.278462f,-0.532617f,-0.206541f,-0.460665f,0.027071f,-0.743788f,0.350877f,0.195021f,0.858141f,-0.057641f,0.537646f,-0.290774f,0.441818f,-0.469384f,0.645545f,-0.107956f,-0.444027f,-0.075330f,-0.367489f,-0.054826f,-0.104587f,0.148523f,-0.110212f,0.199108f,0.275818f,-0.561600f,0.202313f,0.387583f,-0.007924f,0.141830f,-0.003447f,0.374358f,-0.105465f,-0.033725f,0.240662f,-0.321449f,-0.273949f,0.093632f,0.652048f,0.342513f,0.172302f,0.129075f,0.221239f,-0.096393f,-0.149539f,-0.092759f,0.169624f,-0.361590f,0.410217f,0.641211f,-0.075256f,-0.090971f,0.409093f,-0.704904f,0.338099f,0.086223f,-0.252541f,0.478090f,-0.071072f,0.229070f,0.073295f,0.421699f,-0.173135f,0.052470f,-0.749984f,0.138484f,-0.117053f,-0.229581f,0.194523f,0.474573f,0.378192f,0.108722f,-0.007881f,0.142606f,0.071083f,0.727833f,-0.090793f,0.617718f,0.177311f,-0.227336f,0.212451f,-0.319517f,-0.856348f,-0.727610f,0.743348f,0.510234f,0.024888f,0.087133f,0.420835f,-0.264046f,0.364855f,-0.548469f,-0.317540f,0.117431f,0.092742f,-0.354018f,0.027828f,0.560208f,0.393858f,-0.212445f,0.552704f,-0.420434f,0.189252f,-0.154049f,0.419311f,-0.191988f,-0.162432f,-0.012256f,0.042904f,0.235936f,0.502829f,0.181900f,0.161572f,0.409673f,-0.248863f,0.357607f,-0.668435f,0.088828f,-0.084849f,-0.022724f,0.227998f,-0.103930f,-0.434420f,-0.265305f};   // 6*24
    private static final float[] B_B1 = new float[]{0.019360f,-0.003435f,-0.000595f,-0.033200f,-0.004486f,-0.017392f,-0.007453f,-0.018568f,-0.017970f,0.018320f,-0.033164f,-0.048896f,-0.016381f,-0.072712f,-0.003655f,-0.013236f,0.003255f,0.017719f,-0.005710f,0.048552f,-0.017574f,0.025727f,0.008682f,-0.005508f};   // 24
    private static final float[] B_W2 = new float[]{-0.329504f,0.214933f,-0.234173f,0.103838f,-0.224370f,0.289057f,0.136722f,0.296104f,-0.603666f,-0.058855f,-0.224697f,0.276386f,0.233394f,0.388680f,0.163112f,0.259133f,0.418309f,-0.393915f,-0.077335f,-0.242850f,-0.086478f,-0.140002f,-0.244983f,-0.218282f};   // 24
    private static final float[] B_B2 = new float[]{-0.153970f};   // 1

    private static final int RH = 24, BH = 24;

    /** Plan de neige de la zone entiere : rayon + jusqu'a 48 boosts. */
    public static MotherAISnowDirector.SnowPlan decide(TerrainMother m, long latencyMs) {
        // ---- features globales du damier ----
        java.util.List<TerrainMother.Child> kids = m.allChildren();
        int n = 0, snowy = 0, steep = 0;
        float wSum = 0, iSum = 0;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (TerrainMother.Child c : kids) {
            if (c.surfaceYMedian == Integer.MIN_VALUE) continue;
            n++;
            wSum += c.waterRatio; iSum += c.iceRatio;
            if (c.isSnowy()) snowy++;
            if (c.steep) steep++;
            if (c.surfaceYMedian < minY) minY = c.surfaceYMedian;
            if (c.surfaceYMedian > maxY) maxY = c.surfaceYMedian;
        }
        if (n == 0) return new MotherAISnowDirector.SnowPlan(0, Map.of(), latencyMs, "embedded");
        float f = snowy / (float) n;
        float w = wSum / n, i = iSum / n;
        float relief = maxY > minY ? Math.min(1f, (maxY - minY) / 64f) : 0f;
        float gap = Math.min(1f, relief * 1.4f + (steep / (float) n) * 0.6f);
        float[] g = {f, w, i, gap, steep / (float) n, relief,
                     Math.min(1f, n / 576f), 4f * f * (1f - f)};

        // ---- forward RADIUS-NET ----
        float[] h = new float[RH];
        for (int j = 0; j < RH; j++) {
            float acc = R_B1[j];
            for (int k = 0; k < 8; k++) acc += g[k] * R_W1[k * RH + j];
            h[j] = (float) Math.tanh(acc);
        }
        float best = Float.NEGATIVE_INFINITY; int cls = 0;
        for (int c = 0; c < 3; c++) {
            float s = R_B2[c];
            for (int j = 0; j < RH; j++) s += h[j] * R_W2[j * 3 + c];
            if (s > best) { best = s; cls = c; }
        }
        int radius = cls + 1;

        // ---- forward BOOST-NET par enfant ----
        Map<Long, Float> boosts = new HashMap<>();
        float accBoost = 0;
        for (TerrainMother.Child c : kids) {
            if (c.surfaceYMedian == Integer.MIN_VALUE) continue;
            float[] x = {c.snowyRatio, c.waterRatio, c.iceRatio,
                         c.steep ? 1f : 0f, neighborSnow(m, c),
                         Math.min(1f, Math.abs(c.surfaceYMedian - 64f) / 32f)};
            float[] hh = new float[BH];
            for (int j = 0; j < BH; j++) {
                float acc = B_B1[j];
                for (int k = 0; k < 6; k++) acc += x[k] * B_W1[k * BH + j];
                hh[j] = (float) Math.tanh(acc);
            }
            float s = B_B2[0];
            for (int j = 0; j < BH; j++) s += hh[j] * B_W2[j];
            float boost01 = ((float) Math.tanh(s) + 1f) * 0.5f;      // ]0,1[
            float boost = 0.75f + 0.5f * boost01;                     // ]0.75,1.25[
            boost = Math.max(0.75f, Math.min(1.25f, boost));
            accBoost += boost;
            if (boosts.size() < 48 && Math.abs(boost - 1f) > 0.02f)
                boosts.put((((long) c.cx) << 32) | (c.cz & 0xffffffffL), boost);
        }
        return new MotherAISnowDirector.SnowPlan(radius, Map.copyOf(boosts), latencyMs, "embedded");
    }

    /** Moyenne de snowyRatio des 4 voisins de l'enfant (defaut : sa propre
     *  valeur si aucun voisin mesure). */
    private static float neighborSnow(TerrainMother m, TerrainMother.Child c) {
        double sum = 0; int cnt = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            TerrainMother.Child nb = m.child(c.cx + d[0], c.cz + d[1]);
            if (nb != null && nb.present && nb.surfaceYMedian != Integer.MIN_VALUE) {
                sum += nb.snowyRatio; cnt++;
            }
        }
        return cnt == 0 ? c.snowyRatio : (float) (sum / cnt);
    }
}
