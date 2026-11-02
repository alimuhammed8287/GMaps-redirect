# Version 1.3 validation and build scope

Target device: Redmi Note 12 5G, Android 14 / SDK 34.
Version declared in source: versionName 1.3, versionCode 4.
Artifact: `snap-to-maps-v1.3.apk`, signed release, published as a pre-release for device testing.

- Build: https://github.com/alimuhammed8287/GMaps-redirect/actions/runs/41402186046
- APK: https://github.com/alimuhammed8287/GMaps-redirect/releases/download/v1.3/snap-to-maps-v1.3.apk
- Built source commit: `45a0bac45668ef0909dbdf5b2437aa42402eec9f`
- APK SHA-256: `42da05350d8e19fd0308454aecc9dc75963272b740d8dde6ed7a9286f906445f`
- Certificate SHA-256: `71f5064e3cb33632a9fe7c5b72aba9cda9c099a584fb3f59137b8c98a133b283`

## Confirmed scope mapped to source

| Confirmed change | Implementation |
| --- | --- |
| Share screenshot, use bottom address, open Maps directly | ShareActivity, OcrReader, AddressExtractor, MapsLauncher |
| Keep destinations in app history | Shared HistoryStore with the existing preference schema |
| Bike / Car selection, default Bike | AppSettings and MainActivity; both scan entry points snapshot the mode |
| Separate capture activity | Explicit CaptureActivity component, optionally carrying SCAN_TO_MAPS |
| Toggle additional drawer icon | Disabled-by-default CaptureLauncher alias; MainActivity remains enabled |
| Capture visible ride address and open Maps | Opt-in CaptureService, Android 14 window screenshot API |
| Return to ride app from drawer | User-selected launchable app; wait for its visible window |

## Checks completed before any build

- Parsed Java source syntax without compiling it.
- Parsed manifest and resource XML.
- Reviewed source paths for the normal icon, share receiver, image picker, capture activity, and launcher alias.
- Reviewed ownership/cancellation for a second scan, app switching, locking, a changed source window, and service shutdown.
- Confirmed this branch does not trigger the existing main-only push build workflow.
- Added 10 address-selection regression cases with OCR line geometry, including both reference layouts without passenger identities or screenshot files. All 10 subsequently passed in the release workflow.

Source-only checks do not establish OCR accuracy or device behavior. Compilation and unit-test execution are covered below.

## Completed after build approval

- Android compilation and release assembly succeeded. AddressExtractorTest: 10 passed, 0 failed, 0 skipped. Android lint: 0 errors, 8 warnings relating to compatibility and UI conventions.
- Verified the actual APK application ID, version 1.3/code 4, min SDK 23, target SDK 35, non-debuggable release, main/share/capture components, disabled extra launcher alias, Accessibility configuration, packaged icon, ten feature classes, and Maps travel-mode/navigation values.
- Permanent signing key and repository secrets are configured; the workflow now propagates all signing variables and rejects a missing or different key.
- APK signature passed in CI and after download. Downloaded archive matched the GitHub artifact digest; downloaded APK matched the recorded SHA-256. Signing schemes v1 and v2 verified.
- The initial run stopped on two mode-control resource-ID lint errors; those were fixed. A later APK built successfully but the verifier needed to resolve optimized resource paths. The final run passed all steps; no unresolved error from either earlier attempt remains.

Older temporary-key APKs cannot update-install with this permanent key. Export history before uninstalling an older app. Uninstalling erases local history; CSV export is for reference and does not provide import/restore. Future releases using the same permanent key can update-install over 1.3.

## Device scenarios still pending

| Scenario | Expected result |
| --- | --- |
| Fresh install, screenshot-toolbar Share, English reference layout | Bottom address only, direct Maps handoff, Bike selected, one history entry |
| Gallery share and ClipData-only share | Same behavior with temporary image URI access |
| Long wrapped address | Muneswara Layout and Bennigana Halli stay intact |
| Car selected, share/capture/history open | Car requested consistently |
| Reopen normal app after sharing or capture | Updated history; normal settings screen |
| Favorites/export from an existing install | Old saved places remain readable and favorites persist |
| Additional drawer icon off/on/off | Only extra icon changes; main icon and gesture activity keep working |
| Capture access disabled | Setup explanation, no capture or Maps redirect |
| Drawer shortcut with chosen ride app | Existing ride task resumes; captures only after intended window appears |
| Gesture capture with ride screen already visible | No switch to a different configured app |
| Ride app opens home/login instead of trip | No automatic taps; fail if no bottom address is present |
| Rapid taps / share while capture runs | One owner; no duplicate or stale Maps launches |
| Home, notification shade, lock, permission revocation during scan | Cancel; no delayed Maps redirect |
| Rotation / changed app window during capture | Cancel and allow retry; no stale-window redirect |
| Android 14 split-screen and accessibility overlay | Capture intended app window; fail cleanly if address is missing |
| Missing Maps / missing browser | Fallback where available; saved destination retained if launch fails |
| Map-only or unreadable screenshot | Failure message; no unrelated destination added |
| Kannada fallback, offline first run, interrupted model download | Clear failure/retry; no partially downloaded new model promoted |
| Redmi background activity restrictions | Confirm service-to-Maps handoff on Ali's actual device |

No emulator or physical device testing has been performed for this update.
