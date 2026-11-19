package com.fred.realweargpt;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full-duplex PCM16 audio. Public entry points never wait for hardware or network.
 * AudioRecord and AudioTrack each have a dedicated worker and release only after their I/O exits.
 * Listener callbacks run on a worker thread; update Android views through the main Handler.
 */
public final class AudioEngine {
    public interface Listener {
        void onPcm(byte[] pcm24kMono);
        void onError(String message);
    }

    public static final int API_SAMPLE_RATE = 24000;
    private static final int MAX_QUEUE_BYTES = API_SAMPLE_RATE * 2 * 3;
    private static final long JOIN_TIMEOUT_MS = 2000L;
    private final Context context;
    private final AudioManager manager;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService lifecycle = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "realwear-audio-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger requests = new AtomicInteger();
    private volatile Session session;
    private volatile boolean captureMuted;
    private volatile boolean disposed;

    public AudioEngine(Context context, Listener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener required");
        this.listener = listener;
        this.context = context.getApplicationContext();
        manager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    public boolean isRunning() {
        Session current = session;
        return current != null && current.running && current.captureStarted && current.playbackStarted;
    }

    public int getCaptureSampleRate() {
        Session current = session;
        return current == null ? 0 : current.captureRate;
    }

    public void start() {
        if (disposed) return;
        int generation = requests.incrementAndGet();
        execute(() -> startOnWorker(generation));
    }

    private void startOnWorker(int generation) {
        if (disposed || generation != requests.get()) return;
        Session previous = session;
        if (previous != null && previous.running) return;
        if (previous != null && !stopOnWorker(previous)) return;
        if (manager == null) {
            notifyError("Le service audio Android est indisponible.");
            return;
        }
        Session next = new Session();
        session = next;
        next.running = true;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            next.previousMode = manager.getMode();
            next.focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(change -> {
                        if (change == AudioManager.AUDIOFOCUS_LOSS
                                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                            fail(next, "Une autre application a pris le contrôle audio.");
                        }
                    }, main).build();
            if (manager.requestAudioFocus(next.focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                throw new IllegalStateException("Le contrôle audio Android a été refusé.");
            next.focusGranted = true;
            manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            next.modeChanged = true;
            next.recorder = createRecorder(next);
            attachEffects(next);
            next.track = createTrack(next, attributes);
            if (generation != requests.get() || disposed || !next.running) {
                stopOnWorker(next);
                return;
            }
            next.recorder.startRecording();
            if (next.recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Le microphone n’a pas démarré.");
            next.track.play();
            next.captureThread = new Thread(() -> captureLoop(next), "realwear-microphone");
            next.playbackThread = new Thread(() -> playbackLoop(next), "realwear-speaker");
            next.captureStarted = true;
            next.captureThread.start();
            next.playbackStarted = true;
            next.playbackThread.start();
        } catch (RuntimeException e) {
            next.running = false;
            stopOnWorker(next);
            if (generation == requests.get() && !disposed)
                notifyError("Audio : " + safeMessage(e));
        }
    }

    private AudioRecord createRecorder(Session current) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("Autorisation du microphone manquante.");
        int[] sources = { MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC };
        int[] rates = { 48000, 16000 };
        for (int source : sources) {
            for (int rate : rates) {
                int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) continue;
                AudioRecord candidate = null;
                try {
                    candidate = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum * 2, rate / 10));
                    if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                        current.captureRate = rate;
                        return candidate;
                    }
                } catch (SecurityException e) {
                    if (candidate != null) candidate.release();
                    throw new IllegalStateException("Autorisation du microphone manquante.");
                } catch (IllegalArgumentException ignored) {
                    // Try the next native rate/source supported by this firmware.
                }
                if (candidate != null) candidate.release();
            }
        }
        throw new IllegalStateException("Microphone indisponible à 48 kHz et 16 kHz. Vérifie WearHF.");
    }

    private AudioTrack createTrack(Session current, AudioAttributes attributes) {
        for (int rate : new int[] { 48000, 16000 }) {
            int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) continue;
            AudioTrack candidate = null;
            try {
                candidate = new AudioTrack.Builder().setAudioAttributes(attributes)
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setSampleRate(rate).build())
                        .setBufferSizeInBytes(Math.max(minimum * 2, rate / 5))
                        .setTransferMode(AudioTrack.MODE_STREAM).build();
                if (candidate.getState() == AudioTrack.STATE_INITIALIZED) {
                    current.playbackRate = rate;
                    return candidate;
                }
            } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
                // A 16 kHz output is a fallback for firmware that rejects 48 kHz.
            }
            if (candidate != null) candidate.release();
        }
        throw new IllegalStateException("Le haut-parleur Android n’est pas disponible.");
    }

    private void attachEffects(Session current) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                current.aec = AcousticEchoCanceler.create(current.recorder.getAudioSessionId());
                if (current.aec != null) current.aec.setEnabled(true);
            }
        } catch (RuntimeException ignored) { /* Optional hardware processing. */ }
        try {
            if (NoiseSuppressor.isAvailable()) {
                current.noiseSuppressor = NoiseSuppressor.create(current.recorder.getAudioSessionId());
                if (current.noiseSuppressor != null) current.noiseSuppressor.setEnabled(true);
            }
        } catch (RuntimeException ignored) { /* WearHF already performs device noise reduction. */ }
    }

    private void captureLoop(Session current) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        PcmResampler converter = new PcmResampler(current.captureRate, API_SAMPLE_RATE);
        short[] input = new short[current.captureRate / 50]; // 20 ms at the native rate.
        try {
            while (current.running) {
                int count = current.recorder.read(input, 0, input.length, AudioRecord.READ_BLOCKING);
                if (!current.running) break;
                if (count < 0) throw new IllegalStateException("Lecture du microphone interrompue (" + count + ").");
                if (count == 0) { Thread.sleep(3); continue; }
                short[] pcm = converter.process(input, count);
                if (!captureMuted && current.running && pcm.length != 0)
                    listener.onPcm(PcmResampler.littleEndian(pcm));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            if (current.running) fail(current, "Audio : " + safeMessage(e));
        } finally {
            closeCapture(current);
        }
    }

    /** Queues only memory work; never calls AudioTrack from the WebSocket thread. */
    public void enqueue(byte[] pcm24kMono) {
        Session current = session;
        if (current == null || !current.running || pcm24kMono == null || pcm24kMono.length == 0) return;
        if ((pcm24kMono.length & 1) != 0) {
            fail(current, "Le flux vocal contient un échantillon PCM16 incomplet.");
            return;
        }
        boolean overflow;
        synchronized (current.queueLock) {
            overflow = pcm24kMono.length > MAX_QUEUE_BYTES - current.queuedBytes;
            if (!overflow && current.running) {
                current.queue.addLast(new Chunk(pcm24kMono.clone(), current.playbackGeneration));
                current.queuedBytes += pcm24kMono.length;
                current.queueLock.notifyAll();
            }
        }
        if (overflow) fail(current, "La lecture audio a plus de 3 secondes de retard. Vérifie le Wi-Fi et relance la conversation.");
    }

    private void playbackLoop(Session current) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        PcmResampler converter = new PcmResampler(API_SAMPLE_RATE, current.playbackRate);
        long observedGeneration = current.playbackGeneration;
        try {
            while (current.running) {
                Chunk chunk;
                synchronized (current.queueLock) {
                    if (current.queue.isEmpty() && observedGeneration == current.playbackGeneration
                            && current.running) current.queueLock.wait(40);
                    chunk = current.queue.pollFirst();
                    if (chunk != null) current.queuedBytes -= chunk.bytes.length;
                }
                if (!current.running) break;
                if (observedGeneration != current.playbackGeneration) {
                    current.track.pause();
                    current.track.flush();
                    converter.reset();
                    current.track.play();
                    observedGeneration = current.playbackGeneration;
                }
                if (chunk == null || chunk.generation != observedGeneration) continue;
                short[] input = PcmResampler.samples(chunk.bytes);
                short[] output = converter.process(input, input.length);
                int offset = 0;
                while (current.running && observedGeneration == current.playbackGeneration && offset < output.length) {
                    int length = Math.min(current.playbackRate / 50, output.length - offset);
                    int count = current.track.write(output, offset, length, AudioTrack.WRITE_NON_BLOCKING);
                    if (count < 0) throw new IllegalStateException("Lecture du haut-parleur interrompue (" + count + ").");
                    if (count == 0) Thread.sleep(3);
                    else offset += count;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            if (current.running) fail(current, "Audio : " + safeMessage(e));
        } finally {
            closePlayback(current);
        }
    }

    /** Muting stops outbound audio, not the native microphone; stop() releases the microphone. */
    public void muteCapture(boolean muted) { captureMuted = muted; }

    public void interruptPlayback() {
        Session current = session;
        if (current == null) return;
        synchronized (current.queueLock) {
            current.playbackGeneration++;
            current.queue.clear();
            current.queuedBytes = 0;
            current.queueLock.notifyAll();
        }
    }

    /** Stops promptly; native resources are closed on their I/O workers, never during a read/write. */
    public void stop() {
        requests.incrementAndGet();
        Session current = session;
        if (current != null) {
            current.running = false;
            synchronized (current.queueLock) { current.queueLock.notifyAll(); }
        }
        execute(() -> stopOnWorker(current));
    }

    /** Same stop semantics with notification after the native microphone has actually closed. */
    public void stop(Runnable onStopped) {
        requests.incrementAndGet();
        Session current = session;
        if (current != null) {
            current.running = false;
            synchronized (current.queueLock) { current.queueLock.notifyAll(); }
        }
        execute(() -> {
            boolean stopped = stopOnWorker(current);
            if (stopped && onStopped != null) main.post(onStopped);
        });
    }

    private boolean stopOnWorker(Session current) {
        if (current == null) return true;
        current.running = false;
        synchronized (current.queueLock) {
            current.queue.clear();
            current.queuedBytes = 0;
            current.queueLock.notifyAll();
        }
        if (current.captureStarted) {
            // stop() unblocks AudioRecord.read(); release() happens only in captureLoop.finally.
            try { if (current.recorder != null) current.recorder.stop(); }
            catch (RuntimeException ignored) { }
        } else closeCapture(current);
        if (!current.playbackStarted) closePlayback(current);
        boolean stopped = false;
        try { stopped = current.closed.await(JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        restoreAudioState(current);
        if (stopped && session == current) session = null;
        if (!stopped && current.reportedError.compareAndSet(false, true))
            notifyError("Android n’a pas libéré le périphérique audio. Ferme puis rouvre l’application.");
        return stopped;
    }

    private void closeCapture(Session current) {
        if (!current.captureClosed.compareAndSet(false, true)) return;
        try {
            if (current.aec != null) current.aec.release();
        } catch (RuntimeException ignored) { }
        try {
            if (current.noiseSuppressor != null) current.noiseSuppressor.release();
        } catch (RuntimeException ignored) { }
        try {
            if (current.recorder != null) {
                try { current.recorder.stop(); } catch (RuntimeException ignored) { }
                try { current.recorder.release(); } catch (RuntimeException ignored) { }
            }
        } finally { current.closed.countDown(); }
    }

    private void closePlayback(Session current) {
        if (!current.playbackClosed.compareAndSet(false, true)) return;
        try {
            if (current.track != null) {
                try { current.track.pause(); current.track.flush(); current.track.stop(); }
                catch (RuntimeException ignored) { }
                try { current.track.release(); } catch (RuntimeException ignored) { }
            }
        } finally { current.closed.countDown(); }
    }

    private void restoreAudioState(Session current) {
        if (!current.audioStateRestored.compareAndSet(false, true)) return;
        try {
            if (current.focusGranted && current.focusRequest != null)
                manager.abandonAudioFocusRequest(current.focusRequest);
        } catch (RuntimeException ignored) { }
        try {
            if (current.modeChanged && manager.getMode() == AudioManager.MODE_IN_COMMUNICATION)
                manager.setMode(current.previousMode);
        } catch (RuntimeException ignored) { }
    }

    private void fail(Session current, String message) {
        if (session != current || !current.running || !current.reportedError.compareAndSet(false, true)) return;
        stop();
        notifyError(message);
    }

    private void notifyError(String message) {
        if (!disposed) listener.onError(message);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.length() > 180 ? "Le périphérique audio a refusé l’opération." : message;
    }

    private void execute(Runnable runnable) {
        try { lifecycle.execute(runnable); }
        catch (RejectedExecutionException ignored) { /* dispose() already completed. */ }
    }

    public void dispose() {
        disposed = true;
        stop();
        lifecycle.shutdown();
    }

    private static final class Chunk {
        final byte[] bytes;
        final long generation;
        Chunk(byte[] bytes, long generation) { this.bytes = bytes; this.generation = generation; }
    }

    private static final class Session {
        final Object queueLock = new Object();
        final ArrayDeque<Chunk> queue = new ArrayDeque<>();
        final CountDownLatch closed = new CountDownLatch(2);
        final AtomicBoolean captureClosed = new AtomicBoolean();
        final AtomicBoolean playbackClosed = new AtomicBoolean();
        final AtomicBoolean audioStateRestored = new AtomicBoolean();
        final AtomicBoolean reportedError = new AtomicBoolean();
        volatile boolean running;
        volatile boolean captureStarted;
        volatile boolean playbackStarted;
        volatile long playbackGeneration;
        int queuedBytes;
        int captureRate;
        int playbackRate;
        int previousMode;
        boolean modeChanged;
        boolean focusGranted;
        AudioRecord recorder;
        AudioTrack track;
        AcousticEchoCanceler aec;
        NoiseSuppressor noiseSuppressor;
        AudioFocusRequest focusRequest;
        Thread captureThread;
        Thread playbackThread;
    }
}
