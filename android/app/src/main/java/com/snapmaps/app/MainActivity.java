package com.snapmaps.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int PICK_IMAGE = 40, EXPORT_HISTORY = 41;
    private EditText destination;
    private TextView status, captureStatus;
    private Button directions, rideApp;
    private LinearLayout historyView;
    private Switch drawerIcon;
    private ScanCoordinator.Request request;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChanged = (prefs, key) -> {
        if ("history".equals(key)) renderHistory();
        if ("last_scan_status".equals(key)) status.setText(prefs.getString(key, ""));
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        if (state != null) destination.setText(state.getString("destination", ""));
    }

    private void buildUi() {
        LinearLayout page = column();
        page.setPadding(dp(18), dp(18), dp(18), dp(24));
        page.setBackgroundColor(0xfff5f7f4);
        page.addView(label("Snap to Maps", 25));
        page.addView(label("Share a screenshot to read its bottom address and open Google Maps.", 14), spaced());

        page.addView(label("Travel mode", 18), spaced());
        RadioGroup mode = new RadioGroup(this);
        mode.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton bike = new RadioButton(this);
        bike.setText(R.string.mode_bike); bike.setId(R.id.mode_bike); bike.setMinHeight(dp(48));
        RadioButton car = new RadioButton(this);
        car.setText(R.string.mode_car); car.setId(R.id.mode_car); car.setMinHeight(dp(48));
        mode.addView(bike, new LinearLayout.LayoutParams(0, -2, 1));
        mode.addView(car, new LinearLayout.LayoutParams(0, -2, 1));
        mode.check(AppSettings.CAR.equals(AppSettings.mode(this)) ? R.id.mode_car : R.id.mode_bike);
        page.addView(mode);
        mode.setOnCheckedChangeListener((group, id) -> {
            AppSettings.setMode(this, id == R.id.mode_car ? AppSettings.CAR : AppSettings.BIKE);
            updateDirectionsLabel();
        });
        page.addView(label("Used for screenshot shares, capture shortcuts, and reopening history.", 13));

        page.addView(label("Capture shortcut", 19), spaced());
        drawerIcon = buttonSwitch("Show additional Scan to Maps icon");
        drawerIcon.setChecked(AppSettings.isShortcutShown(this));
        drawerIcon.setEnabled(Build.VERSION.SDK_INT >= 30);
        drawerIcon.setOnCheckedChangeListener((button, enabled) -> {
            try {
                AppSettings.showShortcut(this, enabled);
                setStatus(enabled ? "Scan to Maps icon enabled. Your launcher may take a moment to refresh."
                        : "Extra icon hidden. The main app and gesture activity remain available.");
            } catch (RuntimeException failure) {
                drawerIcon.setChecked(AppSettings.isShortcutShown(this));
                setStatus("Could not change the launcher icon. Please try again.");
            }
        });
        page.addView(drawerIcon);
        rideApp = button("Choose ride app");
        rideApp.setOnClickListener(view -> chooseRideApp());
        page.addView(rideApp, spaced());
        page.addView(label("The drawer icon returns to this app before scanning. Keep its bottom address card open. "
                + "A gesture can launch the Scan to Maps activity directly over the visible ride app.", 13));
        Button access = button("Set up screen capture");
        access.setEnabled(Build.VERSION.SDK_INT >= 30);
        access.setOnClickListener(view -> explainAccessibility());
        page.addView(access, spaced());
        captureStatus = label("", 13);
        page.addView(captureStatus);
        page.addView(label("Capture runs only when you trigger it. Screenshot sharing does not need Accessibility access.", 13), spaced());

        Button choose = button("Choose screenshot");
        choose.setOnClickListener(view -> {
            Intent image = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(image, PICK_IMAGE); }
            catch (RuntimeException unavailable) { setStatus("No image picker is available."); }
        });
        page.addView(choose, spaced());
        destination = new EditText(this);
        destination.setHint("Detected destination or place");
        destination.setMinLines(2);
        destination.setGravity(Gravity.TOP);
        destination.setTextSize(16);
        page.addView(destination, spaced());
        directions = button("");
        directions.setOnClickListener(view -> openMaps(destination.getText().toString()));
        page.addView(directions);
        updateDirectionsLabel();
        Button search = button("Find place");
        search.setOnClickListener(view -> {
            String place = destination.getText().toString().trim();
            if (place.isEmpty()) { setStatus("Add a place first."); return; }
            try { MapsLauncher.search(this, place); }
            catch (RuntimeException unavailable) { setStatus("Google Maps could not be opened."); }
        });
        page.addView(search);
        status = label("", 14);
        page.addView(status, spaced());

        page.addView(label("Recent destinations", 20), spaced());
        page.addView(label("Last 50 destinations. Screenshots are not saved in history.", 13));
        historyView = column();
        page.addView(historyView);
        Button export = button("Export history");
        export.setOnClickListener(view -> {
            Intent document = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "snap-maps-history.csv");
            try { startActivityForResult(document, EXPORT_HISTORY); }
            catch (RuntimeException unavailable) { setStatus("No document picker is available."); }
        });
        page.addView(export, spaced());
        Button clear = button("Clear history");
        clear.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("Clear history?")
                .setMessage("This removes saved destinations and favorites.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> HistoryStore.clear(this)).show());
        page.addView(clear);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        scroll.addView(page);
        setContentView(scroll);
        refreshSettings();
        renderHistory();
    }

    private void updateDirectionsLabel() {
        if (directions != null) directions.setText(AppSettings.CAR.equals(AppSettings.mode(this))
                ? "Open car navigation" : "Open bike navigation");
    }

    private void refreshSettings() {
        String selected = AppSettings.prefs(this).getString("capture_app_label", "");
        rideApp.setText(selected.isEmpty() ? getString(R.string.choose_ride_app) : getString(R.string.ride_app_selected, selected));
        if (Build.VERSION.SDK_INT < 30) {
            captureStatus.setText("Screen capture needs Android 11 or newer. You can still share screenshots.");
        } else {
            captureStatus.setText(CaptureService.connected() == null
                    ? "Capture access is off. Set it up once to use the shortcut."
                    : "Capture access is ready.");
        }
        status.setText(AppSettings.prefs(this).getString("last_scan_status", ""));
    }

    private void chooseRideApp() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(query, 0);
        List<ResolveInfo> apps = new ArrayList<>();
        Set<String> packages = new HashSet<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            String packageName = info.activityInfo.packageName;
            if (!packageName.equals(getPackageName()) && packages.add(packageName)) apps.add(info);
        }
        Collections.sort(apps, (a, b) -> a.loadLabel(getPackageManager()).toString()
                .compareToIgnoreCase(b.loadLabel(getPackageManager()).toString()));
        if (apps.isEmpty()) { setStatus("No launchable apps were found."); return; }
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).loadLabel(getPackageManager()).toString();
        new AlertDialog.Builder(this).setTitle("Choose your ride app")
                .setItems(labels, (dialog, which) -> {
                    AppSettings.setCaptureApp(this, apps.get(which).activityInfo.packageName, labels[which]);
                    refreshSettings();
                }).setNegativeButton("Cancel", null).show();
    }

    private void explainAccessibility() {
        new AlertDialog.Builder(this).setTitle("Enable screen capture")
                .setMessage("Scan to Maps uses Accessibility access to identify the visible app and take a screenshot "
                        + "only when you trigger its capture activity. The bottom address is read on your phone; "
                        + "the screenshot is discarded. The service does not tap buttons or scan in the background.\n\n"
                        + "On the next screen, enable Snap to Maps capture. You can turn it off there at any time. "
                        + "If Android blocks this for an APK, open App info → menu → Allow restricted settings, then return.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings", (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (RuntimeException unavailable) { setStatus("Open Accessibility settings on your phone to enable capture."); }
                }).show();
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (code == PICK_IMAGE) scanPickedImage(data.getData());
        if (code == EXPORT_HISTORY) exportCsv(data.getData());
    }

    private void scanPickedImage(Uri image) {
        ScanCoordinator.Request next = ScanCoordinator.begin();
        if (next == null) { setStatus("Another scan is already running. Please wait."); return; }
        request = next;
        setStatus("Reading bottom address…");
        request.read(this, () -> OcrReader.decode(getApplicationContext(), image), new ScanCoordinator.Listener() {
            @Override public void onAddress(String address) {
                request = null;
                if (isDestroyed() || isFinishing()) return;
                destination.setText(address);
                HistoryStore.save(MainActivity.this, address);
                setStatus("Address ready. Tap navigation to open Maps.");
            }
            @Override public void onError(String message) { request = null; setStatus(message); }
        });
    }

    private void openMaps(String place) {
        if (place.trim().isEmpty()) { setStatus("Add a destination first."); return; }
        try { MapsLauncher.directions(this, place, AppSettings.mode(this)); }
        catch (RuntimeException unavailable) { setStatus("Could not open Maps. The address is saved in history."); }
    }

    private void renderHistory() {
        if (historyView == null) return;
        historyView.removeAllViews();
        JSONArray items = HistoryStore.read(this);
        if (items.length() == 0) { historyView.addView(label("No saved destinations yet.", 14), spaced()); return; }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String place = item.optString("place");
            boolean favorite = item.optBoolean("favorite");
            LinearLayout row = column();
            row.setPadding(0, dp(12), 0, dp(8));
            row.addView(label(place, 16));
            row.addView(label(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(new Date(item.optLong("at"))), 12));
            Button open = button("Open in Maps");
            open.setOnClickListener(view -> openMaps(place));
            row.addView(open);
            LinearLayout actions = new LinearLayout(this);
            Button star = button(favorite ? "★ Favorite" : "☆ Favorite");
            star.setOnClickListener(view -> HistoryStore.favorite(this, place, !favorite));
            Button remove = button("Remove");
            remove.setOnClickListener(view -> HistoryStore.remove(this, place));
            actions.addView(star, new LinearLayout.LayoutParams(0, -2, 1));
            actions.addView(remove, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(actions);
            historyView.addView(row);
        }
    }

    private void exportCsv(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("No output file");
            output.write("Destination,Favorite,Saved at\n".getBytes(StandardCharsets.UTF_8));
            JSONArray items = HistoryStore.read(this);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String place = item.optString("place");
                if (place.trim().matches("^[=+@-].*")) place = "'" + place;
                String row = "\"" + place.replace("\"", "\"\"") + "\"," + item.optBoolean("favorite")
                        + ",\"" + new Date(item.optLong("at")) + "\"\n";
                output.write(row.getBytes(StandardCharsets.UTF_8));
            }
            setStatus("History exported.");
        } catch (Exception failure) { setStatus("Could not export history."); }
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView label(String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size);
        view.setTextColor(0xff273c31); return view;
    }
    private Button button(String text) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false); view.setMinHeight(dp(48)); return view;
    }
    private Switch buttonSwitch(String text) {
        Switch view = new Switch(this); view.setText(text); view.setMinHeight(dp(48)); view.setTextSize(15); return view;
    }
    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(10), 0, dp(8)); return params;
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private void setStatus(String message) { status.setText(message); AppSettings.recordStatus(this, message); }

    @Override protected void onStart() {
        super.onStart();
        AppSettings.prefs(this).registerOnSharedPreferenceChangeListener(preferencesChanged);
        renderHistory();
    }
    @Override protected void onResume() { super.onResume(); refreshSettings(); }
    @Override protected void onStop() {
        AppSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(preferencesChanged);
        if (request != null) {
            request.cancel();
            request = null;
            AppSettings.recordStatus(this, "Scan cancelled. Choose the screenshot again when ready.");
        }
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("destination", destination.getText().toString());
        super.onSaveInstanceState(state);
    }
}
