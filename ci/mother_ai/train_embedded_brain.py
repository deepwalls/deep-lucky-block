#!/usr/bin/env python3
"""T293 : entraine LE CERVEAU EMBARQUE de la mere (ordre client :
« le mod doit se mettre dans mods/ et fonctionner, creer tout directement
de lui meme sans avoir besoin d'un tiers » -> FINI la consultation Ollama
par defaut : un petit reseau de neurones VIT dans le jar).

Deux micro-reseaux :
  1) RADIUS-NET  : 8 features globales du damier -> 3 classes (rayon 1/2/3),
     appris d'un PROFESSEUR-au-regles plus nuance que le T289 historique :
     le fondu s'elargit sur damier mixte ET plat, se resserre sur l'eau,
     les falaises et les damiers uniformes. Le reseau IMITE puis LISSER
     les frontieres de decision (pas de saut sec d'un bloc de la regle).
  2) BOOST-NET   : 6 features par enfant -> boost d'aura [0.75, 1.25] :
     phare neigeux isole qui « parle fort » (corr. 1.25), ilot sec baigne
     de neige qui s'efface pour laisser passer l'aura des voisins,
     enfant aquatique etroite, escarpe discret.

Le script exporte les poids dans la classe Java finale
  mod_project/src/main/java/deepluckyblock/util/MotherSnowBrain.java
qui implemente la forward pass en Java pur (zero dependance, ~0,05 ms par
damier de 576 enfants).

Usage : python3 train_embedded_brain.py [--samples 400000] [--out <fichier.java>]
"""
import argparse
import json
import numpy as np

rng = np.random.default_rng(20261026)

# ---------------------------------------------------------------- professeurs
def teacher_radius(f, w, i, gap, steep, relief):
    """Regle experte continue -> classe 1/2/3. mix = 4f(1-f) vaut 1 au
    damier 50/50 (maximum de melange), 0 sur un damier uniforme."""
    mix = 4.0 * f * (1.0 - f)
    score = 1.0 + 2.0 * mix                    # uniforme->1, mixte->3
    score -= 1.2 * max(0.0, w - 0.10) * 3.0    # eau : ne pas laisser l'aura
                                               # deborder loin des berges
    score -= 1.0 * max(0.0, gap - 0.35)        # falaise : fondu inutile et laid
    score -= 0.8 * max(0.0, steep - 0.20)
    score -= 0.6 * max(0.0, relief - 0.45)
    score += 0.6 * max(0.0, i - 0.35)          # glace : fondu large coherent
    r = int(round(score))
    return max(1, min(3, r))

def teacher_boost(s, w, i, steep, neigh, gap):
    """Facteur voix de l'enfant dans le noyau gaussien."""
    b = 1.0
    b += 0.22 * s * (1.0 - neigh)             # phare neigeux entoure de sec
    b -= 0.18 * (1.0 - s) * min(1.0, 2.0 * neigh)   # ilot sec laisse passer l'aura
    b -= 0.25 * min(1.0, 3.0 * w)             # l'eau ne « parle » pas
    b -= 0.12 * steep
    b -= 0.10 * min(1.0, gap)
    b += 0.06 * i                             # la glace aime la neige
    return max(0.75, min(1.25, b))

# ----------------------------------------------------------- generation donnees
def gen_boards(n):
    """Damiers synthetiques plausibles : f partagee avec dispersion, eau
    rare, glace correlee a la neige, relief/bride correles."""
    f = rng.beta(1.6, 1.6, n)                  # densite de damier
    f = np.clip(f + rng.normal(0, 0.12, n), 0.0, 1.0)
    w = np.clip(rng.beta(0.7, 6.0, n) * (1 - f), 0, 1)
    i = np.clip(rng.beta(0.8, 4.0, n) * f, 0, 1)
    gap = np.clip(rng.beta(1.2, 3.0, n), 0, 1)
    steep = np.clip(gap * 0.7 + rng.normal(0, 0.08, n), 0, 1)
    relief = np.clip(gap * 0.8 + rng.normal(0, 0.10, n), 0, 1)
    fill = np.clip(rng.normal(0.85, 0.15, n), 0.3, 1.0)
    X = np.stack([f, w, i, gap, steep, relief, fill, 4 * f * (1 - f)], axis=1)
    y = np.array([teacher_radius(*row[:6]) for row in X]) - 1    # classes 0/1/2
    return X.astype(np.float32), y.astype(np.int64)

def gen_children(n):
    s = np.clip(rng.beta(1.5, 1.5, n) + rng.normal(0, 0.1, n), 0, 1)
    w = np.clip(rng.beta(0.6, 7.0, n) * (1 - s), 0, 1)
    i = np.clip(rng.beta(0.8, 5.0, n) * s, 0, 1)
    steep = (rng.random(n) < 0.12).astype(np.float32)
    neigh = np.clip(s + rng.normal(0, 0.35, n), 0, 1)     # voisinage correle +
    gap = np.clip(rng.beta(1.0, 4.0, n) * 2.0, 0, 1)      # gap max-norme /2
    X = np.stack([s, w, i, steep, neigh, gap], axis=1)
    y = np.array([teacher_boost(*row) for row in X])
    return X.astype(np.float32), (y.astype(np.float32) - 1.0) / 0.25  # [-1,1]

