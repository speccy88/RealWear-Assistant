package com.fred.realweargpt;

import android.content.Context;
import android.content.SharedPreferences;

/** Device preferences contain no API credentials. */
public final class AppConfig {
    private final SharedPreferences p;
    public AppConfig(Context context) { p = context.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    public String liveModel() { return p.getString("live_model", "gpt-live-1"); }
    public String backendModel() { return p.getString("backend_model", "gpt-6-luna"); }
    public String voice() { return p.getString("voice", "marin"); }
    public boolean webSearch() { return p.getBoolean("web_search", true); }
    public boolean imageGeneration() { return p.getBoolean("image_generation", true); }
    public boolean forceWearHf() { return p.getBoolean("wearhf", false); }
    public boolean autoDisplay() { return p.getBoolean("auto_display", true); }
    public int maxMinutes() { return p.getInt("max_minutes", 30); }
    public int fontSize() { return p.getInt("font_size", 24); }
    public void save(String live, String backend, String voice, boolean web, boolean images,
                     boolean wearhf, boolean display, int minutes) {
        p.edit().putString("live_model", live.trim()).putString("backend_model", backend.trim())
            .putString("voice", voice.trim()).putBoolean("web_search", web)
            .putBoolean("image_generation", images).putBoolean("wearhf", wearhf)
            .putBoolean("auto_display", display).putInt("max_minutes", Math.max(1, Math.min(120, minutes))).apply();
    }
    public void fontSize(int value) { p.edit().putInt("font_size", Math.max(18, Math.min(38, value))).apply(); }
}
