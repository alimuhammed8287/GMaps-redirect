package com.snapmaps.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.googlecode.tesseract.android.TessBaseAPI;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String HISTORY_KEY = "history";
    private static final int EXPORT_REQUEST = 41;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private EditText destination;
    private TextView status;
    private LinearLayout historyView;
    private Uri pendingImage;
    private boolean autoOpenAfterScan;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("snap_maps", MODE_PRIVATE);
        buildUi();
        acceptIntent(getIntent());
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptIntent(intent);
    }

    private void buildUi() {
        int pad = dp(18);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(pad, pad, pad, pad);
        page.setBackgroundColor(0xfff5f7f4);

        TextView title = new TextView(this);
        title.setText("Snap to Maps"); title.setTextSize(25); title.setTextColor(0xff18211d);
        page.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Share a screenshot here to open its destination in bike mode.");
        subtitle.setTextSize(14); subtitle.setTextColor(0xff64716a);
        page.addView(subtitle, margin(0, dp(4), 0, dp(14)));

        Button choose = button("Choose screenshot");
        choose.setOnClickListener(v -> { Intent i = new Intent(Intent.ACTION_GET_CONTENT); i.setType("image/*"); startActivityForResult(i, 40); });
        page.addView(choose, margin(0, 0, 0, dp(10)));

        destination = new EditText(this);
        destination.setHint("Detected destination or place");
        destination.setMinLines(2); destination.setGravity(Gravity.TOP); destination.setTextSize(16);
        page.addView(destination, margin(0, 0, 0, dp(8)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button open = button("🏍  Bike directions");
        open.setOnClickListener(v -> openMaps(destination.getText().toString()));
        Button find = button("Find place");
        find.setOnClickListener(v -> openSearch(destination.getText().toString()));
        actions.addView(open, new LinearLayout.LayoutParams(0, dp(50), 1));
        actions.addView(find, new LinearLayout.LayoutParams(0, dp(50), 1));
        page.addView(actions, margin(0, 0, 0, dp(8)));

        status = new TextView(this); status.setTextSize(14); status.setTextColor(0xff59665d);
        page.addView(status, margin(0, 0, 0, dp(12)));

        TextView section = new TextView(this); section.setText("Recent destinations"); section.setTextSize(19); section.setTextColor(0xff18211d);
        page.addView(section, margin(0, dp(8), 0, dp(4)));
        TextView local = new TextView(this); local.setText("Saved only on this phone. Favorite entries with ☆."); local.setTextSize(13); local.setTextColor(0xff64716a);
        page.addView(local, margin(0, 0, 0, dp(8)));
        historyView = new LinearLayout(this); historyView.setOrientation(LinearLayout.VERTICAL);
        page.addView(historyView);

        LinearLayout footer = new LinearLayout(this); footer.setOrientation(LinearLayout.HORIZONTAL);
        Button export = button("Export history"); export.setOnClickListener(v -> startExport());
        Button clear = button("Clear"); clear.setOnClickListener(v -> { prefs.edit().remove(HISTORY_KEY).apply(); renderHistory(); });
        footer.addView(export, new LinearLayout.LayoutParams(0, dp(48), 1));
        footer.addView(clear, new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(footer, margin(0, dp(10), 0, dp(10)));

        ScrollView scroll = new ScrollView(this); scroll.addView(page); setContentView(scroll);
        renderHistory();
    }

    private void acceptIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) { setStatus("The shared item did not include an image."); return; }
        pendingImage = uri;
        autoOpenAfterScan = true;
        scanSharedImage();
    }

    private void scanSharedImage() {
        final Uri uri = pendingImage;
        if (uri == null) return;
        setStatus("Reading screenshot in English and Kannada… first use downloads OCR language data.");
        worker.execute(() -> {
            try {
                File data = new File(getFilesDir(), "tessdata");
                if (!data.exists() && !data.mkdirs()) throw new IllegalStateException("Cannot create OCR data folder");
                downloadModel(data, "eng");
                downloadModel(data, "kan");
                Bitmap bitmap;
                try (InputStream in = getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(in); }
                if (bitmap == null) throw new IllegalArgumentException("Could not decode this image");
                TessBaseAPI api = new TessBaseAPI();
                if (!api.init(getFilesDir().getAbsolutePath(), "eng+kan")) throw new IllegalStateException("OCR could not initialize");
                api.setImage(bitmap);
                String result = api.getUTF8Text();
                api.end(); bitmap.recycle();
                String candidate = chooseCandidate(result);
                runOnUiThread(() -> {
                    if (candidate.isEmpty()) { setStatus("No clear address was found. Add a nearby place or landmark below."); return; }
                    destination.setText(candidate);
                    savePlace(candidate);
                    setStatus("Destination found. Opening Google Maps…");
                    if (autoOpenAfterScan) { autoOpenAfterScan = false; openMaps(candidate); }
                });
            } catch (Exception ex) {
                runOnUiThread(() -> setStatus("Could not read the screenshot: " + ex.getMessage()));
            }
        });
    }

    private void downloadModel(File folder, String language) throws Exception {
        File model = new File(folder, language + ".traineddata");
        if (model.exists() && model.length() > 100_000) return;
        URL url = new URL("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/" + language + ".traineddata");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(20000); connection.setReadTimeout(60000);
        try (InputStream in = new BufferedInputStream(connection.getInputStream()); OutputStream out = new FileOutputStream(model)) {
            byte[] buffer = new byte[16384]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        } finally { connection.disconnect(); }
    }

    private String chooseCandidate(String raw) {
        String[] lines = raw.split("\\R");
        ArrayList<String> candidates = new ArrayList<>();
        for (String line : lines) {
            String clean = line.trim().replaceAll("\\s+", " ");
            if (clean.length() < 5 || clean.matches(".*[0-9]{1,2}:[0-9]{2}.*")) continue;
            String lower = clean.toLowerCase(Locale.ROOT);
            if (lower.matches(".*(complete ride|go to drop|drop location|google|uber|km/h|battery|share).*")) continue;
            if (clean.matches(".*[\\p{L}]{3,}.*")) candidates.add(clean);
        }
        if (candidates.isEmpty()) return "";
        int start = Math.max(0, candidates.size() - 5);
        StringBuilder answer = new StringBuilder();
        for (int i = start; i < candidates.size(); i++) {
            if (answer.length() > 0) answer.append(", ");
            answer.append(candidates.get(i));
        }
        return answer.length() > 260 ? answer.substring(0, 260) : answer.toString();
    }

    private JSONArray readHistory() {
        try { return new JSONArray(prefs.getString(HISTORY_KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void savePlace(String place) {
        String normalized = place.trim(); if (normalized.isEmpty()) return;
        JSONArray old = readHistory(); JSONArray next = new JSONArray();
        boolean favorite = false;
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i); if (item == null) continue;
            if (item.optString("place").equalsIgnoreCase(normalized)) favorite = item.optBoolean("favorite");
            else next.put(item);
        }
        JSONObject current = new JSONObject();
        try { current.put("place", normalized); current.put("at", System.currentTimeMillis()); current.put("favorite", favorite); } catch (Exception ignored) { }
        JSONArray saved = new JSONArray(); saved.put(current);
        for (int i = 0; i < Math.min(next.length(), 49); i++) saved.put(next.optJSONObject(i));
        prefs.edit().putString(HISTORY_KEY, saved.toString()).apply();
        renderHistory();
    }

    private void renderHistory() {
        if (historyView == null) return;
        historyView.removeAllViews(); JSONArray items = readHistory();
        if (items.length() == 0) { TextView empty = new TextView(this); empty.setText("No saved destinations yet."); empty.setTextColor(0xff64716a); historyView.addView(empty); return; }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i); if (item == null) continue;
            String place = item.optString("place"); boolean favorite = item.optBoolean("favorite"); long at = item.optLong("at");
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(0, dp(8), 0, dp(8));
            TextView name = new TextView(this); name.setText(place); name.setTextSize(15); name.setTextColor(0xff18211d); row.addView(name);
            TextView date = new TextView(this); date.setText(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(at))); date.setTextSize(12); date.setTextColor(0xff748078); row.addView(date);
            LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
            Button open = button("Open in Maps"); open.setOnClickListener(v -> openMaps(place));
            Button star = button(favorite ? "★ Favorite" : "☆ Favorite"); star.setOnClickListener(v -> setFavorite(place, !favorite));
            Button remove = button("Remove"); remove.setOnClickListener(v -> removePlace(place));
            buttons.addView(open, new LinearLayout.LayoutParams(0, dp(42), 1)); buttons.addView(star, new LinearLayout.LayoutParams(0, dp(42), 1)); buttons.addView(remove, new LinearLayout.LayoutParams(0, dp(42), 1));
            row.addView(buttons); historyView.addView(row);
            View divider = new View(this); divider.setBackgroundColor(0xffe5ebe5); historyView.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private void setFavorite(String place, boolean value) {
        JSONArray old = readHistory();
        for (int i = 0; i < old.length(); i++) { JSONObject o = old.optJSONObject(i); if (o != null && o.optString("place").equals(place)) try { o.put("favorite", value); } catch (Exception ignored) { } }
        prefs.edit().putString(HISTORY_KEY, old.toString()).apply(); renderHistory();
    }

    private void removePlace(String place) {
        JSONArray old = readHistory(); JSONArray next = new JSONArray();
        for (int i = 0; i < old.length(); i++) { JSONObject o = old.optJSONObject(i); if (o != null && !o.optString("place").equals(place)) next.put(o); }
        prefs.edit().putString(HISTORY_KEY, next.toString()).apply(); renderHistory();
    }

    private void startExport() { Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.setType("text/csv"); i.putExtra(Intent.EXTRA_TITLE, "snap-maps-history.csv"); startActivityForResult(i, EXPORT_REQUEST); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 40 && result == RESULT_OK && data != null) { pendingImage = data.getData(); autoOpenAfterScan = false; scanSharedImage(); }
        if (request == EXPORT_REQUEST && result == RESULT_OK && data != null && data.getData() != null) exportCsv(data.getData());
    }

    private void exportCsv(Uri uri) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            out.write("Destination,Favorite,Saved at\n".getBytes(StandardCharsets.UTF_8)); JSONArray items = readHistory();
            for (int i = 0; i < items.length(); i++) { JSONObject o = items.optJSONObject(i); if (o == null) continue; String p = o.optString("place").replace("\"", "\"\""); String row = "\"" + p + "\"," + o.optBoolean("favorite") + ",\"" + new Date(o.optLong("at")) + "\"\n"; out.write(row.getBytes(StandardCharsets.UTF_8)); }
            setStatus("History exported.");
        } catch (Exception ex) { setStatus("Could not export history: " + ex.getMessage()); }
    }

    private void openMaps(String place) {
        if (place == null || place.trim().isEmpty()) { setStatus("Add a destination first."); return; }
        savePlace(place);
        try { String query = URLEncoder.encode(place.trim(), "UTF-8"); Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + query + "&travelmode=two-wheeler")); startActivity(i); }
        catch (Exception ex) { setStatus("Google Maps could not be opened."); }
    }

    private void openSearch(String place) {
        if (place == null || place.trim().isEmpty()) { setStatus("Add a place first."); return; }
        try { String query = URLEncoder.encode(place.trim(), "UTF-8"); startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + query))); }
        catch (Exception ex) { setStatus("Google Maps could not be opened."); }
    }

    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setAllCaps(false); return b; }
    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(l, t, r, b); return p; }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }
    private void setStatus(String message) { if (status != null) status.setText(message); }
    @Override protected void onDestroy() { super.onDestroy(); worker.shutdown(); }
}

