package com.fred.realweargpt;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Direct OpenAI GPT-Live transport. Never logs credentials, audio or full events.
 * Listener callbacks are on transport/timer threads: marshal UI updates to the main thread.
 * This client deliberately has no automatic reconnection or fallback model.
 */
public final class LiveClient {
    public interface Listener {
        default void onReady() {}
        default void onAudio(byte[] pcm) {}
        default void onTranscript(String role, String delta) {}
        default void onToolCall(String callId, String name, JSONObject args, String delegationId) {}
        default void onResponseText(String delta) {}
        default void onSource(String title, String url) {}
        default void onState(String state) {}
        default void onError(String message) {}
        default void onClosed(String reason, boolean finalized) {}
        default void onUsage(double seconds, boolean finalized) {}
        default void onBackendStarted() {}
        default void onBackendCompleted() {}
        default void onMuted(boolean muted) {}
    }

    private static final long MAX_QUEUED_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_AUDIO_QUEUED_BYTES = 512L * 1024L;
    private final Object lock = new Object();
    private final Listener listener;
    private final OkHttpClient client;
    private final String endpoint;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "realwear-live-timer"); t.setDaemon(true); return t;
    });
    private WebSocket socket;
    private long generation;
    private boolean ready, active, closing, disposed, finalized, disposeWhenClosed;
    private boolean inputQueued, continuationRequested;
    private double usageSeconds;
    private ScheduledFuture<?> startupTimer, closeTimer;
    private final Map<String, Batch> batches = new LinkedHashMap<>();
    private final Map<String, String> responseByDelegation = new HashMap<>();
    private final Map<String, PendingCall> pendingCalls = new HashMap<>();
    private final Set<String> seenCallIds = new HashSet<>();
    private final Set<String> seenSources = new HashSet<>();

    private static final class Batch {
        final String id;
        boolean completed, needsContinuation;
        int pending;
        Batch(String id) { this.id = id; }
    }
    private static final class PendingCall {
        final Batch batch;
        final JSONObject caller;
        PendingCall(Batch batch, JSONObject caller) { this.batch = batch; this.caller = caller; }
    }

    public LiveClient(Listener listener) {
        this(listener, new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build(), LiveProtocol.ENDPOINT);
    }

    // Package-private injection only for local MockWebServer protocol tests.
    LiveClient(Listener listener, OkHttpClient client, String endpoint) {
        this.listener = listener;
        this.client = client;
        this.endpoint = endpoint;
    }

    public void connect(String apiKey, String liveModel, String backendModel, String voice,
                        JSONArray functionTools, boolean webSearch) {
        final JSONObject start;
        try {
            if (apiKey == null || !apiKey.startsWith("sk-") || apiKey.contains("\n") || apiKey.contains("\r"))
                throw new IllegalArgumentException("Importe une clé API OpenAI valide avant de démarrer.");
            start = LiveProtocol.start(liveModel, backendModel, voice, functionTools, webSearch);
        } catch (Exception e) {
            listener.onError(LiveProtocol.safeMessage(e.getMessage()));
            listener.onClosed("Initialisation refusée avant connexion.", false);
            return;
        }
        synchronized (lock) {
            if (disposed || active) {
                listener.onError(disposed ? "Le client est fermé." : "Une session est déjà ouverte.");
                return;
            }
            final long token = ++generation;
            active = true; ready = false; closing = false; finalized = false;
            inputQueued = false; continuationRequested = false; usageSeconds = 0;
            batches.clear(); responseByDelegation.clear(); pendingCalls.clear(); seenCallIds.clear(); seenSources.clear();
            listener.onState("Connexion à GPT-Live…");
            Request request = new Request.Builder().url(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", "RealWearAssistant/1.0 Android")
                    .build();
            socket = client.newWebSocket(request, new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    synchronized (lock) {
                        if (!current(token) || closing) { ws.cancel(); return; }
                        // Only the start command is permitted before session.started.
                        if (!ws.send(start.toString())) failLocked("Impossible d'initialiser la session.");
                    }
                }
                @Override public void onMessage(WebSocket ws, String text) {
                    synchronized (lock) {
                        if (!current(token)) return;
                        if (text.length() > LiveProtocol.MAX_INCOMING_CHARS) {
                            failLocked("Réponse trop volumineuse pour le casque."); return;
                        }
                        try { handleLocked(new JSONObject(text)); }
                        catch (Exception error) { failLocked("Événement OpenAI invalide : " + error.getClass().getSimpleName()); }
                    }
                }
                @Override public void onMessage(WebSocket ws, ByteString bytes) {
                    synchronized (lock) {
                        if (current(token)) failLocked("Un événement binaire inattendu a été reçu.");
                    }
                }
                @Override public void onClosing(WebSocket ws, int code, String reason) {
                    ws.close(code, null);
                }
                @Override public void onClosed(WebSocket ws, int code, String reason) {
                    synchronized (lock) {
                        if (current(token)) finishLocked(finalized ? "Session terminée." : "Connexion fermée; finalisation non confirmée.", finalized);
                    }
                }
                @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                    synchronized (lock) {
                        if (!current(token)) return;
                        String message;
                        if (response != null) message = httpError(response.code());
                        else message = "Connexion interrompue (" + error.getClass().getSimpleName() + "). Vérifie le Wi-Fi et la date du casque.";
                        failLocked(message);
                    }
                }
            });
            startupTimer = scheduler.schedule(() -> {
                synchronized (lock) {
                    if (current(token) && !ready && !closing) failLocked("GPT-Live n'a pas confirmé le démarrage après 30 secondes.");
                }
            }, 30, TimeUnit.SECONDS);
        }
    }

    public boolean isReady() { synchronized (lock) { return ready && !closing; } }
    public boolean isActive() { synchronized (lock) { return active; } }

    public boolean appendAudio(byte[] pcm, int length) {
        synchronized (lock) {
            if (!ready || closing) return false;
            // Photos can temporarily occupy the output queue; stop accumulating live audio
            // after one photo plus a bounded amount of media rather than exhaust device RAM.
            if (socket != null && socket.queueSize() > MAX_AUDIO_QUEUED_BYTES) {
                return false;
            }
            try { return sendLocked(LiveProtocol.audio(pcm, length)); }
            catch (Exception e) { failLocked(LiveProtocol.safeMessage(e.getMessage())); return false; }
        }
    }

    public boolean sendText(String text) {
        synchronized (lock) {
            if (!ready || closing) return false;
            try {
                if (!sendLocked(LiveProtocol.textItem(text))) return false;
                inputQueued = true;
                maybeContinueLocked();
                return true;
            } catch (Exception e) { listener.onError(LiveProtocol.safeMessage(e.getMessage())); return false; }
        }
    }

    /** For a capture_photo function, invoke this before completeTool(callId,...). */
    public boolean sendImage(byte[] jpeg, String question) {
        synchronized (lock) {
            if (!ready || closing) return false;
            try {
                if (!sendLocked(LiveProtocol.imageItem(jpeg, question))) return false;
                inputQueued = true;
                maybeContinueLocked();
                return true;
            } catch (Exception e) { listener.onError(LiveProtocol.safeMessage(e.getMessage())); return false; }
        }
    }

    /** Results are submitted once. All outstanding results must exist before continuation. */
    public boolean completeTool(String callId, JSONObject result) {
        synchronized (lock) {
            if (!ready || closing) return false;
            PendingCall pending = pendingCalls.get(callId);
            if (pending == null) return false;
            try {
                if (!sendLocked(LiveProtocol.toolResult(callId, result, pending.caller))) return false;
                pendingCalls.remove(callId);
                pending.batch.pending--;
                pending.batch.needsContinuation = true;
                maybeContinueLocked();
                return true;
            } catch (Exception e) { failLocked("Impossible de transmettre le résultat de fonction."); return false; }
        }
    }

    public void mute(boolean value) {
        synchronized (lock) {
            if (!ready || closing) return;
            try { sendLocked(LiveProtocol.event(value ? "session.input_audio.mute" : "session.input_audio.unmute")); }
            catch (JSONException e) { failLocked("Commande audio invalide."); }
        }
    }

    public void appendContext(String text) {
        synchronized (lock) {
            if (!ready || closing) return;
            try { sendLocked(LiveProtocol.context(text)); }
            catch (Exception e) { listener.onError(LiveProtocol.safeMessage(e.getMessage())); }
        }
    }

    public void close() {
        synchronized (lock) {
            if (!active || closing) return;
            closing = true;
            cancelTimer(startupTimer);
            if (!ready) { if (socket != null) socket.cancel(); finishLocked("Démarrage annulé.", false); return; }
            ready = false;
            listener.onState("Fermeture de la session…");
            try {
                if (socket == null || !socket.send(LiveProtocol.event("session.close").toString())) {
                    if (socket != null) socket.cancel();
                    finishLocked("Connexion fermée; finalisation non confirmée.", false); return;
                }
            } catch (JSONException e) { if (socket != null) socket.cancel(); finishLocked("Connexion fermée.", false); return; }
            final long token = generation;
            closeTimer = scheduler.schedule(() -> {
                synchronized (lock) {
                    if (current(token)) {
                        if (socket != null) socket.cancel();
                        finishLocked("Délai de fermeture dépassé; finalisation non confirmée.", false);
                    }
                }
            }, 15, TimeUnit.SECONDS);
        }
    }

    /**
     * Activity destruction can request a graceful close without keeping its views alive.
     * The existing close deadline bounds this operation; finalization or its timeout
     * releases the socket, timer executor and HTTP resources automatically.
     */
    public void disposeAfterClose() {
        synchronized (lock) {
            if (disposed) return;
            if (!active) { dispose(); return; }
            disposeWhenClosed = true;
            close();
        }
    }

    /** Stops promptly. Prefer close() first when the activity can await final usage. */
    public void dispose() {
        synchronized (lock) {
            if (disposed) return;
            disposed = true;
            if (socket != null) socket.cancel();
            finishLocked("Client fermé.", finalized);
            scheduler.shutdownNow();
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }

    private boolean current(long token) { return active && token == generation && !disposed; }

    private boolean sendLocked(JSONObject event) {
        if (!ready || closing || socket == null) return false;
        String payload = event.toString();
        if (socket.queueSize() + payload.length() > MAX_QUEUED_BYTES) {
            failLocked("Envoi interrompu : connexion trop lente pour les données en attente."); return false;
        }
        if (!socket.send(payload)) { failLocked("L'envoi à OpenAI a échoué."); return false; }
        return true;
    }

    private void handleLocked(JSONObject event) throws JSONException {
        String type = event.optString("type");
        if ("session.started".equals(type)) {
            if (ready || closing) return;
            ready = true; cancelTimer(startupTimer);
            listener.onState("GPT-Live connecté");
            listener.onReady();
        } else if ("session.closed".equals(type)) {
            JSONObject usage = event.optJSONObject("usage");
            if (usage != null) usageSeconds = usage.optDouble("seconds", usageSeconds);
            finalized = true;
            listener.onUsage(usageSeconds, true);
            if (socket != null) socket.close(1000, "Session finalized");
            finishLocked("Session terminée (" + event.optString("reason", "close_requested") + ").", true);
        } else if ("session.usage.updated".equals(type)) {
            JSONObject usage = event.optJSONObject("usage");
            if (usage != null) usageSeconds = usage.optDouble("seconds", usageSeconds);
            listener.onUsage(usageSeconds, false);
        } else if ("error".equals(type)) {
            JSONObject error = event.optJSONObject("error");
            String message = error == null ? "Erreur de session OpenAI." :
                    "OpenAI [" + error.optString("code", "error") + "] : " + error.optString("message", "Requête refusée.");
            failLocked(LiveProtocol.safeMessage(message));
        } else if (closing) {
            // Keep draining until session.closed; never perform new UI/hardware tools here.
        } else if ("session.output_audio.delta".equals(type)) {
            String encoded = event.optString("delta");
            if (encoded.length() > 512 * 1024) { failLocked("Bloc audio OpenAI trop volumineux."); return; }
            byte[] pcm = Base64.getDecoder().decode(encoded);
            if ((pcm.length & 1) != 0) { failLocked("Bloc PCM16 incomplet reçu."); return; }
            if (pcm.length > 0) listener.onAudio(pcm);
        } else if ("session.input_transcript.delta".equals(type)) {
            listener.onTranscript("user", event.optString("delta"));
        } else if ("session.output_transcript.delta".equals(type)) {
            listener.onTranscript("assistant", event.optString("delta"));
        } else if ("session.input_audio.muted".equals(type)) {
            listener.onMuted(true);
        } else if ("session.input_audio.unmuted".equals(type)) {
            listener.onMuted(false);
        } else if ("session.delegation.created".equals(type)) {
            listener.onState("Analyse en cours…");
            JSONObject delegation = event.optJSONObject("delegation");
            if (delegation != null && delegation.has("response_id")) {
                responseByDelegation.put(delegation.optString("id"), delegation.optString("response_id"));
            }
        } else if ("response.event".equals(type)) {
            JSONObject nested = event.optJSONObject("event");
            if (nested != null) handleResponseLocked(nested, event.optString("delegation_id", ""));
        }
    }

    private Batch batchFor(JSONObject event, String delegation) {
        JSONObject response = event.optJSONObject("response");
        String id = response != null ? response.optString("id") : event.optString("response_id");
        if (id.isEmpty()) id = responseByDelegation.get(delegation);
        if (id == null || id.isEmpty()) id = "delegation:" + delegation;
        responseByDelegation.put(delegation, id);
        Batch batch = batches.get(id);
        if (batch == null) { batch = new Batch(id); batches.put(id, batch); }
        return batch;
    }

    private void handleResponseLocked(JSONObject event, String delegation) throws JSONException {
        String type = event.optString("type");
        if ("response.created".equals(type)) {
            Batch batch = batchFor(event, delegation);
            batch.completed = false;
            continuationRequested = false;
            seenSources.clear();
            listener.onBackendStarted();
        } else if ("response.output_text.delta".equals(type)) {
            listener.onResponseText(event.optString("delta"));
        } else if ("response.output_text.annotation.added".equals(type)) {
            sourceLocked(event.optJSONObject("annotation"));
        } else if ("response.output_item.done".equals(type)) {
            JSONObject item = event.optJSONObject("item");
            if (item == null) return;
            String itemType = item.optString("type");
            if ("function_call".equals(itemType)) {
                String callId = item.optString("call_id");
                if (callId.isEmpty() || !seenCallIds.add(callId)) return;
                if (seenCallIds.size() > 1000) { failLocked("Trop d'appels de fonctions dans cette session."); return; }
                Batch batch = batchFor(event, delegation);
                batch.pending++;
                pendingCalls.put(callId, new PendingCall(batch, item.optJSONObject("caller")));
                try {
                    String rawArgs = item.optString("arguments", "{}");
                    if (rawArgs.length() > 64000) throw new JSONException("Arguments trop longs");
                    listener.onToolCall(callId, item.optString("name"), new JSONObject(rawArgs), delegation);
                } catch (JSONException error) {
                    completeTool(callId, new JSONObject().put("ok", false).put("error", "Arguments de fonction invalides."));
                }
            } else if ("message".equals(itemType)) {
                JSONArray content = item.optJSONArray("content");
                if (content != null) for (int i = 0; i < content.length(); i++) {
                    JSONObject part = content.optJSONObject(i);
                    JSONArray annotations = part == null ? null : part.optJSONArray("annotations");
                    if (annotations != null) for (int j = 0; j < annotations.length(); j++) sourceLocked(annotations.optJSONObject(j));
                }
            }
        } else if ("response.completed".equals(type)) {
            Batch batch = batchFor(event, delegation);
            batch.completed = true;
            listener.onBackendCompleted();
            maybeContinueLocked();
        } else if ("response.failed".equals(type) || "response.incomplete".equals(type)) {
            JSONObject response = event.optJSONObject("response");
            JSONObject error = response == null ? null : response.optJSONObject("error");
            String message = error == null ? "Le modèle délégué n'a pas terminé sa réponse." : error.optString("message");
            failLocked("Analyse interrompue : " + LiveProtocol.safeMessage(message));
        } else if (type.startsWith("response.web_search_call.")) {
            listener.onState(type.endsWith("completed") ? "Recherche terminée" : "Recherche Web en cours…");
        }
    }

    private void sourceLocked(JSONObject annotation) {
        if (annotation == null || !"url_citation".equals(annotation.optString("type"))) return;
        String url = annotation.optString("url");
        if (url.length() > 3000 || !url.startsWith("https://") || !seenSources.add(url)) return;
        if (seenSources.size() <= 200) listener.onSource(annotation.optString("title", url), url);
    }

    private void maybeContinueLocked() throws JSONException {
        if (!ready || closing || continuationRequested || !pendingCalls.isEmpty()) return;
        boolean needs = inputQueued;
        for (Batch batch : batches.values()) {
            if (!batch.completed) return;
            if (batch.pending != 0) return;
            needs |= batch.needsContinuation;
        }
        if (!needs) return;
        if (sendLocked(LiveProtocol.event("response.create"))) {
            continuationRequested = true;
            inputQueued = false;
            batches.clear();
            responseByDelegation.clear();
        }
    }

    private void failLocked(String message) {
        // Finalize our local state before notifying application code to avoid reentrant close races.
        if (socket != null) socket.cancel();
        boolean wasActive = active;
        active = false; ready = false; closing = false;
        ++generation;
        cancelTimer(startupTimer); cancelTimer(closeTimer);
        socket = null; pendingCalls.clear(); batches.clear(); responseByDelegation.clear();
        listener.onError(LiveProtocol.safeMessage(message));
        if (wasActive) listener.onClosed("Session interrompue; finalisation non confirmée.", false);
        if (disposeWhenClosed) dispose();
    }

    private void finishLocked(String reason, boolean finalUsageConfirmed) {
        boolean wasActive = active;
        active = false; ready = false; closing = false;
        ++generation;
        cancelTimer(startupTimer); cancelTimer(closeTimer);
        socket = null; pendingCalls.clear(); batches.clear(); responseByDelegation.clear();
        if (wasActive) listener.onClosed(reason, finalUsageConfirmed);
        if (disposeWhenClosed) dispose();
    }

    private static void cancelTimer(ScheduledFuture<?> timer) { if (timer != null) timer.cancel(false); }

    private static String httpError(int code) {
        switch (code) {
            case 401: return "Clé API refusée (401). Vérifie ou remplace la clé importée.";
            case 403: return "Accès GPT-Live refusé (403). Vérifie les permissions et l'accès du projet.";
            case 404: return "Point d'accès ou modèle indisponible (404). Vérifie les modèles configurés.";
            case 429: return "Quota ou limite de débit OpenAI atteint (429). Vérifie le compte API.";
            default: return "Échec de connexion OpenAI (HTTP " + code + "). Réessaie depuis l'application.";
        }
    }
}
