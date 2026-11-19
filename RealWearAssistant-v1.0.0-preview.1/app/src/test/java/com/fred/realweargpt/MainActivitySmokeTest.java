package com.fred.realweargpt;

import android.Manifest;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Android 8.1 framework smoke tests, not a physical HMT-1 or a graphics emulator. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27, qualifiers = "w854dp-h480dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class MainActivitySmokeTest {
    private ActivityController<MainActivity> controller;

    private MainActivity launch(boolean demo) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), MainActivity.class);
        if (demo) intent.putExtra("demo", true);
        controller = Robolectric.buildActivity(MainActivity.class, intent).setup().visible();
        shadowOf(Looper.getMainLooper()).idle();
        return controller.get();
    }

    @After public void destroy() {
        if (controller != null) {
            controller.pause().stop().destroy();
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Test public void launchesWithoutApiKeyAndShowsNativeControls() {
        MainActivity activity = launch(false);
        View root = activity.getWindow().getDecorView();
        assertNotNull(text(root, "RealWear Assistant"));
        assertNotNull(text(root, "Démarrer"));
        assertNotNull(text(root, "Photo"));
        assertNotNull(text(root, "WearHF · prêt"));
        assertFalse(new SecretStore(activity).isConfigured());
    }

    @Test public void startWithoutKeyDisplaysConfigurationInsteadOfOpeningApi() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA);
        MainActivity activity = launch(false);
        Button start = (Button) text(activity.getWindow().getDecorView(), "Démarrer");
        assertNotNull(start);
        start.performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(text(activity.getWindow().getDecorView(), "Configurer la clé OpenAI"));
        assertNotNull(text(activity.getWindow().getDecorView(), "Démarrer"));
    }

    @Test public void offlineDemoCyclesThroughTextTableImageAndSources() {
        MainActivity activity = launch(true);
        View root = activity.getWindow().getDecorView();
        Button next = (Button) text(root, "Suivant");
        assertNotNull(text(root, "Démo · hors connexion"));
        assertNotNull(text(root, "Affichage pendant la conversation"));
        next.performClick(); shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(text(root, "Exemple de comparaison"));
        assertNotNull(text(root, "Capture et lecture"));
        next.performClick(); shadowOf(Looper.getMainLooper()).idle();
        ImageView image = firstImage(root);
        assertNotNull(image);
        assertEquals("Exemple de schéma affiché", image.getContentDescription());
        next.performClick(); shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(text(root, "Sources de la recherche"));
        assertNotNull(text(root, "1. Documentation GPT-Live"));
    }

    @Test public void imageUsesTheFullContentHeightBesideTheMenu() {
        MainActivity activity = launch(true);
        View root = activity.getWindow().getDecorView();
        Button next = (Button) text(root, "Suivant");
        next.performClick();
        next.performClick();
        View content = activity.findViewById(android.R.id.content);
        content.measure(View.MeasureSpec.makeMeasureSpec(854, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, 854, 480);

        ImageView image = firstImage(content);
        TextView menu = text(content, "Menu");
        assertNotNull(image);
        assertEquals(ImageView.ScaleType.FIT_CENTER, image.getScaleType());
        assertTrue(image.getHeight() >= 450);
        assertTrue(image.getWidth() >= 680);
        assertTrue(menu.isShown());
        int[] imagePosition = new int[2];
        int[] menuPosition = new int[2];
        image.getLocationInWindow(imagePosition);
        menu.getLocationInWindow(menuPosition);
        assertTrue(menuPosition[0] + menu.getWidth() < imagePosition[0]);
    }

    @Test public void allNumberedMenuItemsFitOnTheRealWearScreenInOrder() {
        MainActivity activity = launch(false);
        View content = activity.findViewById(android.R.id.content);
        Button menu = (Button) text(content, "Menu");
        assertEquals("4  Menu", menu.getText().toString());
        assertTrue(menu.getContentDescription().toString().contains("numéro quatre"));
        menu.performClick();
        content.measure(View.MeasureSpec.makeMeasureSpec(854, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, 854, 480);

        ViewGroup options = (ViewGroup) text(content, "Conversation").getParent();
        assertEquals(11, options.getChildCount());
        int previousBottom = 0;
        for (int i = 0; i < options.getChildCount(); i++) {
            Button button = (Button) options.getChildAt(i);
            assertTrue(button.getText().toString().startsWith((i + 1) + "  "));
            assertTrue(button.isShown());
            int[] position = new int[2];
            button.getLocationInWindow(position);
            assertTrue(position[1] >= previousBottom);
            previousBottom = position[1] + button.getHeight();
            assertTrue(previousBottom <= 480);
        }
        assertFalse(menu.isShown());
        text(content, "Retour").performClick();
        assertTrue(menu.isShown());
    }

    @Test public void actionButtonFinishesIdleActivityAndLifecycleCanDestroyIt() {
        MainActivity activity = launch(false);
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, 500)));
        assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, 500)));
        assertTrue(activity.isFinishing());
        // @After explicitly exercises pause -> stop -> destroy without touching real hardware.
    }

    @Test public void screenDeckNavigatesNativeTableImageAndVerifiedSources() throws Exception {
        MainActivity activity = launch(false);
        ScreenDeck deck = new ScreenDeck(activity);
        deck.text("Texte", "Une information", false);
        deck.table("Comparaison", new JSONArray("[\"Objet\",\"Valeur\"]"), new JSONArray("[[\"A\",\"12 V\"]]"));
        assertNotNull(text(deck, "12 V"));
        deck.image("Photo de test", Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888), "Image locale de test");
        assertNotNull(firstImage(deck));
        deck.zoom(true); deck.next(); deck.previous(); deck.hideContent(true); deck.hideContent(false);
        deck.back(); assertNotNull(text(deck, "Comparaison"));
        deck.addSource("Source officielle", "https://developers.openai.com/");
        deck.addSource("Source rejetée", "http://127.0.0.1/");
        deck.showSources();
        assertNotNull(text(deck, "1. Source officielle"));
        assertNull(text(deck, "2. Source rejetée"));
    }

    private static TextView text(View view, String expected) {
        if (view instanceof TextView) {
            String label = ((TextView) view).getText().toString();
            if (view instanceof Button) label = label.replaceFirst("^[0-9]+  ", "");
            if (expected.equals(label)) return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = text(group.getChildAt(i), expected);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static ImageView firstImage(View view) {
        if (view instanceof ImageView && ((ImageView) view).getDrawable() != null) return (ImageView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ImageView found = firstImage(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
