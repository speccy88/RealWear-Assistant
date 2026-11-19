package com.fred.realweargpt;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public class WearHfRecoveryTest {
    @Test public void recoveredSessionReturnsMicrophoneOnlyOnce() {
        Application app = RuntimeEnvironment.getApplication();
        SharedPreferences recovery = app.getSharedPreferences(WearHfController.RECOVERY_PREFS, Context.MODE_PRIVATE);
        recovery.edit().putBoolean(WearHfController.MIC_RETURN_PENDING, true).commit();

        new WearHfController(app, false).recoverInterruptedSession();
        assertEquals(0, broadcasts(app, WearHfController.MIC_RELEASED));
        assertTrue(recovery.getBoolean(WearHfController.MIC_RETURN_PENDING, false));

        WearHfController controller = new WearHfController(app, true);
        controller.recoverInterruptedSession();
        controller.recoverInterruptedSession();
        assertEquals(1, broadcasts(app, WearHfController.MIC_RELEASED));
        assertFalse(recovery.getBoolean(WearHfController.MIC_RETURN_PENDING, false));
    }

    @Test public void activityRecreationDoesNotReturnMicrophoneDuringAnotherControllersRequest() {
        Application app = RuntimeEnvironment.getApplication();
        SharedPreferences recovery = app.getSharedPreferences(WearHfController.RECOVERY_PREFS, Context.MODE_PRIVATE);
        WearHfController controller = new WearHfController(app, true);
        try {
            controller.start(new WearHfController.Callback() {
                @Override public void onGranted() { fail("Unexpected WearHF acknowledgement"); }
                @Override public void onError(String message) { fail(message); }
                @Override public void onReclaimed() { fail("Unexpected WearHF reclaim"); }
            });
            assertTrue(recovery.getBoolean(WearHfController.MIC_RETURN_PENDING, false));
            assertEquals(1, broadcasts(app, WearHfController.RELEASE_MIC));
            new WearHfController(app, true).recoverInterruptedSession();
            assertEquals(0, broadcasts(app, WearHfController.MIC_RELEASED));
        } finally {
            controller.dispose();
        }
        assertEquals(1, broadcasts(app, WearHfController.MIC_RELEASED));
        assertFalse(recovery.getBoolean(WearHfController.MIC_RETURN_PENDING, false));
    }

    private static long broadcasts(Application app, String action) {
        return shadowOf(app).getBroadcastIntents().stream().map(Intent::getAction).filter(action::equals).count();
    }
}
