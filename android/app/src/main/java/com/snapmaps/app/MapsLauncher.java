package com.snapmaps.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

final class MapsLauncher {
    private MapsLauncher() { }

    static void directions(Context context, String place, String mode) {
        Uri uri = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
                .appendQueryParameter("api", "1")
                .appendQueryParameter("destination", place.trim())
                .appendQueryParameter("travelmode", AppSettings.CAR.equals(mode)
                        ? AppSettings.CAR : AppSettings.BIKE)
                .appendQueryParameter("dir_action", "navigate").build();
        // Save before handing off, including when Maps is unavailable and the user must retry.
        HistoryStore.save(context, place);
        open(context, uri);
    }

    static void search(Context context, String place) {
        open(context, Uri.parse("https://www.google.com/maps/search/").buildUpon()
                .appendQueryParameter("api", "1").appendQueryParameter("query", place.trim()).build());
    }

    private static void open(Context context, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps");
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(intent); }
        catch (ActivityNotFoundException missingMaps) {
            intent.setPackage(null);
            context.startActivity(intent);
        }
    }
}
