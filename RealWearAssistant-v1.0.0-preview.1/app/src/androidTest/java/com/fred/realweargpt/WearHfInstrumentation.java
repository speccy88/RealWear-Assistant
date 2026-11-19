package com.fred.realweargpt;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Android-level broadcast/lifecycle tests without an AndroidX test dependency.
 * Simulates WearHF acknowledgements on an emulator; deliberately refuses real RealWear hardware.
 * No network, API key, microphone recording or camera is involved.
 */
public final class WearHfInstrumentation extends Instrumentation {
    private static final int TEST_COUNT = 4;
    private int passed;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle summary = new Bundle();
        if (WearHfController.isRealWearDevice()) {
            summary.putString("stream", "Tests skipped: simulated WearHF broadcasts must run on an emulator, not a RealWear device.\n");
            summary.putInt("numtests", 0);
            finish(Activity.RESULT_CANCELED, summary);
            return;
        }
        try {
            runCase("releaseRequiresExternalAcknowledgement", this::testAcknowledgement);
            runCase("timeoutRestoresWearHfWithoutGrant", this::testTimeout);
            runCase("reclaimWaitsForClientAudioClose", this::testReclaim);
            runCase("cancelledRequestIgnoresLateAcknowledgement", this::testCancelAndRestart);
            summary.putString("stream", "WearHF instrumentation: " + passed + "/" + TEST_COUNT + " tests passed.\n");
            summary.putInt("numtests", TEST_COUNT);
            summary.putInt("passed", passed);
            finish(Activity.RESULT_OK, summary);
        } catch (Throwable failure) {
            summary.putString("stream", "WearHF instrumentation failed after " + passed + " tests: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n");
            summary.putInt("numtests", TEST_COUNT);
            summary.putInt("passed", passed);
            summary.putString("shortMsg", "WearHF lifecycle assertion failed");
            finish(Activity.RESULT_CANCELED, summary);
        }
    }

    private void runCase(String name, TestCase test) throws Exception {
        Bundle started = new Bundle();
        started.putString("class", getClass().getName());
        started.putString("test", name);
        started.putInt("numtests", TEST_COUNT);
        started.putInt("current", passed + 1);
        sendStatus(1, started);
        test.run();
        passed++;
        started.putString("stream", "PASS " + name + "\n");
        sendStatus(0, started);
    }

    private void testAcknowledgement() throws Exception {
        Probe probe = new Probe();
        try {
            probe.startRequest();
            require(probe.request.await(1, TimeUnit.SECONDS), "RELEASE_MIC was not sent");
            require(probe.grants.get() == 0, "Microphone granted before an acknowledgement");
            require(!probe.grant.await(150, TimeUnit.MILLISECONDS), "Grant was based on a delay, not an acknowledgement");
            // Echoes of the application's own broadcast must not be treated as a WearHF reply.
            probe.send(WearHfController.MIC_RELEASED, probe.context.getPackageName());
            waitForIdleSync();
            require(!probe.grant.await(150, TimeUnit.MILLISECONDS), "Own MIC_RELEASED falsely granted microphone");
            probe.send(WearHfController.MIC_RELEASED, null);
            require(probe.grant.await(1, TimeUnit.SECONDS), "A documented acknowledgement without extras was ignored");
            require(probe.grants.get() == 1, "Grant callback count is not one");
            require(probe.error.get() == null, "Unexpected error");
            probe.send(WearHfController.MIC_RELEASED, null);
            waitForIdleSync();
            Thread.sleep(50);
            require(probe.grants.get() == 1, "Duplicate acknowledgement granted twice");
        } finally { probe.close(); }
    }

    private void testTimeout() throws Exception {
        Probe probe = new Probe();
        try {
            probe.startRequest();
            require(probe.request.await(1, TimeUnit.SECONDS), "RELEASE_MIC was not sent");
            require(probe.failed.await(4500, TimeUnit.MILLISECONDS), "Missing WearHF reply did not time out");
            require(probe.returned.await(1, TimeUnit.SECONDS), "Timeout did not send MIC_RELEASED to restore WearHF");
            require(probe.grants.get() == 0, "Timed-out request granted microphone");
            require(probe.error.get() != null && !probe.error.get().isEmpty(), "Missing readable timeout error");
            probe.send(WearHfController.MIC_RELEASED, null);
            waitForIdleSync();
            require(!probe.grant.await(150, TimeUnit.MILLISECONDS), "Late timeout acknowledgement granted microphone");
        } finally { probe.close(); }
    }

    private void testReclaim() throws Exception {
        Probe probe = new Probe();
        try {
            probe.startRequest();
            require(probe.request.await(1, TimeUnit.SECONDS), "RELEASE_MIC was not sent");
            probe.send(WearHfController.MIC_RELEASED, null);
            require(probe.grant.await(1, TimeUnit.SECONDS), "Initial grant failed");
            probe.send(WearHfController.RELEASE_MIC, "com.realwear.wearhf");
            require(probe.reclaimed.await(1, TimeUnit.SECONDS), "External release did not notify the client");
            require(!probe.returned.await(150, TimeUnit.MILLISECONDS), "Controller restored WearHF before audio cleanup callback");
            probe.send(WearHfController.RELEASE_MIC, "com.realwear.wearhf");
            probe.send(WearHfController.MIC_RELEASED, null);
            waitForIdleSync();
            Thread.sleep(50);
            require(probe.reclaims.get() == 1, "Duplicate reclaim callback");
            require(probe.grants.get() == 1, "Acknowledgement during reclamation granted microphone again");
            // This models AudioEngine.stop(onStopped) completing the native resource cleanup.
            runOnMainSync(probe.controller::release);
            require(probe.returned.await(1, TimeUnit.SECONDS), "Client cleanup did not restore WearHF");
            require(probe.error.get() == null, "Reclamation produced an unexpected error");
        } finally { probe.close(); }
    }

    private void testCancelAndRestart() throws Exception {
        Probe probe = new Probe();
        try {
            runOnMainSync(() -> { probe.controller.start(probe.callback); probe.controller.release(); });
            require(probe.returned.await(1, TimeUnit.SECONDS), "Cancellation did not restore WearHF");
            probe.send(WearHfController.MIC_RELEASED, null);
            waitForIdleSync();
            require(!probe.grant.await(150, TimeUnit.MILLISECONDS), "Cancelled request accepted a late acknowledgement");
            probe.startRequest();
            probe.send(WearHfController.MIC_RELEASED, null);
            require(probe.grant.await(1, TimeUnit.SECONDS), "A fresh request could not start after cancellation");
            require(probe.grants.get() == 1, "Restart granted more than once");
            require(probe.error.get() == null, "Restart returned an unexpected error");
        } finally { probe.close(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface TestCase { void run() throws Exception; }

    private final class Probe {
        final Context context = getTargetContext();
        final WearHfController controller = new WearHfController(context, true);
        final CountDownLatch request = new CountDownLatch(1);
        final CountDownLatch grant = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);
        final CountDownLatch returned = new CountDownLatch(1);
        final CountDownLatch reclaimed = new CountDownLatch(1);
        final AtomicInteger grants = new AtomicInteger();
        final AtomicInteger reclaims = new AtomicInteger();
        final AtomicReference<String> error = new AtomicReference<>();
        final WearHfController.Callback callback = new WearHfController.Callback() {
            @Override public void onGranted() { grants.incrementAndGet(); grant.countDown(); }
            @Override public void onError(String message) { error.set(message); failed.countDown(); }
            @Override public void onReclaimed() { reclaims.incrementAndGet(); reclaimed.countDown(); }
        };
        final BroadcastReceiver observer = new BroadcastReceiver() {
            @Override public void onReceive(Context unused, Intent intent) {
                if (!context.getPackageName().equals(intent.getStringExtra(WearHfController.SOURCE_PACKAGE))) return;
                if (WearHfController.RELEASE_MIC.equals(intent.getAction())) request.countDown();
                if (WearHfController.MIC_RELEASED.equals(intent.getAction())) returned.countDown();
            }
        };

        Probe() {
            runOnMainSync(() -> {
                IntentFilter filter = new IntentFilter();
                filter.addAction(WearHfController.RELEASE_MIC);
                filter.addAction(WearHfController.MIC_RELEASED);
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(observer, filter, Context.RECEIVER_EXPORTED);
                else context.registerReceiver(observer, filter);
            });
        }

        void startRequest() { runOnMainSync(() -> controller.start(callback)); }

        void send(String action, String source) {
            Intent intent = new Intent(action).setPackage(context.getPackageName());
            if (source != null) intent.putExtra(WearHfController.SOURCE_PACKAGE, source);
            context.sendBroadcast(intent);
        }

        void close() {
            runOnMainSync(() -> {
                controller.dispose();
                context.unregisterReceiver(observer);
            });
            waitForIdleSync();
        }
    }
}
