# Snap to Maps

A screenshot-to-directions tool with a mobile web version and a native Android app. Share a screenshot to the Android app to read its visible text and open Google Maps two-wheeler directions.

## Features

- Choose **Snap to Maps** from Android's Share menu; a transparent handoff reads the screenshot and opens Maps without stopping on the app's history screen. You can also select a screenshot from the app.
- Read English and Kannada text with Tesseract OCR, then open the detected text in Google Maps two-wheeler directions.
- Save the last 50 destinations on-device, mark favorites, reopen entries in Maps, remove entries, clear history, or export a CSV.
- Screenshots are processed on-device and are not retained in history.
- The PWA prototype also supports a local, last-30 destination history.

## Native Android app

The native app source is under `android/`. The screenshot share receiver shows only a brief reading notice, then launches Maps directly. On first OCR use it downloads the English and Kannada Tesseract models from the official `tessdata_fast` repository into app-private storage. Subsequent scans reuse the local models.

Pushes to `main` that change the Android project start the **Build signed Android APK** GitHub Actions workflow. Download the `snap-maps-signed-apk` artifact from the workflow run. The default workflow fallback creates a one-run signing key; configure the repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` to keep the same signing identity for future updates. Without a persistent key, a later APK cannot update-install over the earlier one.

## Run and install the PWA

The Android share target and service worker require this app to be served from an HTTPS origin and installed in a supporting browser. Opening `index.html` directly from disk is only a preview; it will not appear in the Android share sheet.

After hosting the files at an HTTPS URL, open that URL in Chrome on Android, install it from the browser menu, and then share an image to **Snap Maps**. The first OCR run requires an internet connection to load Tesseract.js from its CDN.

Google Maps two-wheeler routes are only available in supported regions. OCR can misread or combine nearby map labels, so confirm the destination in Maps before starting a ride.

## Files

- `index.html` — app UI, OCR and local history
- `manifest.webmanifest` — install and share-target configuration
- `sw.js` — receives the shared image and passes it to the app
- `icon.svg` — app icon

