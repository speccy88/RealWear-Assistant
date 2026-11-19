package com.fred.realweargpt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

/**
 * Negotiates the microphone with WearHF. All state and callbacks run on the main thread.
 *
 * RealWear documentation: realwear/devdocs, docs/wear-ml/wearhf-intents/microphone-intents.md.
 * MIC_RELEASED has two roles: WearHF acknowledges our request, and our app sends it when done.
 * Never start a RealWear microphone until WearHF has acknowledged RELEASE_MIC.
 */
public final class WearHfController {
    public interface Callback {
        void onGranted();
        void onError(String message);
        void onReclaimed();
    }

    public static final String RELEASE_MIC = "com.realwear.wearhf.intent.action.RELEASE_MIC";
    public static final String MIC_RELEASED = "com.realwear.wearhf.intent.action.MIC_RELEASED";
    public static final String SOURCE_PACKAGE = "com.realwear.wearhf.intent.extra.SOURCE_PACKAGE";
    public static final String HIDE_TEXT = "com.realwear.wearhf.intent.extra.HIDE_TEXT";
    public static final String MUTE_TEXT = "com.realwear.wearhf.intent.extra.MUTE_TEXT";
    public static final int ACTION_BUTTON_KEY_CODE = 500;
    private static final long REQUEST_TIMEOUT_MS = 3000L;
    static final String RECOVERY_PREFS = "wearhf_recovery";
    static final String MIC_RETURN_PENDING = "mic_return_pending";
    // All controllers and transitions run on the main thread, including activity recreation.
    private static boolean requestInProcess;

    private enum State { IDLE, REQUESTING, GRANTED, RECLAIMING }
    private final Context context;
    private final SharedPreferences recovery;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean realWear;
    private State state = State.IDLE;
    private Callback callback;
    private boolean registered;
    private boolean disposed;

    /** forceRealWear is the user setting for devices with nonstandard manufacturer metadata. */
    public WearHfController(Context context, boolean forceRealWear) {
        this.context = context.getApplicationContext();
        recovery = this.context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE);
        this.realWear = forceRealWear || isRealWearDevice();
    }

    public static boolean isRealWearDevice() {
        String device = (Build.MANUFACTURER + " " + Build.BRAND + " " + Build.MODEL)
                .toLowerCase(Locale.ROOT);
        return device.contains("realwear") || device.contains("hmt-1") || device.contains("hmt1");
    }

    public boolean isRealWear() { return realWear; }

    void recoverInterruptedSession() {
        if (realWear && !requestInProcess && recovery.getBoolean(MIC_RETURN_PENDING, false)) {
            returnMicrophone();
        }
    }

    public void start(Callback request) {
        if (request == null) throw new IllegalArgumentException("Callback required");
        onMain(() -> requestOnMain(request));
    }

    private void requestOnMain(Callback request) {
        if (disposed) {
            request.onError("Le contrôleur WearHF est fermé.");
            return;
        }
        if (state != State.IDLE || (realWear && requestInProcess)) {
            request.onError("Une demande de microphone est déjà active.");
            return;
        }
        callback = request;
        if (!realWear) {
            state = State.GRANTED;
            request.onGranted();
            return;
        }
        try {
            ensureReceiver();
            // A process killed during capture cannot run onPause or onDestroy.
            if (!recovery.edit().putBoolean(MIC_RETURN_PENDING, true).commit()) {
                fail("Impossible de préparer la restitution du microphone à WearHF.");
                return;
            }
            requestInProcess = true;
            state = State.REQUESTING;
            Intent intent = ownIntent(RELEASE_MIC);
            // Our screen visibly identifies the active OpenAI microphone and the Action-button exit.
            intent.putExtra(HIDE_TEXT, true);
            intent.putExtra(MUTE_TEXT, "Assistant actif. Bouton Action : retour à WearHF.");
            main.postDelayed(timeout, REQUEST_TIMEOUT_MS);
            context.sendBroadcast(intent);
        } catch (RuntimeException e) {
            fail("WearHF : impossible de demander le microphone.");
        }
    }

    // API 33+ uses the explicit export flag below. Android 8.1 has no such flag;
    // this receiver must accept the external WearHF service's documented broadcast.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void ensureReceiver() {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(RELEASE_MIC);
        filter.addAction(MIC_RELEASED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        registered = true;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context unused, Intent intent) {
            if (disposed || intent == null) return;
            // Official replies need not include SOURCE_PACKAGE. Only exclude our own messages.
            if (context.getPackageName().equals(intent.getStringExtra(SOURCE_PACKAGE))) return;
            String action = intent.getAction();
            if (MIC_RELEASED.equals(action) && state == State.REQUESTING) {
                main.removeCallbacks(timeout);
                state = State.GRANTED;
                Callback current = callback;
                if (current != null) current.onGranted();
            } else if (RELEASE_MIC.equals(action) && state != State.IDLE && state != State.RECLAIMING) {
                main.removeCallbacks(timeout);
                state = State.RECLAIMING;
                Callback current = callback;
                // The caller stops AudioRecord asynchronously, then calls release() to acknowledge.
                if (current != null) current.onReclaimed();
                else releaseOnMain();
            }
        }
    };

    private final Runnable timeout = () -> {
        if (state == State.REQUESTING) {
            fail("WearHF n’a pas confirmé la libération du microphone. "
                    + "Réessaie après un retour à l’accueil du casque.");
        }
    };

    private void fail(String message) {
        Callback current = callback;
        releaseOnMain();
        if (current != null) current.onError(message);
    }

    /** Call after stopping AudioRecord, including onPause and all connection/audio errors. */
    public void release() { onMain(this::releaseOnMain); }

    private void releaseOnMain() {
        main.removeCallbacks(timeout);
        boolean requested = state != State.IDLE;
        state = State.IDLE;
        callback = null;
        if (requested && realWear) {
            requestInProcess = false;
            returnMicrophone();
        }
    }

    private void returnMicrophone() {
        try {
            context.sendBroadcast(ownIntent(MIC_RELEASED));
            recovery.edit().remove(MIC_RETURN_PENDING).apply();
        } catch (RuntimeException ignored) {
            // Keep the marker so a later activity start can retry the handoff.
        }
    }

    private Intent ownIntent(String action) {
        return new Intent(action).putExtra(SOURCE_PACKAGE, context.getPackageName());
    }

    public void dispose() {
        onMain(() -> {
            releaseOnMain();
            disposed = true;
            if (registered) {
                context.unregisterReceiver(receiver);
                registered = false;
            }
        });
    }

    private void onMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else main.post(runnable);
    }
}
