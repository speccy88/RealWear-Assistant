package com.fred.realweargpt;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.ByteArrayOutputStream;
import java.util.List;

/** Legacy Camera is intentional: HMT-1 firmware exposes the most stable path here. */
@SuppressWarnings("deprecation")
public final class CameraCapture {
    public interface Callback { void onPhoto(byte[] jpeg, Bitmap preview); void onError(String message); }
    private final Context context;
    private final HandlerThread thread = new HandlerThread("realwear-camera");
    private final Handler handler;
    private Camera camera;
    private SurfaceTexture texture;
    private Callback pending;
    private int generation;
    public CameraCapture(Context c) { context = c.getApplicationContext(); thread.start(); handler = new Handler(thread.getLooper()); }
    public void capture(Callback callback) {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { callback.onError("Autorise la caméra dans les paramètres de l’application."); return; }
        handler.post(() -> {
            if (pending != null) { callback.onError("Une prise de photo est déjà en cours."); return; }
            pending = callback; final int token = ++generation;
            try {
                int cameraId = 0;
                for (int i = 0; i < Camera.getNumberOfCameras(); i++) { Camera.CameraInfo info = new Camera.CameraInfo(); Camera.getCameraInfo(i, info); if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { cameraId = i; break; } }
                Camera.CameraInfo info = new Camera.CameraInfo(); Camera.getCameraInfo(cameraId, info);
                camera = Camera.open(cameraId);
                Camera.Parameters p = camera.getParameters();
                Camera.Size best = choose(p.getSupportedPictureSizes(), 1920, 1080);
                if (best != null) p.setPictureSize(best.width, best.height);
                Camera.Size preview = choose(p.getSupportedPreviewSizes(), 640, 480);
                if (preview != null) p.setPreviewSize(preview.width, preview.height);
                List<String> focus = p.getSupportedFocusModes();
                if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                p.setJpegQuality(85); p.setRotation(0); camera.setParameters(p);
                texture = new SurfaceTexture(0); camera.setPreviewTexture(texture); camera.startPreview();
                // The display is locked to landscape. Most HMT-1 firmware already rotates JPEG pixels.
                // Keep raw sensor orientation; portrait source JPEG is rotated only through its EXIF in normalize().
                handler.postDelayed(() -> {
                    if (token != generation || camera == null) return;
                    try { camera.takePicture(null, null, (data, ignored) -> finishPhoto(token, data)); }
                    catch (Exception e) { fail(token, "La caméra ne peut pas prendre de photo."); }
                }, 700);
                handler.postDelayed(() -> fail(token, "La caméra ne répond pas. Réessaie après avoir fermé les autres applications caméra."), 10000);
            } catch (Exception e) { fail(token, "Caméra indisponible ou déjà utilisée par une autre application."); }
        });
    }
    private Camera.Size choose(List<Camera.Size> sizes, int width, int height) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = sizes.get(0); long bestScore = Long.MAX_VALUE;
        for (Camera.Size size : sizes) { long score = Math.abs((long) size.width * size.height - (long) width * height); if (score < bestScore) { best = size; bestScore = score; } }
        return best;
    }
    private void finishPhoto(int token, byte[] data) {
        if (token != generation || pending == null) return;
        Callback callback = pending;
        try {
            Bitmap bitmap = ImageLoader.decode(data);
            androidx.exifinterface.media.ExifInterface exif = new androidx.exifinterface.media.ExifInterface(new java.io.ByteArrayInputStream(data));
            int orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
            int degrees = orientation == 6 ? 90 : orientation == 3 ? 180 : orientation == 8 ? 270 : 0;
            if (degrees != 0) { Matrix matrix = new Matrix(); matrix.postRotate(degrees); Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true); if (rotated != bitmap) bitmap.recycle(); bitmap = rotated; }
            int longSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (longSide > 1280) { float scale = 1280f / longSide; Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true); if (scaled != bitmap) bitmap.recycle(); bitmap = scaled; }
            ByteArrayOutputStream output = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output);
            pending = null; releaseCamera(); callback.onPhoto(output.toByteArray(), bitmap);
        } catch (Exception e) { fail(token, "La photo a été prise, mais son traitement a échoué."); }
    }
    private void fail(int token, String message) { if (token != generation || pending == null) return; Callback cb = pending; pending = null; releaseCamera(); cb.onError(message); }
    private void releaseCamera() { if (camera != null) { try { camera.stopPreview(); } catch (RuntimeException ignored) { } try { camera.release(); } catch (RuntimeException ignored) { } camera = null; } if (texture != null) { texture.release(); texture = null; } }
    public void cancel() { handler.post(() -> { generation++; Callback cb = pending; pending = null; releaseCamera(); if (cb != null) cb.onError("Prise de photo annulée."); }); }
    public void dispose() { handler.post(() -> { generation++; pending = null; releaseCamera(); thread.quitSafely(); }); }
}
