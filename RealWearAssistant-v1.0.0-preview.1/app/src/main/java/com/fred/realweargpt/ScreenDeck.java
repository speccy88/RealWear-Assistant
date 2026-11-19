package com.fred.realweargpt;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Native, scrollable display: no HTML/JavaScript from the model is executed. */
public final class ScreenDeck extends LinearLayout {
    public interface SourceListener { void onSource(String url); }
    public static final int TEXT = 0xFFEAF3F7, MUTED = 0xFF9EB4C8, ACCENT = 0xFF79DBCA;
    private final Context context;
    private final LinearLayout heading;
    private final TextView title, counter;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final FrameLayout imageViewport;
    private final ArrayDeque<Card> history = new ArrayDeque<>();
    private final List<String[]> sources = new ArrayList<>();
    private Card current;
    private int fontSize = 24;
    private float imageZoom = 1;
    private ImageView image;
    private SourceListener sourceListener;
    private boolean hidden;
    private static class Card {
        String type, title, text;
        Bitmap bitmap;
        JSONArray columns, rows;
        Card(String type, String title) { this.type = type; this.title = title; }
    }
    public ScreenDeck(Context context) {
        super(context); this.context = context;
        setOrientation(VERTICAL); setPadding(dp(18), dp(12), dp(18), dp(10));
        GradientDrawable background = new GradientDrawable(); background.setColor(0xFF142433); background.setCornerRadius(dp(14)); background.setStroke(dp(1), 0xFF2C4355); setBackground(background);
        heading = new LinearLayout(context); heading.setGravity(Gravity.CENTER_VERTICAL);
        title = label("Assistant personnel", 22, TEXT); title.setTypeface(null, Typeface.BOLD); title.setMaxLines(1);
        counter = label("", 13, MUTED); counter.setGravity(Gravity.END);
        heading.addView(title, new LayoutParams(0, -2, 1)); heading.addView(counter); addView(heading);
        scroll = new ScrollView(context); scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(true);
        body = new LinearLayout(context); body.setOrientation(VERTICAL); body.setPadding(0, dp(12), 0, dp(8)); scroll.addView(body);
        addView(scroll, new LayoutParams(-1, 0, 1));
        imageViewport = new FrameLayout(context);
        imageViewport.setVisibility(GONE);
        addView(imageViewport, new LayoutParams(-1, 0, 1));
        scroll.getViewTreeObserver().addOnScrollChangedListener(this::pageIndicator);
    }
    public void setSourceListener(SourceListener listener) { sourceListener = listener; }
    public void setFontSize(int sp) { fontSize = sp; if (current != null) render(current); }
    public int getFontSize() { return fontSize; }
    public void text(String heading, String text, boolean remember) {
        Card c = new Card("text", safe(heading, 100)); c.text = safe(text, 12000); display(c, remember);
    }
    public void table(String heading, JSONArray columns, JSONArray rows) {
        Card c = new Card("table", safe(heading, 100)); c.columns = columns; c.rows = rows; display(c, true);
    }
    public void image(String heading, Bitmap bitmap, String caption) {
        Card c = new Card("image", safe(heading, 100)); c.bitmap = bitmap; c.text = safe(caption, 1600); display(c, true);
    }
    public void fullImage(String description, Bitmap bitmap) {
        Card c = new Card("fullImage", safe(description, 100));
        c.bitmap = bitmap;
        display(c, true);
    }
    private void display(Card c, boolean remember) {
        if (remember && current != null && !"sources".equals(current.type)) { history.addLast(current); while (history.size() > 8) history.removeFirst(); }
        current = c; imageZoom = 1; render(c);
    }
    private void render(Card c) {
        body.removeAllViews(); imageViewport.removeAllViews(); image = null;
        title.setText(c.title); counter.setText("");
        boolean fullImage = "fullImage".equals(c.type);
        heading.setVisibility(fullImage ? GONE : VISIBLE);
        scroll.setVisibility(fullImage ? GONE : VISIBLE);
        imageViewport.setVisibility(fullImage ? VISIBLE : GONE);
        setPadding(fullImage ? 0 : dp(18), fullImage ? 0 : dp(12), fullImage ? 0 : dp(18), fullImage ? 0 : dp(10));
        if (fullImage) {
            image = createImageView(c);
            imageViewport.addView(image, new FrameLayout.LayoutParams(-1, -1));
            applyContentVisibility();
            return;
        }
        if ("image".equals(c.type)) {
            image = createImageView(c);
            image.setAdjustViewBounds(true);
            body.addView(image, new LayoutParams(-1, dp(210)));
            if (c.text != null && !c.text.isEmpty()) body.addView(label(c.text, Math.max(18, fontSize - 4), MUTED));
        } else if ("table".equals(c.type)) {
            int columns = Math.min(3, c.columns == null ? 0 : c.columns.length());
            if (columns == 0) { body.addView(label("Aucune colonne à afficher.", fontSize, TEXT)); }
            else {
                addTableRow(c.columns, columns, true);
                for (int row = 0; c.rows != null && row < Math.min(c.rows.length(), 12); row++) {
                    JSONArray values = c.rows.optJSONArray(row); if (values != null) addTableRow(values, columns, false);
                }
            }
        } else if ("sources".equals(c.type)) {
            if (sources.isEmpty()) body.addView(label("Les sources des recherches apparaîtront ici.", fontSize, MUTED));
            for (int i = 0; i < sources.size(); i++) {
                String[] s = sources.get(i); final String url = s[1];
                TextView link = label((i + 1) + ". " + s[0], fontSize, ACCENT); link.setPadding(0, dp(8), 0, dp(4));
                link.setContentDescription("Source " + (i + 1)); link.setOnClickListener(v -> { if (sourceListener != null) sourceListener.onSource(url); });
                body.addView(link); body.addView(label(url, 14, MUTED));
            }
        } else body.addView(label(c.text == null ? "" : c.text, fontSize, TEXT));
        scroll.post(() -> { scroll.scrollTo(0, 0); pageIndicator(); });
        applyContentVisibility();
    }
    private ImageView createImageView(Card c) {
        ImageView view = new ImageView(context);
        view.setImageBitmap(c.bitmap);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setContentDescription(c.title);
        return view;
    }
    private void addTableRow(JSONArray row, int count, boolean heading) {
        LinearLayout line = new LinearLayout(context); line.setBackgroundColor(heading ? 0xFF243B4C : Color.TRANSPARENT);
        for (int i = 0; i < count; i++) {
            TextView cell = label(safe(row.optString(i, ""), 350), Math.max(16, fontSize - 5), heading ? ACCENT : TEXT);
            cell.setPadding(dp(8), dp(6), dp(8), dp(6)); if (heading) cell.setTypeface(null, Typeface.BOLD);
            line.addView(cell, new LayoutParams(0, -2, 1));
        }
        body.addView(line); View divider = new View(context); divider.setBackgroundColor(0xFF2C4355); body.addView(divider, new LayoutParams(-1, dp(1)));
    }
    public void addSource(String name, String url) {
        if (!UrlPolicy.isPublicHttps(url)) return;
        for (String[] s : sources) if (url.equals(s[1])) return;
        if (sources.size() == 30) sources.remove(0); sources.add(new String[]{safe(name == null || name.isEmpty() ? url : name, 200), url});
        if (current != null && "sources".equals(current.type)) render(current);
    }
    public void showSources() { display(new Card("sources", "Sources de la recherche"), true); }
    public void previous() { scroll.smoothScrollBy(0, -Math.max(dp(60), scroll.getHeight() - dp(30))); }
    public void next() { scroll.smoothScrollBy(0, Math.max(dp(60), scroll.getHeight() - dp(30))); }
    public void back() { if (!history.isEmpty()) { current = history.removeLast(); imageZoom = 1; render(current); } }
    public void hideContent(boolean hide) { hidden = hide; applyContentVisibility(); }
    private void applyContentVisibility() {
        int visibility = hidden ? INVISIBLE : VISIBLE;
        body.setVisibility(visibility);
        title.setVisibility(visibility);
        counter.setVisibility(visibility);
        if (imageViewport.getVisibility() != GONE) imageViewport.setVisibility(visibility);
    }
    public void zoom(boolean larger) { if (image != null) { imageZoom = Math.max(1, Math.min(3, imageZoom + (larger ? .25f : -.25f))); image.setScaleX(imageZoom); image.setScaleY(imageZoom); } else setFontSize(Math.max(18, Math.min(38, fontSize + (larger ? 3 : -3)))); }
    private void pageIndicator() { int h = Math.max(1, scroll.getHeight()); int pages = Math.max(1, (body.getHeight() + h - 1) / h); int page = Math.min(pages, 1 + scroll.getScrollY() / Math.max(1, h - dp(30))); counter.setText(pages > 1 ? page + " / " + pages : ""); }
    private TextView label(String value, int size, int color) { TextView text = new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setLineSpacing(dp(3), 1); return text; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safe(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max) + "…"; }
}
