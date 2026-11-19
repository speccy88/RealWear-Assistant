package com.fred.realweargpt;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.Call;

public final class MainActivity extends Activity {
    private enum State { IDLE, PREPARING, CONNECTING, LIVE, CLOSING }
    private static final String[] NUMBERS_FR = {"un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze"};
    private static final String[] NUMBERS_EN = {"one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven"};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AppConfig config;
    private SecretStore secrets;
    private WearHfController wearhf;
    private AudioEngine audio;
    private CameraCapture camera;
    private ImageLoader images;
    private ImageGenerator imageGenerator;
    private Call imageRequest;
    private String sessionApiKey;
    private LiveClient live;
    private ScreenDeck deck;
    private TextView status, duration, caption;
    private LinearLayout mainMenu, secondaryMenu;
    private Button startButton, photoButton, nextButton, menuButton;
    private State state = State.IDLE;
    private boolean resumed, destroyed, muted, showingConversation, cameraBusy, demo;
    private boolean askToStartAfterPermission, pendingStartAfterResume, fatalError, audioReleasing, explicitDisplay;
    private boolean transportClosed = true;
    private volatile int epoch;
    private int demoStep;
    private long startedAt, lastScreenUpdate;
    private final StringBuilder conversation = new StringBuilder(), backendText = new StringBuilder();
    private String transcriptRole = "";
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (state == State.LIVE) {
                long seconds = Math.max(0, (SystemClock.elapsedRealtime() - startedAt) / 1000);
                duration.setText(String.format(Locale.CANADA_FRENCH, "%02d:%02d", seconds / 60, seconds % 60));
                if (seconds >= config.maxMinutes() * 60L) stopSession("Durée maximale atteinte. Tu peux démarrer une nouvelle session.");
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new AppConfig(this); secrets = new SecretStore(this);
        wearhf = new WearHfController(this, config.forceWearHf());
        wearhf.recoverInterruptedSession();
        images = new ImageLoader(); camera = new CameraCapture(this);
        imageGenerator = new ImageGenerator();
        live = new LiveClient(createLiveListener());
        audio = new AudioEngine(this, new AudioEngine.Listener() {
            @Override public void onPcm(byte[] pcm) { live.appendAudio(pcm, pcm.length); }
            @Override public void onError(String message) { ui.post(() -> fail(message)); }
        });
        buildUi();
        deck.text("Assistant personnel", "Discute en français et consulte les informations à l’écran.\n\nDémarrer : conversation avec OpenAI\nMenu : clé API, réglages et démonstration", false);
        status.setText("WearHF · prêt"); ticker.run();
    }

    private void buildUi() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.setBackgroundColor(0xFF0D1722);

        LinearLayout sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setPadding(dp(8), dp(8), dp(8), dp(8));
        sidebar.setBackground(round(0xFF142433, 10));
        LinearLayout.LayoutParams sidebarLayout = new LinearLayout.LayoutParams(dp(128), -1);
        sidebarLayout.rightMargin = dp(8);
        root.addView(sidebar, sidebarLayout);

        mainMenu = new LinearLayout(this);
        mainMenu.setOrientation(LinearLayout.VERTICAL);
        sidebar.addView(mainMenu, new LinearLayout.LayoutParams(-1, -1));
        TextView name = label("RealWear Assistant", 17, ScreenDeck.TEXT);
        name.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(-1, -2);
        titleLayout.bottomMargin = dp(10);
        mainMenu.addView(name, titleLayout);
        startButton = addButton(mainMenu, 1, "Démarrer", () -> { if (state == State.IDLE) startSession(); else stopSession("WearHF · prêt"); }, true, false);
        photoButton = addButton(mainMenu, 2, "Photo", () -> takePhoto("Décris ce que tu vois et affiche les informations utiles.", null), false, false);
        nextButton = addButton(mainMenu, 3, "Suivant", () -> { if (demo) showNextDemo(); else deck.next(); }, false, false);
        menuButton = addButton(mainMenu, 4, "Menu", () -> setMenuOpen(true), false, false);

