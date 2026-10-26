package deepluckyblock.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * T290 (ordre client 25/10 : « new idee, add ia to mother ») — DIRECTEUR IA
 * optionnel de la MERE. Principe : la mere peut CONSULTER un modele local
 * gratuit (Ollama, http://127.0.0.1:11434) comme un directeur artistique
 * qui lit le damier d'enfants (texte compact) et repond en JSON son
 * avis : rayon du fondu de neige + boosts/baiser d'aura par enfant.
 *
 * <p>CONTRAINTES DE PRODUCTION (machine bas de gamme du client, Intel
 * iGPU — le blueprint externe collee (langchain4j + ollama HTTP) est
 * respecte dans l'esprit, MAIS sans AUCUNE dependance embarquee) :</p>
 * <ul>
 *  <li>zero dependance ajoutee au jar : java.net.http.HttpClient (JDK 21
 *      natif) + Gson (deja fourni par Minecraft/NeoForge) ;</li>
 *  <li>ACTIVE PAR DEFAUT depuis T292 (ordre client 26/10 :
 *      « non je la veux active !! ») : la mere consulte Ollama local
 *      (127.0.0.1:11434, modele qwen2.5:3b) a chaque zone. Coupure
 *      explicite : {@code -Ddlb.motherAi=off} (ou env
 *      {@code DLB_MOTHER_AI=off}). Modele URL ajustables :
 *      {@code -Ddlb.motherAi=ollama:MODELE@URL}. Un joueur SANS Ollama ne
 *      paye qu'UN probe 1,5 s async au premier structure (memorise, puis
 *      court-circuit pour toute la session — zero cout ensuite) ;</li>
 *  <li>l'IA ne pose JAMAIS de bloc : elle ne fait que NUDGER (borne ±25 %)
 *      l'aura de neige et choisir le rayon 1..3 ; toute decision est
 *      clamped/censuree cote mere ;</li>
 *  <li>timeout dur 9 s + fallback integral = gaussien deterministe T289 ;
 *      aucune erreur IA ne casse la chaine ;</li>
 *  <li>determinisme preserve : la mere ATTEND le plan au plus 1500 ms a la
 *      premiere demande de densite ; passe ce delai, la zone entiere reste
 *      sur le mode deterministe (pas de changement en cours de sweep qui
 *      creerait des lignes).</li>
 * </ul>
 */
public final class MotherAISnowDirector {

    private MotherAISnowDirector() {}

    /** Plan IA valide : rayon du fondu (1..3) + multiplicateurs d'aura par enfant. */
    public record SnowPlan(int radiusChunks, Map<Long, Float> childAuraBoost, long latencyMs, String model) {}

    private static final String PROP = "dlb.motherAi";
    private static final String ENV = "DLB_MOTHER_AI";
    private static final String DEFAULT_URL = "http://127.0.0.1:11434";
    private static final String DEFAULT_MODEL = "qwen2.5:3b";
    private static final int PROBE_TIMEOUT_MS = 1500;
    private static final int REQUEST_TIMEOUT_MS = 9000;

    /** Pool DAO monofil (daemon) : jamais de fuite de threads, jamais le pool commun. */
    private static final Executor AI_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DLB-MotherAI");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // T292 (bug trouve par les tests mock : interblocage) — NE PAS passer
    // AI_POOL ici : avec un executor MONOFIL sur le client ET un send()
    // bloquant sur ce meme thread unique, les taches internes du client
    // (timeouts, livraison du corps) attendent ce thread qui attend la
    // reponse -> deadlock silencieux, la consultation mourrait TOUJOURS en
    // silence et tout retombait sur T289. Le client garde son pool interne
    // (scheduled + selector) ; AI_POOL ne sert qu'a serialiser les send()
    // bloquants (une consultation a la fois, jamais deux modeles en vol).
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(PROBE_TIMEOUT_MS))
            .build();

    // Etat : -1 = pas teste, 0 = indisponible, 1 = disponible.
    private static volatile int ollamaState = -1;
    private static volatile String lastDiag = "non teste";
    /** T292 : quand le probe echoue, on reteste au bout de 10 min (le joueur
     *  peut demarrer Ollama APRES le jeu) au lieu de geler la session. */
    private static final long REPROBE_AFTER_MS = 10L * 60_000L;
    private static volatile long lastProbeMs = 0L;

    /** Config : null = COUPURE EXPLICITE ; sinon "MODEL@URL".
     *  T292 : ACTIVE PAR DEFAUT — l'absence totale de propriete/env
     *  correspond au modele+URL par defaut ; seuls off/false/0 coupent. */
    private static String readConfig() {
        String v = System.getProperty(PROP);
        if (v == null || v.isEmpty()) {
            try { v = System.getenv(ENV); } catch (Throwable ignored) {}
        }
        if (v == null || v.isEmpty()) {
            return DEFAULT_MODEL + "@" + DEFAULT_URL;   // T292 : par defaut ON
        }
        if (v.equalsIgnoreCase("off") || v.equalsIgnoreCase("false") || v.equals("0")) {
            return null;
        }
        if (v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true") || v.equals("1")) {
            return DEFAULT_MODEL + "@" + DEFAULT_URL;
        }
        if (v.startsWith("ollama:")) {
            v = v.substring("ollama:".length());
            if (v.isEmpty()) return DEFAULT_MODEL + "@" + DEFAULT_URL;
            if (!v.contains("@")) return v + "@" + DEFAULT_URL;
            return v;
        }
        // Valeur libre comprise comme un simple nom de modele.
        return v + "@" + DEFAULT_URL;
    }

    public static boolean isEnabled() {
        return readConfig() != null;
    }

    /** Probe GET /api/tags (1,5 s max), memoise ; re-probe toutes les 10 min
     *  tant qu'il est KO (T292 : Ollama lance en cours de session). */
    private static boolean ollamaReachable(String baseUrl) {
        if (ollamaState == 1) return true;
        if (ollamaState == 0 && System.currentTimeMillis() - lastProbeMs < REPROBE_AFTER_MS) return false;
        lastProbeMs = System.currentTimeMillis();
        try {
            HttpRequest probe = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofMillis(PROBE_TIMEOUT_MS)).GET().build();
            int code = HTTP.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode();
            ollamaState = (code >= 200 && code < 300) ? 1 : 0;
            lastDiag = "HTTP " + code;
            if (ollamaState == 1)
                System.out.println("[DLB T290] IA mere : Ollama DETECTE sur " + baseUrl + " -- consultation active.");
        } catch (Throwable t) {
            ollamaState = 0;
            lastDiag = t.getClass().getSimpleName() + " : " + String.valueOf(t.getMessage());
        }
        return ollamaState == 1;
    }

    /** Cle de chunk identique a celle de degrade/zone : (cx << 32) | (cz & 0xffffffff). */
    private static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) | (cz & 0xffffffffL);
    }

    /**
     * Serialise le damier de la mere en texte compact pour le modele :
     * une ligne par enfant renseigne, bornes 24x24 (= 576 enfants max,
     * ~10 Ko — confortable pour un petit modele local).
     */
    private static String serializeBoard(TerrainMother m, StringBuilder out, int[] stats) {
        out.append("GRILLE CHUNKS ZONE STRUCTURE (damier mere/enfants)\n");
        out.append("Format : cx cz medianY minY maxY snowy% eau% glace% escarpe(0/1)\n");
        int n = 0, snowy = 0;
        for (TerrainMother.Child c : m.allChildren()) {
            if (c == null || !c.present || c.surfaceYMedian == Integer.MIN_VALUE) continue;
            if (n >= 576) break;
            out.append(c.cx).append(' ').append(c.cz).append(' ')
               .append(c.surfaceYMedian).append(' ').append(c.surfaceYMin).append(' ').append(c.surfaceYMax)
               .append(' ').append(Math.round(c.snowyRatio * 100f))
               .append(' ').append(Math.round(c.waterRatio * 100f))
               .append(' ').append(Math.round(c.iceRatio * 100f))
               .append(' ').append(c.steep ? 1 : 0).append('\n');
            n++;
            if (c.isSnowy()) snowy++;
        }
        stats[0] = n; stats[1] = snowy;
        return out.toString();
    }

    private static String buildPrompt(String board, int children, int snowy) {
        return "Tu es la MERE, directrice terrain d'un mod Minecraft NeoForge. Les CHUNKS (enfants) "
             + "de la zone structure viennent de te rapporter leurs mesures VIERGES.\n"
             + board
             + "\nBilan : " + children + " enfants renseignes, " + snowy + " entierement neigeux.\n"
             + "Decide de l'aura de neige multi-chunks (zero delimitation de chunk visible).\n"
             + "Reponds UNIQUEMENT un JSON valide, sans aucun texte autour, de la forme :\n"
             + "{\"radius\":<1|2|3>,\"boost\":[[cx,cz,facteur],...]}\n"
             + "radius = rayon du fondu en chunks (1 = couture seule, 3 = si le damier est tres mixte neige/non-neige, l'aura s'echappe loin).\n"
             + "boost = liste d'au maximum 48 triplets [cx, cz, facteur] avec facteur entre 0.75 et 1.25 "
             + "pour renforcer (1.1..1.25) l'aura autour d'un enfant fortement neigeux, ou l'attenuer (0.75..0.9) "
             + "autour d'un enfant isole. Laisse la liste vide si le damier est uniforme.";
    }

    /** Parse la reponse Ollama (/api/generate, format json) → SnowPlan clamped, null si illisible. */
    static SnowPlan parsePlan(String body, String model, long latencyMs) {
        try {
            JsonObject outer = JsonParser.parseString(body).getAsJsonObject();
            String inner = outer.has("response") ? outer.get("response").getAsString() : null;
            if (inner == null || inner.isEmpty()) return null;
            // Le modele a parfois tendance a entourer le JSON de ``` — on isole l'objet.
            int a = inner.indexOf('{'), b = inner.lastIndexOf('}');
            if (a < 0 || b <= a) return null;
            JsonObject plan = JsonParser.parseString(inner.substring(a, b + 1)).getAsJsonObject();
            int radius = 0;
            if (plan.has("radius")) radius = plan.get("radius").getAsInt();
            // radius 0/absent = l'IA n'a pas tranche : la mere garde SON
            // evaluation du damier (evaluateSnowRadius). Sinon clamp 1..3.
            radius = radius > 0 ? Math.max(1, Math.min(3, radius)) : 0;
            Map<Long, Float> boosts = new HashMap<>();
            if (plan.has("boost") && plan.get("boost").isJsonArray()) {
                JsonArray arr = plan.getAsJsonArray("boost");
                int kept = 0;
                for (JsonElement el : arr) {
                    if (kept >= 48 || !el.isJsonArray()) continue;
                    JsonArray t = el.getAsJsonArray();
                    if (t.size() < 3) continue;
                    int cx = t.get(0).getAsInt();
                    int cz = t.get(1).getAsInt();
                    float f = t.get(2).getAsFloat();
                    f = Math.max(0.75f, Math.min(1.25f, f));
                    boosts.put(chunkKey(cx, cz), f);
                    kept++;
                }
            }
            return new SnowPlan(radius, Collections.unmodifiableMap(boosts), latencyMs, model);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Consultation ASYNCHRONE (fire & forget) : si l'IA est desactivee ou
     * indisponible, renvoie immediatement un futur deja termine a null.
     * Le resultat est applique a la mere via applyAiPlan des que possible.
     */
    public static CompletableFuture<SnowPlan> consultAsync(TerrainMother mother) {
        String cfg = readConfig();
        if (cfg == null || mother == null) {
            return CompletableFuture.completedFuture(null);
        }
        final String[] parts = cfg.split("@", 2);
        final String model = parts[0];
        final String baseUrl = parts.length > 1 ? parts[1] : DEFAULT_URL;
        return CompletableFuture.supplyAsync(() -> {
            if (!ollamaReachable(baseUrl)) {
                System.out.println("[DLB T290] IA mere : demandee mais Ollama indisponible (" + lastDiag
                        + ") — gaussien deterministe T289 conserve pour cette zone.");
                return null;
            }
            long t0 = System.currentTimeMillis();
            try {
                StringBuilder board = new StringBuilder(16 * 1024);
                int[] stats = new int[2];
                // Snapshot immutable AVANT envoi (copie texte : zero reference
                // vers le monde ou la mere vivante pendant l'appel reseau).
                String payload = buildPrompt(serializeBoard(mother, board, stats), stats[0], stats[1]);
                JsonObject reqBody = new JsonObject();
                reqBody.addProperty("model", model);
                reqBody.addProperty("prompt", payload);
                reqBody.addProperty("stream", false);
                reqBody.addProperty("format", "json");
                JsonObject opts = new JsonObject();
                opts.addProperty("temperature", 0.2);
                reqBody.add("options", opts);
                HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
                        .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(reqBody.toString()))
                        .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                long dt = System.currentTimeMillis() - t0;
                if (resp.statusCode() == 404) {
                    System.out.println("[DLB T290] IA mere : modele '" + model
                            + "' absent d'Ollama -- installez-le une fois :  ollama pull " + model
                            + "  (fallback deterministe T289 en attendant).");
                    return null;
                }
                if (resp.statusCode() / 100 != 2) {
                    System.out.println("[DLB T290] IA mere : Ollama HTTP " + resp.statusCode() + " — fallback deterministe.");
                    return null;
                }
                SnowPlan plan = parsePlan(resp.body(), model, dt);
                if (plan == null) {
                    System.out.println("[DLB T290] IA mere : reponse illisible apres " + dt + " ms — fallback deterministe.");
                } else {
                    System.out.println("[DLB T290] IA mere : plan recu du modele " + model + " en " + dt
                            + " ms : radius=" + plan.radiusChunks() + ", boosts=" + plan.childAuraBoost().size());
                }
                return plan;
            } catch (Throwable t) {
                System.out.println("[DLB T290] IA mere : consultation en echec ("
                        + t.getClass().getSimpleName() + " : " + t.getMessage()
                        + ") — fallback deterministe T289.");
                return null;
            }
        }, AI_POOL);
    }
}