# ------------------------------------------------------------------- mini-MLP
def init_mlp(din, dh, dout, scale=0.6):
    W1 = rng.normal(0, scale, (din, dh)).astype(np.float64) * np.sqrt(2.0 / din)
    b1 = np.zeros(dh)
    W2 = rng.normal(0, scale, (dh, dout)).astype(np.float64) * np.sqrt(2.0 / dh)
    b2 = np.zeros(dout)
    return [W1, b1, W2, b2]

def forward(p, X, task):
    H = np.tanh(X @ p[0] + p[1])
    Z = H @ p[2] + p[3]
    if task == 'cls':
        Z = Z - Z.max(axis=1, keepdims=True)
        P = np.exp(Z); P /= P.sum(axis=1, keepdims=True)
        return H, P
    Y = np.tanh(Z)                            # resserre dans ]-1,1[
    return H, Y

def train(X, y, task, din, dh, dout, epochs=60, bs=4096, lr=0.05, seed=7):
    p = init_mlp(din, dh, dout)
    n = len(X)
    for ep in range(epochs):
        idx = rng.permutation(n)
        for off in range(0, n, bs):
            sl = idx[off:off + bs]; Xb, yb = X[sl], y[sl]
            H1, out = forward(p, Xb, task)
            m = len(Xb)
            if task == 'cls':
                dZ = out.copy(); dZ[np.arange(m), yb] -= 1.0; dZ /= m
            else:
                dZ = (out - yb.reshape(-1, 1)) * (1 - out ** 2) / m
            dW2 = H1.T @ dZ; db2 = dZ.sum(0)
            dH = (dZ @ p[2].T) * (1 - H1 ** 2)
            dW1 = Xb.T @ dH; db1 = dH.sum(0)
            for k, g in ((0, dW1), (1, db1), (2, dW2), (3, db2)):
                p[k] -= lr * g
        if (ep + 1) % 20 == 0:
            lr *= 0.6
    return p

def accuracy(p, X, y):
    _, P = forward(p, X, 'cls')
    return float((P.argmax(1) == y).mean())

def mse(p, X, y):
    _, Y = forward(p, X, 'reg')
    return float(np.mean((Y[:, 0] - y) ** 2))

