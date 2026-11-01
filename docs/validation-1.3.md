# Version 1.3 validation and build scope

Target device: Redmi Note 12 5G, Android 14 / SDK 34.
Version declared in source: versionName 1.3, versionCode 4.
Artifact: build authorized by Ali; release validation is in progress.

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
- Added 10 address-selection regression cases with OCR line geometry, including both reference layouts without passenger identities or screenshot files. They are **not executed yet**.

These checks do not establish Android compilation, OCR accuracy, or device behavior.

## Required after build approval

- Compile, run AddressExtractorTest, run Android lint, assemble and verify the release signature.
- Cross-check the merged manifest, version, resources, and packaged classes against every confirmed item above.
- Permanent signing key and repository secrets are configured; the workflow now propagates all signing variables and rejects a missing or different key.
- Record the resulting artifact filename, version, SHA-256, certificate identity, and GitHub URL.

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
