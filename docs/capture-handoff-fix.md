# Capture handoff fix - v1.4 build scope

Changes in source:
- Allow up to 1.5 seconds for an unknown/own-app window transition after observing the ride app; capture waits for the target to return. A known different app still cancels immediately.
- Preserve the original captured window identity during OCR; changed ride windows still cancel.
- Save a successfully extracted address before the final foreground check. A cancelled final handoff explains that history contains the address.
- Launch Maps through a separate internal, non-exported foreground activity, retaining the captured Bike/Car setting. No changes to screenshot sharing or the normal launcher.
- Observe Google Maps becoming visible for up to six seconds; otherwise report an unconfirmed handoff with history recovery guidance. This verifies foreground visibility, not destination or navigation correctness.
- Include Android screenshot error codes, clear observation callbacks on service destruction/unbind, and stop an older result/observer when another scan takes ownership.

Validation performed:
- Parsed all 11 production Java files and Android manifest without compilation.
- Checked internal handoff export/filter restrictions and the existing capture task affinity.
- git diff --check passed.
- Reviewed source paths for temporary window loss, explicit app switches, lock, changed window, overlapping scans, and service teardown. These are source review, not runtime tests.

Remaining:
- Build approved by Ali. Version is 1.4 / code 5. CI will run 10 address-selection tests and 14 capture-timing tests, lint, release compilation, permanent signature and actual APK inspection. Results are recorded after completion.
- Redmi Note 12 5G / Android 14 testing remains necessary, including repeated drawer launches, gesture entry, screenshot sharing, Bike/Car, lock/app switch, and history recovery.
- A foreground handoff activity cannot guarantee bypassing a phone's background launch restrictions. If both its launch and Maps launch are blocked, the address remains in history and status reports the unconfirmed result. Accessibility visibility can also be unavailable, so the check must not be treated as proof of a system block.

Additional review changes:
- A pure CaptureGate owns the production timing/identity rules; its 14 regression cases exercise those rules directly.
- A monotonically increasing request generation prevents a superseded scan or delayed handoff from opening an older address, even if a newer scan has already finished.
- The handoff checks phone lock and generation before opening Maps; its state prevents repeated focus callbacks or rotation from launching twice.
- Screenshot sharing, manual history opening, travel mode, existing signing identity and optional launcher ownership are unchanged.

## Build result

- Version 1.4 / code 5; source commit 7b92ea5aa1c9ee7ef7d23140dcbd17f435266278.
- GitHub run 41664840887 / job 126017283809 completed successfully.
- 24 tests passed, none failed/skipped; lint 0 errors / 8 warnings.
- Actual APK inspection verified 12 production classes, version/package, the private handoff activity, existing launcher/share/service ownership, screenshot configuration, travel mode constants, and permanent certificate.
- Downloaded archive SHA-256 matched the GitHub artifact digest. APK SHA-256: c90cfc6af191b67aa0271015a4af8fa3e2d57e3b809d7a5b81568c02e0db218f. APK size 74,575,862 bytes. Downloaded APK signature rechecked locally (v1/v2 signatures valid).
- Public signing certificate unchanged from v1.3: 71f5064e3cb33632a9fe7c5b72aba9cda9c099a584fb3f59137b8c98a133b283.
- No connected phone or emulator: drawer/gesture/share integration, OEM launch policy, real OCR and route/navigation correctness remain unverified on-device.
- GitHub pre-release: https://github.com/alimuhammed8287/GMaps-redirect/releases/tag/v1.4

Agreed scope cross-check: transition handling is in CaptureGate/CaptureService; address preservation in HistoryStore calls before handoff; foreground launch in non-exported MapsHandoffActivity; superseded-request protection in ScanCoordinator generation checks; observed Maps status/error codes in CaptureService. The existing ShareActivity, MapsLauncher mode URL, history UI and optional launcher configuration were not changed.