# ------------------------------------------------------------------- export Java
def jfloats(a):
    return ','.join(f'{v:.6f}f' if abs(v) < 1e6 or np.isfinite(v) else '0f' for v in np.ravel(a))

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--samples', type=int, default=400_000)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()

    X, y = gen_boards(a.samples)
    pn = train(X[:a.samples * 3 // 4], y[:a.samples * 3 // 4], 'cls', 8, 24, 3)
    te = slice(a.samples * 3 // 4, a.samples)
    acc = accuracy(pn, X[te], y[te])
    # distance moyenne a la classe du professeur (0, 1 ou 2 crans)
    _, P = forward(pn, X[te], 'cls')
    off = np.abs(P.argmax(1) - y[te])
    print(f'radius-net : acc={acc:.4f}  (+/-1 cran : {(off <= 1).mean():.4f})')

    Xc, yc = gen_children(a.samples)
    pb = train(Xc[:a.samples * 3 // 4], yc[:a.samples * 3 // 4], 'reg', 6, 24, 1, epochs=50)
    err = mse(pb, Xc[te], yc[te]) ** 0.5 * 0.25
    print(f'boost-net : RMSE={err:.4f} (en unites boost, objectif < 0.05)')

    W1, b1, W2, b2 = pn
    V1, c1, V2, c2 = pb
    cls = f"""package deepluckyblock.util;

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
 * <p>Poids RADIUS entraines : acc professeur {acc:.4f}. BOOST RMSE {err:.4f}.</p>
 */
public final class MotherSnowBrain {{

    private MotherSnowBrain() {{}}

    // ---- RADIUS-NET : 8 -> 24 (tanh) -> 3 (argmax + 1) ----
    private static final float[] R_W1 = new float[]{{{jfloats(W1)}}};   // 8*24
    private static final float[] R_B1 = new float[]{{{jfloats(b1)}}};   // 24
    private static final float[] R_W2 = new float[]{{{jfloats(W2)}}};   // 24*3
    private static final float[] R_B2 = new float[]{{{jfloats(b2)}}};   // 3

    // ---- BOOST-NET : 6 -> 24 (tanh) -> 1 (tanh) ----
    private static final float[] B_W1 = new float[]{{{jfloats(V1)}}};   // 6*24
    private static final float[] B_B1 = new float[]{{{jfloats(c1)}}};   // 24
    private static final float[] B_W2 = new float[]{{{jfloats(V2)}}};   // 24
    private static final float[] B_B2 = new float[]{{{jfloats(c2)}}};   // 1

    private static final int RH = 24, BH = 24;

    /** Plan de neige de la zone entiere : rayon + jusqu'a 48 boosts. */
    public static MotherAISnowDirector.SnowPlan decide(TerrainMother m, long latencyMs) {{
        // ---- features globales du damier ----
        java.util.List<TerrainMother.Child> kids = m.allChildren();
        int n = 0, snowy = 0, steep = 0;
        float wSum = 0, iSum = 0;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (TerrainMother.Child c : kids) {{
            if (c.surfaceYMedian == Integer.MIN_VALUE) continue;
            n++;
            wSum += c.waterRatio; iSum += c.iceRatio;
            if (c.isSnowy()) snowy++;
            if (c.steep) steep++;
            if (c.surfaceYMedian < minY) minY = c.surfaceYMedian;
            if (c.surfaceYMedian > maxY) maxY = c.surfaceYMedian;
        }}
        if (n == 0) return new MotherAISnowDirector.SnowPlan(0, Map.of(), latencyMs, "embedded");
        float f = snowy / (float) n;
        float w = wSum / n, i = iSum / n;
        float relief = maxY > minY ? Math.min(1f, (maxY - minY) / 64f) : 0f;
        float gap = Math.min(1f, relief * 1.4f + (steep / (float) n) * 0.6f);
        float[] g = {{f, w, i, gap, steep / (float) n, relief,
                     Math.min(1f, n / 576f), 4f * f * (1f - f)}};

        // ---- forward RADIUS-NET ----
        float[] h = new float[RH];
        for (int j = 0; j < RH; j++) {{
            float acc = R_B1[j];
            for (int k = 0; k < 8; k++) acc += g[k] * R_W1[k * RH + j];
            h[j] = (float) Math.tanh(acc);
        }}
        float best = Float.NEGATIVE_INFINITY; int cls = 0;
        for (int c = 0; c < 3; c++) {{
            float s = R_B2[c];
            for (int j = 0; j < RH; j++) s += h[j] * R_W2[j * 3 + c];
            if (s > best) {{ best = s; cls = c; }}
        }}
        int radius = cls + 1;

        // ---- forward BOOST-NET par enfant ----
        Map<Long, Float> boosts = new HashMap<>();
        float accBoost = 0;
        for (TerrainMother.Child c : kids) {{
            if (c.surfaceYMedian == Integer.MIN_VALUE) continue;
            float[] x = {{c.snowyRatio, c.waterRatio, c.iceRatio,
                         c.steep ? 1f : 0f, neighborSnow(m, c),
                         Math.min(1f, Math.abs(c.surfaceYMedian - 64f) / 32f)}};
            float[] hh = new float[BH];
            for (int j = 0; j < BH; j++) {{
                float acc = B_B1[j];
                for (int k = 0; k < 6; k++) acc += x[k] * B_W1[k * BH + j];
                hh[j] = (float) Math.tanh(acc);
            }}
            float s = B_B2[0];
            for (int j = 0; j < BH; j++) s += hh[j] * B_W2[j];
            float boost01 = ((float) Math.tanh(s) + 1f) * 0.5f;      // ]0,1[
            float boost = 0.75f + 0.5f * boost01;                     // ]0.75,1.25[
            boost = Math.max(0.75f, Math.min(1.25f, boost));
            accBoost += boost;
            if (boosts.size() < 48 && Math.abs(boost - 1f) > 0.02f)
                boosts.put((((long) c.cx) << 32) | (c.cz & 0xffffffffL), boost);
        }}
        return new MotherAISnowDirector.SnowPlan(radius, Map.copyOf(boosts), latencyMs, "embedded");
    }}

    /** Moyenne de snowyRatio des 4 voisins de l'enfant (defaut : sa propre
     *  valeur si aucun voisin mesure). */
    private static float neighborSnow(TerrainMother m, TerrainMother.Child c) {{
        double sum = 0; int cnt = 0;
        int[][] dirs = {{{{1, 0}}, {{-1, 0}}, {{0, 1}}, {{0, -1}}}};
        for (int[] d : dirs) {{
            TerrainMother.Child nb = m.child(c.cx + d[0], c.cz + d[1]);
            if (nb != null && nb.present && nb.surfaceYMedian != Integer.MIN_VALUE) {{
                sum += nb.snowyRatio; cnt++;
            }}
        }}
        return cnt == 0 ? c.snowyRatio : (float) (sum / cnt);
    }}
}}
"""
    with open(a.out, 'w') as fh:
        fh.write(cls)
    print(f'ecrit : {a.out}')
    print(json.dumps({'acc_radius': acc, 'rmse_boost': err}))

if __name__ == '__main__':
    main()
