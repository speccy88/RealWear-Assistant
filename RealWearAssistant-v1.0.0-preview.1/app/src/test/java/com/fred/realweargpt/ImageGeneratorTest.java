package com.fred.realweargpt;

import android.graphics.Bitmap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 27)
public class ImageGeneratorTest {
    @Test public void voiceToolIsAvailableOnlyWhenImageGenerationIsEnabled() throws Exception {
        JSONArray disabledTools = ToolCatalog.create(false);
        JSONArray enabledTools = ToolCatalog.create(true);
        assertEquals(disabledTools.length() + 1, enabledTools.length());
        JSONObject imageTool = enabledTools.getJSONObject(enabledTools.length() - 1);
        assertEquals("function", imageTool.getString("type"));
        assertEquals("generate_image", imageTool.getString("name"));
        assertEquals("prompt", imageTool.getJSONObject("parameters").getJSONArray("required").getString(0));
    }

    @Test public void sendsFlareRequestAndDecodesReturnedImage() throws Exception {
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aO9sAAAAASUVORK5CYII=";
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"data\":[{\"b64_json\":\"" + png + "\"}]}"));
            ImageGenerator generator = new ImageGenerator(new OkHttpClient(), server.url("/v1/images/generations").toString());
            try {
                CompletableFuture<Bitmap> result = new CompletableFuture<>();
                generator.generate("sk-test", "Un moteur électrique en coupe", new ImageLoader.Callback() {
                    @Override public void onImage(Bitmap bitmap) { result.complete(bitmap); }
                    @Override public void onError(String error) { result.completeExceptionally(new AssertionError(error)); }
                });
                assertEquals(1, result.get(8, TimeUnit.SECONDS).getWidth());
                RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals("POST", request.getMethod());
                assertEquals("/v1/images/generations", request.getPath());
                assertEquals("Bearer sk-test", request.getHeader("Authorization"));
                JSONObject payload = new JSONObject(request.getBody().readUtf8());
                assertEquals("gpt-image-2.6-flare", payload.getString("model"));
                assertEquals("Un moteur électrique en coupe", payload.getString("prompt"));
                assertEquals(1, payload.getInt("n"));
                assertEquals("jpeg", payload.getString("output_format"));
            } finally {
                generator.dispose();
            }
        }
    }

    @Test public void neverReturnsAnApiErrorBodyToTheAssistant() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(403).setBody("Internal details: sk-test"));
            ImageGenerator generator = new ImageGenerator(new OkHttpClient(), server.url("/v1/images/generations").toString());
            try {
                CompletableFuture<String> result = new CompletableFuture<>();
                generator.generate("sk-test", "Un arbre", new ImageLoader.Callback() {
                    @Override public void onImage(Bitmap bitmap) { result.completeExceptionally(new AssertionError("Image inattendue")); }
                    @Override public void onError(String error) { result.complete(error); }
                });
                String error = result.get(8, TimeUnit.SECONDS);
                assertTrue(error.contains("403"));
                assertFalse(error.contains("sk-test"));
            } finally {
                generator.dispose();
            }
        }
    }
}
