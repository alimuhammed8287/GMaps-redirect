"""Inspect the actual release APK, not just the source tree. Uses installed Android SDK tools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def run(*command):
    return subprocess.check_output([str(item) for item in command], text=True, encoding="utf-8")


def dex_classes(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Unexpected DEX format")
    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]
    def string(index):
        offset = u32(u32(60) + index * 4)
        while data[offset] & 128:
            offset += 1
        offset += 1
        return data[offset:data.index(0, offset)].decode("utf-8", errors="replace")
    result = set()
    for index in range(u32(96)):
        type_index = u32(u32(100) + index * 32)
        result.add(string(u32(u32(68) + type_index * 4)))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    sdk_path = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk_path:
        raise RuntimeError("ANDROID_HOME must point to the Android SDK")
    sdk = Path(sdk_path)
    suffix = ".bat" if os.name == "nt" else ""
    analyzer = sdk / "cmdline-tools/latest/bin" / ("apkanalyzer" + suffix)
    signer = sdk / "build-tools/35.0.0" / ("apksigner" + suffix)
    signature = run(signer, "verify", "--verbose", "--print-certs", args.apk)
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
    assert match, "APK signer certificate not found"
    certificate = match.group(1).lower()
    assert certificate == (root / "android/release-signing-certificate.sha256").read_text().strip().lower(), "Signing identity mismatch"

    manifest = ET.fromstring(run(analyzer, "manifest", "print", args.apk))
    android = "{http://schemas.android.com/apk/res/android}"
    package = manifest.get("package")
    assert package == "com.snapmaps.app", "Wrong application ID"
    assert manifest.get(android + "versionName") == "1.4", "Wrong version name"
    assert manifest.get(android + "versionCode") == "5", "Wrong version code"
    sdk_config = manifest.find("uses-sdk")
    assert sdk_config.get(android + "minSdkVersion") == "23"
    assert sdk_config.get(android + "targetSdkVersion") == "35"
    application = manifest.find("application")
    assert application.get(android + "debuggable", "false") == "false", "Release is debuggable"
    def qualified(name):
        return package + name if name.startswith(".") else name
    components = {qualified(item.get(android + "name")): item
                  for item in application if item.get(android + "name")}
    normal = components[package + ".MainActivity"]
    share = components[package + ".ShareActivity"]
    capture = components[package + ".CaptureActivity"]
    handoff = components[package + ".MapsHandoffActivity"]
    assert handoff.get(android + "exported") == "false"
    assert not handoff.findall("intent-filter")
    assert handoff.get(android + "excludeFromRecents") == "true"
    assert handoff.get(android + "taskAffinity") == package + ".handoff"
    alias = components[package + ".CaptureLauncher"]
    service = components[package + ".CaptureService"]
    assert normal.get(android + "enabled", "true") == "true"
    assert "android.intent.category.LAUNCHER" in [x.get(android + "name") for x in normal.iter("category")]
    assert {x.get(android + "name") for x in share.iter("action")} == {
        "android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE"}
    assert capture.get(android + "exported") == "true" and not capture.findall("intent-filter")
    assert alias.get(android + "enabled") == "false"
    assert qualified(alias.get(android + "targetActivity")) == package + ".CaptureActivity"
    assert service.get(android + "permission") == "android.permission.BIND_ACCESSIBILITY_SERVICE"
    def resource_file(kind, name):
        # Release resource optimization can shorten file paths. Resolve through resources.arsc.
        configs = run(analyzer, "resources", "configs", "--type", kind, "--package", package, args.apk).splitlines()
        for variant in configs:
            names = run(analyzer, "resources", "names", "--config", variant.strip(),
                        "--type", kind, "--package", package, args.apk)
            if not re.search(r"\b" + re.escape(name) + r"\b", names):
                continue
            value = run(analyzer, "resources", "value", "--config", variant.strip(),
                        "--name", name, "--type", kind, "--package", package, args.apk)
            path = re.search(r"res/[^\s\"']+", value)
            if path:
                return path.group(0)
        raise AssertionError("Packaged resource missing: " + kind + "/" + name)
    capture_config_path = resource_file("xml", "capture_service")
    capture_icon_path = resource_file("drawable", "ic_capture")
    config = ET.fromstring(run(analyzer, "resources", "xml", "--file", capture_config_path, args.apk))
    assert config.get(android + "canTakeScreenshot") == "true"
    assert config.get(android + "canRetrieveWindowContent") == "true"
    assert config.get(android + "isAccessibilityTool") == "false"

    expected_classes = {"MainActivity", "ShareActivity", "CaptureActivity", "CaptureService",
                        "CaptureGate", "MapsHandoffActivity", "AppSettings", "HistoryStore", "MapsLauncher", "OcrReader", "AddressExtractor", "ScanCoordinator"}
    classes = set()
    dex_data = b""
    with zipfile.ZipFile(args.apk) as apk:
        for name in apk.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                data = apk.read(name)
                classes.update(dex_classes(data))
                dex_data += data
        assert capture_icon_path in apk.namelist(), "Extra icon is absent"
    for name in expected_classes:
        assert "Lcom/snapmaps/app/" + name + ";" in classes, "Missing class: " + name
    for text in [b"two-wheeler", b"driving", b"dir_action", b"navigate", b"travel_mode",
                 b"Show additional Scan to Maps icon", b"history", b"generation",
                 b"Address saved and Google Maps opened.", b"Waiting for the ride address screen..."]:
        assert text in dex_data, "Missing packaged setting/behavior: " + text.decode()

    suites = {"AddressExtractorTest": 10, "CaptureGateTest": 14}
    passed = 0
    for suite, count in suites.items():
        tests = ET.parse(root / ("android/app/build/test-results/testDebugUnitTest/TEST-com.snapmaps.app." + suite + ".xml")).getroot()
        assert int(tests.get("tests")) == count, "Missing tests in " + suite
        assert all(int(tests.get(key)) == 0 for key in ("failures", "errors", "skipped")), "Failed tests in " + suite
        passed += count
    lint = ET.parse(root / "android/app/build/reports/lint-results-debug.xml").getroot()
    errors = [issue for issue in lint.findall("issue") if issue.get("severity") in {"Error", "Fatal"}]
    assert not errors, "Android lint contains errors"
    report = {
        "file": args.apk.name,
        "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        "certificate_sha256": certificate,
        "application_id": package,
        "version_name": "1.4", "version_code": 5, "min_sdk": 23, "target_sdk": 35,
        "signature_verified": True,
        "verified_classes": sorted(expected_classes),
        "unit_tests": {"passed": passed, "failed": 0, "skipped": 0},
        "lint_errors": 0,
        "lint_warnings": len([i for i in lint.findall("issue") if i.get("severity") == "Warning"]),
        "source_commit": run("git", "rev-parse", "HEAD").strip(),
        "device_test_status": "Not tested on an emulator or physical device"
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
