package com.fred.realweargpt;

import android.graphics.Bitmap;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.json.JSONException;
import org.json.JSONObject;

final class ImageGenerator {
    static final String MODEL = "gpt-image-2.6-flare";
    static final String MODEL_LABEL = "GPT Image 2.6 Flare";
    static final int MAX_PROMPT_LENGTH = 32000;
    private static final String ENDPOINT = "https://api.openai.com/v1/images/generations";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    private final OkHttpClient client;
    private final String endpoint;

    ImageGenerator() {
        this(new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build(), ENDPOINT);
    }

    ImageGenerator(OkHttpClient client, String endpoint) {
        this.client = client;
        this.endpoint = endpoint;
    }

    Call generate(String apiKey, String prompt, ImageLoader.Callback callback) throws JSONException {
        JSONObject payload = new JSONObject()
                .put("model", MODEL)
                .put("prompt", prompt)
                .put("n", 1)
                .put("size", "1536x1024")
                .put("output_format", "jpeg")
                .put("output_compression", 85);
        Request request = new Request.Builder().url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        Call call = client.newCall(request);
        call.enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException error) {
                callback.onError(connectionError(error));
            }

            @Override public void onResponse(Call call, Response response) {
                Bitmap image;
                try (Response result = response) {
                    if (!result.isSuccessful()) {
                        callback.onError(httpError(result.code()));
                        return;
                    }
                    ResponseBody body = result.body();
                    if (body == null) throw new IOException("Réponse vide");
                    BufferedSource source = body.source();
                    if (source.request(MAX_RESPONSE_BYTES + 1)) throw new IOException("Réponse trop volumineuse");
                    String base64 = new JSONObject(source.readUtf8()).getJSONArray("data")
                            .getJSONObject(0).getString("b64_json");
                    image = ImageLoader.decode(Base64.getDecoder().decode(base64));
                } catch (InterruptedIOException error) {
                    callback.onError(connectionError(error));
                    return;
                } catch (IOException | JSONException | IllegalArgumentException error) {
                    callback.onError("Impossible de lire l’image générée.");
                    return;
                }
                callback.onImage(image);
            }
        });
        return call;
    }

    private static String connectionError(IOException error) {
        return error instanceof InterruptedIOException
                ? "Le délai de génération de l’image a expiré."
                : "Connexion interrompue pendant la génération de l’image.";
    }

    private static String httpError(int code) {
        switch (code) {
            case 401: return "Clé OpenAI refusée pour la génération d’images (401).";
            case 403:
            case 404: return MODEL_LABEL + " est inaccessible avec ce projet OpenAI (" + code + ").";
            case 429: return "Limite de débit ou quota OpenAI atteint pour les images (429).";
            default: return "OpenAI n’a pas pu générer l’image (HTTP " + code + ").";
        }
    }

    void dispose() {
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
    }
}
