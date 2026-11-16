#!/usr/bin/env python3
"""Build the latest Debug, install it and restart only its accessibility service.

Usage: python tools/install_debug.py [--skip-build] [--serial SERIAL] [--apk PATH]
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io.github.lumiagg.gestureevo.debug"
SERVICE = f"{PACKAGE}/io.github.lumiagg.gestureevo.gesture.GestureService"
ACTIVITY = f"{PACKAGE}/io.github.lumiagg.gestureevo.MainActivity"


def run(command: list[str]) -> str:
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace", cwd=ROOT, timeout=600)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(command)}\n{result.stdout}")
    return result.stdout.strip()


def online_devices(output: str) -> list[str]:
    return [parts[0] for line in output.splitlines()
            if len(parts := line.split()) >= 2 and parts[1] == "device"]


def canonical(component: str) -> str:
    package, separator, name = component.partition("/")
    return package + separator + (package + name if name.startswith(".") else name)


def service_list(value: str, enabled: bool) -> list[str]:
    services = [item for item in value.split(":")
                if item and item != "null" and canonical(item) != SERVICE]
    return services + ([SERVICE] if enabled else [])


def service_bound(dump: str) -> bool:
    # Match the bound list, not enabled/crashed entries elsewhere in dumpsys.
    for bound in re.findall(r"Bound services:\s*\{([^}]*)\}", dump, re.IGNORECASE):
        if SERVICE in bound or "GestureEVO Debug" in bound:
            return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB serial; required when multiple devices are online")
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--skip-build", action="store_true", help="Install an existing APK without building")
    parser.add_argument("--bind-timeout", type=float, default=15)
    args = parser.parse_args()
    if not args.skip_build:
        print("Building latest Debug...", flush=True)
        run((["cmd.exe", "/d", "/c", "gradlew.bat"] if sys.platform == "win32" else ["./gradlew"])
            + [":app:assembleDebug", "--offline"])
        print("Debug build succeeded.", flush=True)
    if not args.apk.is_file():
        raise RuntimeError(f"APK missing: {args.apk}")
    devices = online_devices(run(["adb", "devices"]))
    serial = args.serial
    if serial is None:
        if len(devices) != 1:
            raise RuntimeError(f"Expected one online device, found {devices}; specify --serial")
        serial = devices[0]
    if serial not in devices:
        raise RuntimeError(f"Device is not online: {serial}")

    def adb(*arguments: str) -> str:
        return run(["adb", "-s", serial, *arguments])

    result = adb("install", "-r", str(args.apk.resolve()))
    if "Success" not in result:
        raise RuntimeError(f"Install failed: {result}")
    print(f"Installed Debug on {serial}", flush=True)
    user = adb("shell", "am", "get-current-user")
    if not user.isdigit():
        raise RuntimeError(f"Cannot determine Android user: {user}")

    def set_service(enabled: bool) -> None:
        # Re-read before each change so another service's latest selection is preserved.
        current = adb("shell", "settings", "--user", user, "get", "secure",
                      "enabled_accessibility_services")
        services = service_list(current, enabled)
        command = ["shell", "settings", "--user", user]
        if services:
            adb(*command, "put", "secure", "enabled_accessibility_services", ":".join(services))
        else:
            adb(*command, "delete", "secure", "enabled_accessibility_services")

    adb("shell", "am", "start", "--user", user, "-n", ACTIVITY)
    try:
        set_service(False)
        time.sleep(0.5)
        set_service(True)
        adb("shell", "settings", "--user", user, "put", "secure", "accessibility_enabled", "1")
        deadline = time.monotonic() + max(0, args.bind_timeout)
        while True:
            if service_bound(adb("shell", "dumpsys", "accessibility")):
                print("Debug accessibility service is bound. Device operations finished.")
                return 0
            if time.monotonic() >= deadline:
                break
            time.sleep(1)
    except RuntimeError as error:
        print(f"Automatic accessibility activation failed: {error}", file=sys.stderr)
        # Restore this service's enabled selection if the restart stopped halfway.
        try:
            set_service(True)
        except RuntimeError:
            pass
    print("Binding not confirmed; opening accessibility settings for manual activation.", flush=True)
    try:
        adb("shell", "am", "start", "--user", user, "-a",
            "android.settings.ACCESSIBILITY_DETAILS_SETTINGS", "--ecn",
            "android.intent.extra.COMPONENT_NAME", SERVICE)
    except RuntimeError:
        adb("shell", "am", "start", "--user", user, "-a", "android.settings.ACCESSIBILITY_SETTINGS")
    return 2


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
