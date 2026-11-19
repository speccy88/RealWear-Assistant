package com.fred.realweargpt;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.*;

public final class ImageLoader {
    public interface Callback { void onImage(Bitmap bitmap); void onError(String error); }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).dns(hostname -> {
            List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
            for (InetAddress ip : addresses) if (!UrlPolicy.isPublicAddress(ip)) throw new UnknownHostException("Adresse privée refusée");
            return addresses;
        }).build();
    public void load(String url, Callback callback) {
        worker.execute(() -> {
            try {
                String current = url;
                for (int redirects = 0; redirects <= 3; redirects++) {
                    if (!UrlPolicy.isPublicHttps(current)) throw new IOException("Une URL HTTPS publique d’image est requise.");
                    Request request = new Request.Builder().url(current).header("Accept", "image/jpeg,image/png,image/webp").build();
                    try (Response response = client.newCall(request).execute()) {
                        if (response.isRedirect()) {
                            String location = response.header("Location");
                            HttpUrl resolved = location == null ? null : request.url().resolve(location);
                            if (resolved == null || redirects == 3) throw new IOException("Redirection d’image invalide.");
                            current = resolved.toString(); continue;
                        }
                        if (!response.isSuccessful() || response.body() == null) throw new IOException("Image indisponible (HTTP " + response.code() + ").");
                        String type = response.header("Content-Type", "");
                        if (!type.startsWith("image/") || type.contains("svg")) throw new IOException("L’URL ne correspond pas à une image JPEG, PNG ou WebP.");
                        byte[] bytes = SecretStore.readBounded(response.body().byteStream(), 8 * 1024 * 1024);
                        callback.onImage(decode(bytes)); return;
                    }
                }
            } catch (Exception e) { callback.onError(e instanceof IOException ? e.getMessage() : "Impossible de charger cette image."); }
        });
    }
    public static Bitmap decode(byte[] bytes) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0 || (long) o.outWidth * o.outHeight > 100_000_000L) throw new IOException("Format ou dimensions d’image non pris en charge.");
        o.inSampleSize = 1; while (o.outWidth / o.inSampleSize > 1600 || o.outHeight / o.inSampleSize > 1600) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false; o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (b == null) throw new IOException("Image illisible."); return b;
    }
    public void dispose() { client.dispatcher().cancelAll(); worker.shutdownNow(); client.connectionPool().evictAll(); }
}
