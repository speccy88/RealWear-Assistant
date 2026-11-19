package com.fred.realweargpt;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Key import never places a key in logs, preferences, an Intent, or a command line. */
public final class SecretStore {
    private static final String ALIAS = "realwear_openai_v1";
    private final Context context;
    private final AtomicFile storage;
    public SecretStore(Context context) {
        this.context = context.getApplicationContext();
        storage = new AtomicFile(new File(context.getNoBackupFilesDir(), "openai.enc"));
        internalImport().getParentFile().mkdirs();
        File external = externalImport();
        if (external != null) external.getParentFile().mkdirs();
    }
    public File internalImport() { return new File(context.getFilesDir(), "import/openai.key"); }
    public File externalImport() {
        File base = context.getExternalFilesDir(null);
        return base == null ? null : new File(base, "import/openai.key");
    }
    public synchronized boolean isConfigured() { return storage.getBaseFile().isFile(); }
    public synchronized String importIfPresent() throws Exception {
        File f = internalImport();
        if (!f.isFile()) f = externalImport();
        if (f == null || !f.isFile()) return null;
        if (f.length() < 20 || f.length() > 2048) throw new IOException("Le fichier de clé a une taille invalide.");
        byte[] bytes;
        try (InputStream in = new FileInputStream(f)) { bytes = readBounded(in, 2048); }
        String value = new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        Arrays.fill(bytes, (byte) 0);
        if (!value.startsWith("sk-") || value.length() < 20 || value.length() > 1024 || java.util.regex.Pattern.compile("\\s").matcher(value).find())
            throw new IOException("Format de clé non reconnu. Le fichier doit contenir seulement la clé API.");
        save(value);
        return f.delete() ? "Clé importée et chiffrée. Accès API à tester." : "Clé chiffrée. Supprime le fichier d’importation resté sur l’appareil.";
    }
    private SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            if (!create) throw new IOException("Clé de chiffrement absente. Réimporte la clé API.");
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
            gen.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }
    private void save(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true));
        byte[] iv = cipher.getIV(), plain = value.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted;
        try { encrypted = cipher.doFinal(plain); } finally { Arrays.fill(plain, (byte) 0); }
        FileOutputStream out = null;
        try {
            out = storage.startWrite();
            out.write(1); out.write(iv.length); out.write(iv); out.write(encrypted);
            storage.finishWrite(out);
        } catch (IOException e) { if (out != null) storage.failWrite(out); throw e; }
    }
    public synchronized String load() throws Exception {
        if (!isConfigured()) return null;
        byte[] packed = storage.readFully();
        if (packed.length < 30 || packed.length > 4096 || packed[0] != 1 || packed[1] != 12)
            throw new IOException("Clé chiffrée illisible. Réimporte la clé API.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Arrays.copyOfRange(packed, 2, 14)));
        byte[] decrypted = cipher.doFinal(packed, 14, packed.length - 14);
        try { return new String(decrypted, StandardCharsets.UTF_8); } finally { Arrays.fill(decrypted, (byte) 0); }
    }
    public synchronized void delete() throws Exception {
        storage.delete();
        internalImport().delete();
        File external = externalImport(); if (external != null) external.delete();
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS);
    }
    static byte[] readBounded(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) { if (result.size() + n > limit) throw new IOException("Contenu trop volumineux."); result.write(buf, 0, n); }
        return result.toByteArray();
    }
}
