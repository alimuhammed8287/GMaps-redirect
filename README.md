# Snap to Maps

A screenshot-to-directions tool with a mobile web version and a native Android app. Share a screenshot to the Android app to read its visible text and open Google Maps two-wheeler directions.

## Features

- Receive screenshots from Android's Share menu, or select one in the app.
- Read English and Kannada text with Tesseract OCR, then open the detected text in Google Maps two-wheeler directions.
- Save the last 50 destinations on-device, mark favorites, reopen entries in Maps, remove entries, clear history, or export a CSV.
- Screenshots are processed on-device and are not retained in history.
- The PWA prototype also supports a local, last-30 destination history.

## Native Android app

The native app source is under `android/`. On first OCR use it downloads English and Kannada Tesseract models from the official `tessdata_fast` repository into app-private storage. Later scans reuse the local models.

Pushes to `main` that change the Android project start the **Build signed Android APK** GitHub Actions workflow. Download the `snap-maps-signed-apk` artifact from the workflow run. The default workflow fallback creates a one-run signing key; configure repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` to keep the same signing identity for future updates. Without a persistent key, a later APK cannot update-install over the earlier one.

## PWA

The PWA is in the repository root. The Android share target and service worker require the files to be hosted over HTTPS and the PWA installed in a supporting browser. Directly opening `index.html` from disk is only a preview.

Google Maps two-wheeler routes are available only in supported regions. OCR can misread or combine nearby map labels, so confirm the destination in Maps before starting a ride.
