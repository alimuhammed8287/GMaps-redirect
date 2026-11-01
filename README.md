# Snap to Maps

Android screenshot-to-navigation app, with an older web prototype at the repository root.

## Android 1.3 source update

This branch contains the feature implementation. No 1.3 APK has been built or device-tested yet.

- Share **one screenshot** from the screenshot toolbar or gallery to **Snap to Maps**. A translucent receiver reads the bottom address, saves it in history, and opens Google Maps without showing the settings/history screen.
- **Bike / Car** is remembered. Bike means motorized two-wheeler, and is the default. Screenshot shares, capture shortcuts, and history use this setting. A running scan keeps the mode selected when it began.
- OCR reads the lower half of the image, uses text positions to select the connected address block above the ride button, removes short passenger headings, and preserves wrapped address words. It does not infer coordinates from the map pin.
- History retains the existing last-50 destinations, favorites, removal, and CSV export. Both scan entry points use the same history store. Screenshots are discarded, not attached to history.
- **Scan to Maps** is a separate capture activity. **Show additional Scan to Maps icon** enables or hides only its launcher alias; the normal app icon continues to open settings/history.
- Choose a **ride app** in settings. The drawer icon brings that app's existing task forward, waits for its window to be visible, then captures and scans. It does not tap through the ride app or restore a dismissed trip.
- A gesture/automation app can launch the capture activity while the ride app is visible. That route captures the visible app instead of switching to the configured drawer target.

## Set up the capture shortcut

1. Open Snap to Maps and select Bike or Car.
2. Choose the ride app that the optional drawer icon should open.
3. Tap **Set up screen capture**, read the disclosure, and enable **Snap to Maps capture** in Android Accessibility settings.
4. Optionally enable the additional drawer icon.
5. Leave the ride address card visible before triggering capture.

On some sideloaded installations, Android requires **App info → menu → Allow restricted settings** before enabling the Accessibility service.

Sharing a screenshot needs no Accessibility access. Screen capture requires Android 11+. On Android 14+ it captures the intended app window, avoiding accessibility overlays. On Android 11–13 it uses display capture and requires the source app to be full screen. Protected windows cannot be captured.

Capture activity for gesture apps:

- Package: `com.snapmaps.app`
- Activity: `com.snapmaps.app.CaptureActivity`
- Optional action on an explicit component intent: `com.snapmaps.app.action.SCAN_TO_MAPS`
- Optional drawer alias: `com.snapmaps.app.CaptureLauncher`

Gesture apps should use the explicit activity component above. The activity has no launcher intent filter, so it remains callable while its drawer alias is hidden, including from tools that send explicit MAIN intents.

The Accessibility service is idle unless the user invokes capture. It uses window information to check which app is visible. It does not click, scroll, or continuously capture the screen. Leaving the source app, changing its window, locking the phone, or disabling the service cancels the pending capture. Only one scan can run at a time across sharing, the in-app image picker, and capture.

## Recognition and Maps behavior

Bundled ML Kit handles English on-device. Tesseract remains the fallback for English/Kannada, downloading its language models on first use. Model downloads need internet access; the screenshot itself is not uploaded.

Layouts similar to the supplied ride screenshots are the target. Unreadable, unrelated, or incomplete bottom cards can fail recognition; the app reports the failure rather than opening arbitrary nearby map labels. The in-app image picker leaves the detected text editable before opening Maps.

Google Maps receives `travelmode=two-wheeler` or `travelmode=driving` and `dir_action=navigate`. Maps may show a route preview if it cannot determine the current location or if navigation is unavailable. Two-wheeler coverage depends on the region. If Google Maps is absent, the app falls back to an available browser.

References: [Maps URLs](https://developers.google.com/maps/documentation/urls/get-started), [Android screenshot API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService), [launcher aliases](https://developer.android.com/guide/topics/manifest/activity-alias-element).

## Validation and build

See [the 1.3 validation checklist](docs/validation-1.3.md). Source syntax and manifest checks are separate from compilation, unit execution, and device tests.

The Android project is in `android/`. After explicit build approval, run:

```
gradle --no-daemon -p android :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

The existing GitHub workflow builds on Android-related pushes to **main**, or manual dispatch. The feature branch does not automatically build. Download published artifacts from the GitHub Actions run.

The workflow requires the four permanent signing secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. It no longer generates a temporary key. The public certificate SHA-256 is pinned in `android/release-signing-certificate.sha256`, and the finished APK is checked against that identity. Private keys and passwords are never committed.

Older APKs were signed with temporary keys. Installing 1.3 over one of those APKs may be rejected by Android. Export old history before uninstalling: uninstalling removes app data, and CSV export is a backup for reference, not an import/restore feature. Future releases signed with the permanent key can update-install over 1.3 and retain history.

## Web prototype

The root `index.html`, `sw.js`, and web manifest remain the earlier PWA prototype with a separate last-30 history. The Android capture activity, mode selector, and new address reader are native features. A local file preview is not an installable Android APK or share target.