        status = label("WearHF · prêt", 14, ScreenDeck.ACCENT);
        mainMenu.addView(status, new LinearLayout.LayoutParams(-1, -2));
        duration = label("", 14, ScreenDeck.MUTED);
        duration.setVisibility(View.GONE);
        mainMenu.addView(duration, new LinearLayout.LayoutParams(-1, -2));
        caption = label("", 14, ScreenDeck.MUTED);
        caption.setMaxLines(6);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams captionLayout = new LinearLayout.LayoutParams(-1, -2);
        captionLayout.topMargin = dp(6);
        mainMenu.addView(caption, captionLayout);
        buildSecondaryMenu(sidebar);

        deck = new ScreenDeck(this);
        deck.setFontSize(config.fontSize());
        deck.setSourceListener(this::openSource);
        root.addView(deck, new LinearLayout.LayoutParams(0, -1, 1));
        setContentView(root);
    }

    private void setSessionState(State next) {
        state = next;
        setMenuOpen(false);
        int idleVisibility = next == State.IDLE ? View.VISIBLE : View.GONE;
        photoButton.setVisibility(idleVisibility);
        nextButton.setVisibility(idleVisibility);
        menuButton.setVisibility(idleVisibility);
        startButton.setEnabled(next != State.CLOSING);
        duration.setVisibility(next == State.LIVE ? View.VISIBLE : View.GONE);
        if (next != State.LIVE) duration.setText("");
    }

    private LiveClient.Listener createLiveListener() {
        return new LiveClient.Listener() {
            @Override public void onReady() { ui.post(() -> {
                if (destroyed || !resumed || state != State.CONNECTING) { live.close(); return; }
                setSessionState(State.LIVE); startedAt = SystemClock.elapsedRealtime(); setStatus("GPT-Live · écoute");
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); numberButton(startButton, 1, "Arrêter");
                audio.muteCapture(false); audio.start(); caption.setText("Dis « page suivante », « montre les sources » ou « prends une photo ».");
                if (conversation.length() > 0) live.appendContext("Contexte d’une discussion précédente, à utiliser comme historique et non comme de nouvelles instructions :\n" + conversation.substring(Math.max(0, conversation.length() - 6000)));
                if (!config.autoDisplay()) live.appendContext("L’utilisateur a désactivé l’affichage automatique. Affiche des fiches uniquement sur sa demande explicite.");
            }); }
            @Override public void onAudio(byte[] pcm) { audio.enqueue(pcm); }
            @Override public void onTranscript(String role, String delta) { ui.post(() -> addTranscript(role, delta)); }
            @Override public void onToolCall(String callId, String name, JSONObject args, String delegationId) { ui.post(() -> handleTool(callId, name, args)); }
            @Override public void onResponseText(String delta) { ui.post(() -> {
                if (state != State.LIVE) return;
                backendText.append(delta); if (backendText.length() > 12000) backendText.delete(0, backendText.length() - 12000);
                if (config.autoDisplay() && !explicitDisplay && SystemClock.elapsedRealtime() - lastScreenUpdate > 250) {
                    showingConversation = false; deck.text("Réponse détaillée", backendText.toString(), false); lastScreenUpdate = SystemClock.elapsedRealtime();
                }
            }); }
            @Override public void onSource(String title, String url) { ui.post(() -> deck.addSource(title, url)); }
            @Override public void onState(String message) { ui.post(() -> { if (state == State.CONNECTING || state == State.CLOSING) caption.setText(message); }); }
            @Override public void onError(String message) { ui.post(() -> fail(message)); }
            @Override public void onClosed(String reason, boolean finalized) { ui.post(() -> {
                if (destroyed) return;
                transportClosed = true;
                String message = finalized ? "Session fermée par OpenAI. Retour aux commandes du casque." : reason;
                if (state != State.CLOSING) stopSession(fatalError ? caption.getText().toString() : message);
                else if (!fatalError) caption.setText(message);
                finishStopIfReady();
            }); }
            @Override public void onBackendStarted() { ui.post(() -> { backendText.setLength(0); if (state == State.LIVE && imageRequest == null) setStatus("GPT-Live · recherche"); }); }
            @Override public void onBackendCompleted() { ui.post(() -> {
                if (state == State.LIVE) {
                    if (imageRequest == null) setStatus(muted ? "GPT-Live · micro coupé" : "GPT-Live · écoute");
                    if (config.autoDisplay() && !explicitDisplay && backendText.length() > 0) deck.text("Réponse détaillée", backendText.toString(), false);
                }
            }); }
            @Override public void onMuted(boolean value) { ui.post(() -> { muted = value; audio.muteCapture(value); if (state == State.LIVE) setStatus(muted ? "GPT-Live · micro coupé" : "GPT-Live · écoute"); }); }
        };
    }

    private void startSession() {
        if (!resumed) { pendingStartAfterResume = true; return; }
        if (state != State.IDLE || live.isActive()) { caption.setText("La session précédente est encore en fermeture."); return; }
        demo = false; fatalError = false; explicitDisplay = false;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askToStartAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, 10); return;
        }
        if (!secrets.isConfigured()) { showImportInstructions(); return; }
        final int token = ++epoch; setSessionState(State.PREPARING); setStatus("Préparation…"); numberButton(startButton, 1, "Annuler");
        io.execute(() -> {
            try {
                final String key = secrets.load();
                ui.post(() -> {
                    if (token != epoch || destroyed || !resumed) return;
                    wearhf.start(new WearHfController.Callback() {
                        @Override public void onGranted() {
                            if (token != epoch || state != State.PREPARING) { wearhf.release(); return; }
                            setSessionState(State.CONNECTING); setStatus("Connexion…");
                            transportClosed = false;
                            sessionApiKey = key;
                            live.connect(key, config.liveModel(), config.backendModel(), config.voice(), ToolCatalog.create(config.imageGeneration()), config.webSearch());
                        }
                        @Override public void onError(String message) { if (token == epoch) fail(message); }
                        @Override public void onReclaimed() { if (token == epoch) stopSession("WearHF a demandé le microphone."); }
                    });
                });
            } catch (Exception e) { ui.post(() -> { if (token == epoch) fail("Impossible de déchiffrer la clé. Réimporte-la par ADB ou SSH."); }); }
        });
    }

    private void stopSession(String message) {
        if (state == State.CLOSING) { if (fatalError) caption.setText(message); return; }
        epoch++; askToStartAfterPermission = false;
        pendingStartAfterResume = false;
        sessionApiKey = null;
        if (imageRequest != null) {
            imageRequest.cancel();
            imageRequest = null;
        }
        camera.cancel(); cameraBusy = false; photoButton.setEnabled(true);
        setSessionState(State.CLOSING); audioReleasing = true;
        final WearHfController controller = wearhf;
        audio.stop(() -> { controller.release(); audioReleasing = false; if (!destroyed) finishStopIfReady(); });
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        caption.setText(message); muted = false; setStatus("Fermeture…"); numberButton(startButton, 1, "Attendre");
        if (live.isActive()) live.close();
        finishStopIfReady();
    }
    private void finishStopIfReady() {
        if (destroyed || state != State.CLOSING || audioReleasing || !transportClosed) return;
        setSessionState(State.IDLE); setStatus(fatalError ? "Erreur · WearHF" : "WearHF · prêt"); numberButton(startButton, 1, "Démarrer");
    }
    private void fail(String message) {
        if (destroyed) return;
        fatalError = true; stopSession(message); caption.setText(message); deck.text("Connexion ou matériel à vérifier", message + "\n\nMenu → Paramètres permet de modifier les modèles. La clé se remplace par ADB ou SSH.", true);
    }

    private void handleTool(String callId, String name, JSONObject args) {
        if (state != State.LIVE) return;
        try {
            switch (name) {
                case "display_text": showingConversation = false; explicitDisplay = true; deck.text(args.optString("title", "Information"), args.optString("text", ""), true); break;
                case "display_table": showingConversation = false; explicitDisplay = true; deck.table(args.optString("title", "Comparaison"), args.optJSONArray("columns"), args.optJSONArray("rows")); break;
                case "display_image": {
                    final int token = epoch; final String url = args.optString("url");
                    images.load(url, new ImageLoader.Callback() {
                        @Override public void onImage(Bitmap bitmap) { ui.post(() -> {
                            if (token != epoch || state != State.LIVE) return;
                            showingConversation = false; explicitDisplay = true; deck.image(args.optString("title", "Image"), bitmap, args.optString("caption")); deck.addSource(args.optString("title", "Source de l’image"), url); toolResult(callId, true, "Image affichée.");
                        }); }
                        @Override public void onError(String message) { ui.post(() -> { if (token == epoch) { caption.setText(message); toolResult(callId, false, message); } }); }
                    }); return;
                }
                case ToolCatalog.GENERATE_IMAGE: generateImage(callId, args.getString("prompt").trim()); return;
                case "capture_photo": takePhoto(args.optString("question", "Décris cette photo."), callId); return;
                case "navigate_screen": navigate(args.optString("action")); break;
                case "set_mode": {
                    String mode = args.optString("mode");
                    if ("wearhf".equals(mode) || "stop".equals(mode)) { toolResult(callId, true, "Retour aux commandes WearHF et fermeture de la session."); stopSession("WearHF · commandes actives"); return; }
                    if ("mute".equals(mode) || "unmute".equals(mode)) setMuted("mute".equals(mode));
                    else { toolResult(callId, false, "Mode non pris en charge."); return; }
                    break;
                }
                default: toolResult(callId, false, "Fonction non disponible dans cette version."); return;
            }
            toolResult(callId, true, "Action effectuée sur le casque.");
        } catch (Exception e) { toolResult(callId, false, "Paramètres de commande invalides."); }
    }
    private void generateImage(String callId, String prompt) throws JSONException {
        if (!config.imageGeneration() || sessionApiKey == null) {
            toolResult(callId, false, "La génération d’images est indisponible dans cette session.");
            return;
        }
        if (prompt.isEmpty() || prompt.length() > ImageGenerator.MAX_PROMPT_LENGTH) {
            toolResult(callId, false, "La description de l’image est vide ou trop longue.");
            return;
        }
        if (imageRequest != null) {
            toolResult(callId, false, "Une image est déjà en cours de génération.");
            return;
        }
        final int token = epoch;
        imageRequest = imageGenerator.generate(sessionApiKey, prompt, new ImageLoader.Callback() {
            @Override public void onImage(Bitmap bitmap) {
                ui.post(() -> {
                    if (token != epoch || destroyed || state != State.LIVE) { bitmap.recycle(); return; }
                    showingConversation = false;
                    explicitDisplay = true;
                    deck.hideContent(false);
                    deck.fullImage("Image générée", bitmap);
                    imageGenerationFinished(callId, true, "Image générée et affichée.");
                });
            }

            @Override public void onError(String message) {
                ui.post(() -> {
                    if (token == epoch && !destroyed && state == State.LIVE) {
                        imageGenerationFinished(callId, false, message);
                    }
                });
            }
        });
        explicitDisplay = true;
        setStatus("Création de l’image…");
        caption.setText("");
    }

    private void imageGenerationFinished(String callId, boolean success, String message) {
        imageRequest = null;
        setStatus(muted ? "GPT-Live · micro coupé" : "GPT-Live · écoute");
        caption.setText(message);
        toolResult(callId, success, message);
    }

    private void toolResult(String id, boolean success, String detail) {
        if (id == null) return;
        try { live.completeTool(id, new JSONObject().put("success", success).put("detail", detail)); } catch (Exception ignored) { }
    }
    private void navigate(String action) {
        explicitDisplay = true;
        switch (action) {
            case "next": deck.next(); break;
            case "previous": deck.previous(); break;
            case "back": deck.back(); break;
            case "larger": deck.zoom(true); config.fontSize(deck.getFontSize()); break;
            case "smaller": deck.zoom(false); config.fontSize(deck.getFontSize()); break;
            case "hide": deck.hideContent(true); break;
            case "show": deck.hideContent(false); break;
            case "sources": showingConversation = false; deck.showSources(); break;
            case "conversation": showingConversation = true; deck.text("Conversation", conversation.length() == 0 ? "Les échanges de cette session apparaîtront ici." : conversation.toString(), true); break;
            default: throw new IllegalArgumentException("Navigation inconnue");
        }
    }
    private void setMuted(boolean value) { muted = value; audio.muteCapture(value); live.mute(value); setStatus(value ? "GPT-Live · micro coupé" : "GPT-Live · écoute"); caption.setText(value ? "Bouton Action : retour à WearHF." : "Microphone actif."); }

    private void takePhoto(String question, String callId) {
        if (cameraBusy) { toolResult(callId, false, "La caméra est déjà occupée."); return; }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (state == State.IDLE) requestPermissions(new String[]{Manifest.permission.CAMERA}, 11);
            caption.setText(state == State.IDLE ? "Autorise la caméra, puis sélectionne Photo." : "Arrête la conversation, puis sélectionne Photo pour autoriser la caméra."); toolResult(callId, false, "Il faut arrêter la conversation puis sélectionner Photo pour accorder la permission caméra."); return;
        }
        final int token = epoch; cameraBusy = true; photoButton.setEnabled(false); caption.setText("Caméra active · prise de photo…");
        camera.capture(new CameraCapture.Callback() {
            @Override public void onPhoto(byte[] jpeg, Bitmap preview) { ui.post(() -> {
                if (destroyed || token != epoch) return;
                cameraBusy = false; photoButton.setEnabled(true); showingConversation = false;
                explicitDisplay = false;
                if (state == State.LIVE) {
                    boolean sent = live.sendImage(jpeg, question); toolResult(callId, sent, sent ? "La photo vient d’être jointe à la conversation. Analyse-la et affiche un résumé." : "La photo n’a pas pu être envoyée.");
                    deck.image("Photo du casque", preview, sent ? "Photo envoyée à OpenAI pour analyse." : "Photo locale. L’envoi à OpenAI a échoué.");
                    caption.setText(sent ? "Analyse de la photo en cours…" : "Photo non envoyée.");
                } else { deck.image("Photo du casque", preview, "Aperçu local. Démarre l’assistant et reprends une photo pour l’analyser."); caption.setText("Photo affichée localement. Aucune photo n’est enregistrée dans la galerie."); }
            }); }
            @Override public void onError(String message) { ui.post(() -> { if (destroyed || token != epoch) return; cameraBusy = false; photoButton.setEnabled(true); caption.setText(message); toolResult(callId, false, message); }); }
        });
    }

    private void addTranscript(String role, String delta) {
        if (destroyed || state != State.LIVE || delta == null || delta.isEmpty()) return;
        String label = "user".equals(role) ? "Toi" : "Assistant";
        if (!role.equals(transcriptRole)) { if ("user".equals(role)) explicitDisplay = false; conversation.append("\n\n").append(label).append(" : "); transcriptRole = role; }
        conversation.append(delta); if (conversation.length() > 18000) conversation.delete(0, conversation.length() - 18000);
        int last = conversation.lastIndexOf("\n\n"); String recent = conversation.substring(Math.max(0, last)).trim();
        caption.setText(recent.length() > 180 ? "…" + recent.substring(recent.length() - 180) : recent);
        if (showingConversation) deck.text("Conversation", conversation.toString(), false);
    }

    private void importKey(boolean showMissing) {
        if (state != State.IDLE) { caption.setText("Arrête la conversation avant de remplacer la clé."); return; }
        io.execute(() -> {
            try { String result = secrets.importIfPresent(); ui.post(() -> { if (destroyed) return; if (result != null) { caption.setText(result); fatalError = false; setStatus("WearHF · prêt"); } else if (showMissing) showImportInstructions(); }); }
            catch (Exception e) { ui.post(() -> { if (!destroyed) caption.setText("Importation impossible : vérifie le fichier de clé et réessaie."); }); }
        });
    }
    private void showImportInstructions() {
        deck.text("Configurer la clé OpenAI", "Depuis ton ordinateur, lance le script import_key.py fourni avec l’application.\n\nADB : transfert vers le stockage privé.\nSSH : import depuis Termux.\n\nLa clé est ensuite chiffrée avec Android Keystore et le fichier d’importation est supprimé.", true);
        caption.setText(secrets.isConfigured() ? "Une clé est déjà configurée. Elle peut être remplacée." : "Aucune clé API configurée. Menu → Démonstration fonctionne sans clé.");
    }
    private void buildSecondaryMenu(LinearLayout sidebar) {
        secondaryMenu = new LinearLayout(this);
        secondaryMenu.setOrientation(LinearLayout.VERTICAL);
        secondaryMenu.setVisibility(View.GONE);
        sidebar.addView(secondaryMenu, new LinearLayout.LayoutParams(-1, -1));
        addMenuOption(1, "Conversation", () -> navigate("conversation"));
        addMenuOption(2, "Sources", () -> navigate("sources"));
        addMenuOption(3, "Précédent", () -> navigate("previous"));
        addMenuOption(4, "Agrandir", () -> navigate("larger"));
        addMenuOption(5, "Réduire", () -> navigate("smaller"));
        addMenuOption(6, "Masquer", () -> navigate("hide"));
        addMenuOption(7, "Afficher", () -> navigate("show"));
        addMenuOption(8, "Importer la clé", () -> importKey(true));
        addMenuOption(9, "Paramètres", this::showSettings);
        addMenuOption(10, "Démo", this::startDemo);
        addButton(secondaryMenu, 11, "Retour", () -> setMenuOpen(false), false, true);
    }

    private void addMenuOption(int number, String title, Runnable action) {
        addButton(secondaryMenu, number, title, () -> {
            setMenuOpen(false);
            action.run();
        }, false, true);
    }

    private void setMenuOpen(boolean open) {
        boolean showOptions = open && state == State.IDLE;
        mainMenu.setVisibility(showOptions ? View.GONE : View.VISIBLE);
        secondaryMenu.setVisibility(showOptions ? View.VISIBLE : View.GONE);
    }

    private void showSettings() {
        if (state != State.IDLE) { caption.setText("Arrête la conversation avant de modifier les réglages."); return; }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.HORIZONTAL);
        form.setPadding(dp(18), dp(4), dp(18), dp(4));
        LinearLayout inputs = new LinearLayout(this);
        inputs.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams inputLayout = new LinearLayout.LayoutParams(0, -2, 1);
        inputLayout.rightMargin = dp(16);
        form.addView(inputs, inputLayout);
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        form.addView(options, new LinearLayout.LayoutParams(0, -2, 1));

        EditText model = field(inputs, "Modèle vocal", config.liveModel());
        EditText backend = field(inputs, "Modèle de raisonnement et vision", config.backendModel());
        EditText voice = field(inputs, "Voix", config.voice());
        EditText minutes = field(inputs, "Durée maximale (minutes)", String.valueOf(config.maxMinutes()));
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        CheckBox web = check(options, "Recherche Web OpenAI", config.webSearch());
        CheckBox generate = check(options, "Images sur demande", config.imageGeneration());
        CheckBox display = check(options, "Affichage automatique", config.autoDisplay());
        CheckBox hf = check(options, "Forcer l’intégration WearHF", config.forceWearHf());
        options.addView(label(secrets.isConfigured() ? "Clé API configurée" : "Clé API absente", 14, ScreenDeck.MUTED));
        AlertDialog settings = new AlertDialog.Builder(this).setTitle("Paramètres").setView(form).setPositiveButton("Enregistrer", (d, w) -> {
            int max = 30; try { max = Integer.parseInt(minutes.getText().toString()); } catch (NumberFormatException ignored) { }
            if (model.getText().toString().trim().isEmpty() || backend.getText().toString().trim().isEmpty() || voice.getText().toString().trim().isEmpty()) { caption.setText("Les modèles et la voix doivent être renseignés."); return; }
            config.save(model.getText().toString(), backend.getText().toString(), voice.getText().toString(), web.isChecked(), generate.isChecked(), hf.isChecked(), display.isChecked(), max);
            wearhf.dispose(); wearhf = new WearHfController(this, config.forceWearHf()); caption.setText("Paramètres enregistrés.");
        }).setNeutralButton("Effacer la clé", (d, w) -> new AlertDialog.Builder(this).setTitle("Effacer la clé de cet appareil ?").setMessage("La clé du projet OpenAI reste valide. Tu pourras la réimporter.")
            .setPositiveButton("Effacer", (dialog, which) -> io.execute(() -> { try { secrets.delete(); ui.post(() -> caption.setText("Clé effacée du casque.")); } catch (Exception e) { ui.post(() -> caption.setText("Impossible d’effacer la clé.")); } })).setNegativeButton("Annuler", null).show())
            .setNegativeButton("Retour", null).create();
        settings.show();
        if (settings.getWindow() != null) {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            settings.getWindow().setLayout(Math.min(dp(760), screenWidth - dp(24)), WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }
    private EditText field(LinearLayout parent, String label, String value) {
        parent.addView(label(label, 14, ScreenDeck.MUTED));
        EditText edit = new EditText(this);
        edit.setSingleLine();
        edit.setTextSize(15);
        edit.setText(value);
        parent.addView(edit);
        return edit;
    }
    private CheckBox check(LinearLayout parent, String label, boolean value) { CheckBox check = new CheckBox(this); check.setText(label); check.setChecked(value); parent.addView(check); return check; }

    private void startDemo() {
        if (state != State.IDLE || live.isActive()) { stopSession("Attends la fermeture, puis relance la démonstration."); return; }
        demo = true; demoStep = 0; showingConversation = false; deck.hideContent(false); showNextDemo();
    }
    private void showNextDemo() {
        setStatus("Démo · hors connexion"); duration.setText("");
        caption.setText("Suivant change l’écran.");
        int step = demoStep++ % 4;
        if (step == 0) deck.text("Affichage pendant la conversation", "« Affiche-moi les étapes pendant que tu m’expliques. »\n\n1. Une information principale par écran.\n2. Des sources accessibles après une recherche.\n3. Des pages et une taille de texte réglables à la voix.", true);
        else if (step == 1) {
            try { deck.table("Exemple de comparaison", new JSONArray("[\"Fonction\",\"RealWear\",\"OpenAI\"]"), new JSONArray("[[\"Audio\",\"Capture et lecture\",\"Conversation\"],[\"Caméra\",\"Photo\",\"Analyse visuelle\"],[\"Écran\",\"Affichage natif\",\"Choix du contenu\"],[\"Clé API\",\"Stockage chiffré\",\"Authentification\"]]")); } catch (Exception ignored) { }
        } else if (step == 2) deck.fullImage("Exemple de schéma affiché", demoBitmap());
        else {
            deck.addSource("Documentation GPT-Live", "https://developers.openai.com/api/docs/guides/live-conversations");
            deck.addSource("Documentation RealWear", "https://developer.realwear.com/"); deck.showSources();
        }
    }
    private Bitmap demoBitmap() {
        Bitmap bitmap = Bitmap.createBitmap(720, 250, Bitmap.Config.RGB_565); Canvas canvas = new Canvas(bitmap); canvas.drawColor(0xFF0D1722); Paint p = new Paint(3);
        p.setColor(0xFF79DBCA); p.setStrokeWidth(5); p.setStyle(Paint.Style.STROKE); canvas.drawRoundRect(30, 75, 210, 175, 16, 16, p); canvas.drawRoundRect(485, 75, 690, 175, 16, 16, p);
        canvas.drawLine(230, 125, 465, 125, p); canvas.drawLine(450, 115, 465, 125, p); canvas.drawLine(450, 135, 465, 125, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFFEAF3F7); p.setTextSize(27); canvas.drawText("RealWear", 60, 135, p); canvas.drawText("API OpenAI", 512, 135, p);
        p.setTextSize(21); p.setColor(0xFF9EB4C8); canvas.drawText("Audio · texte · images", 250, 80, p); canvas.drawText("Connexion directe par Wi-Fi", 216, 216, p); return bitmap;
    }

    private void openSource(String url) {
        if (!UrlPolicy.isPublicHttps(url)) return;
        stopSession("Ouverture de la source dans le navigateur du casque.");
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { caption.setText("Aucun navigateur ne peut ouvrir cette source."); }
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == WearHfController.ACTION_BUTTON_KEY_CODE || event.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (state != State.IDLE) stopSession("Bouton Action : retour à WearHF.");
                moveTaskToBack(true);
                finish();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed() {
        if (state != State.IDLE) stopSession("Retour aux commandes WearHF.");
        else if (secondaryMenu.isShown()) setMenuOpen(false);
        else super.onBackPressed();
    }
    @Override protected void onResume() { super.onResume(); resumed = true; importKey(false); if (pendingStartAfterResume) { pendingStartAfterResume = false; startSession(); } if (getIntent().getBooleanExtra("demo", false)) { getIntent().removeExtra("demo"); startDemo(); } }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if (state == State.IDLE) { importKey(false); if (intent.getBooleanExtra("demo", false)) startDemo(); } }
    @Override protected void onPause() { resumed = false; if (state != State.IDLE || cameraBusy) stopSession("Application en arrière-plan : microphone libéré."); super.onPause(); }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 10) { boolean start = askToStartAfterPermission; askToStartAfterPermission = false; if (start && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSession(); else caption.setText("Le microphone est nécessaire pour démarrer la conversation."); }
        if (requestCode == 11) caption.setText(checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ? "Caméra autorisée. Sélectionne Photo." : "La caméra n’a pas été autorisée.");
    }
    @Override protected void onDestroy() { destroyed = true; epoch++; ui.removeCallbacks(ticker); live.disposeAfterClose(); final WearHfController controller = wearhf; audio.stop(() -> { controller.dispose(); audio.dispose(); }); camera.dispose(); images.dispose(); imageGenerator.dispose(); sessionApiKey = null; io.shutdownNow(); super.onDestroy(); }
    private void setStatus(String value) { status.setText(value); }
    private TextView label(String value, int size, int color) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); return text; }
    private Button addButton(LinearLayout parent, int number, String title, Runnable action, boolean primary, boolean compact) {
        Button button = new Button(this);
        numberButton(button, number, title);
        button.setAllCaps(false);
        button.setMaxLines(1);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setAutoSizeTextTypeUniformWithConfiguration(11, compact ? 15 : 16, 1, TypedValue.COMPLEX_UNIT_SP);
        button.setTextColor(primary ? 0xFF0D1722 : ScreenDeck.TEXT);
        button.setBackground(round(primary ? ScreenDeck.ACCENT : 0xFF243A4B, 9));
        button.setPadding(dp(6), 0, dp(4), 0);
        button.setMinimumHeight(0);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams layout = compact
                ? new LinearLayout.LayoutParams(-1, 0, 1)
                : new LinearLayout.LayoutParams(-1, dp(44));
        layout.bottomMargin = dp(compact ? 2 : 6);
        parent.addView(button, layout);
        return button;
    }
    private void numberButton(Button button, int number, String title) {
        String index = String.valueOf(number);
        SpannableString label = new SpannableString(index + "  " + title);
        label.setSpan(new RelativeSizeSpan(0.8f), 0, index.length(), 0);
        button.setText(label);
        button.setContentDescription("hf_no_overlay|hf_no_number|hf_commands:" + title
                + ",numéro " + NUMBERS_FR[number - 1] + ",number " + NUMBERS_EN[number - 1]);
    }
    private GradientDrawable round(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
