#!/usr/bin/env python3
"""Summarize GestureEVO memory usage through ADB.

Usage:
  python tools/adb_memory_summary.py
  python tools/adb_memory_summary.py --device <serial>
  python tools/adb_memory_summary.py --package io.github.lumiagg.gestureevo --no-smaps
"""

from __future__ import annotations

import argparse
import collections
import datetime as dt
import re
import subprocess
import sys
from dataclasses import dataclass

DEFAULT_PACKAGE = "io.github.lumiagg.gestureevo"


def run_adb(args: list[str], device: str | None = None, check: bool = True) -> str:
    command = ["adb"]
    if device:
        command += ["-s", device]
    command += args
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if check and result.returncode != 0:
        raise RuntimeError(f"Command failed: {' '.join(command)}\n{result.stdout.strip()}")
    return result.stdout


def kb_to_mb(kb: int) -> str:
    return f"{kb / 1024.0:.1f} MB"


def first_number(line: str | None) -> int | None:
    if not line:
        return None
    match = re.search(r"([0-9]+)", line)
    return int(match.group(1)) if match else None


def find_line(lines: list[str], pattern: str) -> str | None:
    regex = re.compile(pattern)
    return next((line for line in lines if regex.search(line)), None)


@dataclass
class MemSummary:
    total_pss: int | None = None
    total_rss: int | None = None
    total_swap_pss: int | None = None
    java_heap: int | None = None
    native_heap: int | None = None
    graphics: int | None = None
    code: int | None = None
    stack: int | None = None
    private_other: int | None = None
    views_line: str | None = None
    activities_line: str | None = None


def parse_meminfo(meminfo: str) -> MemSummary:
    lines = meminfo.splitlines()
    summary = MemSummary()
    total_line = find_line(lines, r"TOTAL PSS:")
    if total_line:
        match = re.search(r"TOTAL PSS:\s*([0-9]+).*TOTAL RSS:\s*([0-9]+).*TOTAL SWAP PSS:\s*([0-9]+)", total_line)
        if match:
            summary.total_pss = int(match.group(1))
            summary.total_rss = int(match.group(2))
            summary.total_swap_pss = int(match.group(3))

    summary.java_heap = first_number(find_line(lines, r"Java Heap:"))
    summary.native_heap = first_number(find_line(lines, r"Native Heap:"))
    summary.graphics = first_number(find_line(lines, r"Graphics:"))
    summary.code = first_number(find_line(lines, r"Code:"))
    summary.stack = first_number(find_line(lines, r"Stack:"))
    summary.private_other = first_number(find_line(lines, r"Private Other:"))
    summary.views_line = find_line(lines, r"Views:")
    summary.activities_line = find_line(lines, r"AppContexts:")
    return summary


@dataclass
class SmapsGroup:
    rss: int = 0
    pss: int = 0
    private_dirty: int = 0
    private_clean: int = 0
    swap: int = 0
    count: int = 0


def parse_smaps(smaps: str) -> dict[str, SmapsGroup]:
    header = re.compile(r"^([0-9a-f]+)-([0-9a-f]+)\s+(\S+)\s+([0-9a-f]+)\s+\S+\s+\S+\s*(.*)$")
    entries: list[dict[str, int | str]] = []
    current: dict[str, int | str] | None = None

    for line in smaps.splitlines():
        match = header.match(line)
        if match:
            if current:
                entries.append(current)
            start, end, perm, _offset, path = match.groups()
            current = {
                "path": path.strip() or "[anonymous]",
                "perm": perm,
                "size": (int(end, 16) - int(start, 16)) // 1024,
                "rss": 0,
                "pss": 0,
                "private_dirty": 0,
                "private_clean": 0,
                "swap": 0,
            }
            continue
        if not current:
            continue
        if line.startswith("Rss:"):
            current["rss"] = int(line.split()[1])
        elif line.startswith("Pss:"):
            current["pss"] = int(line.split()[1])
        elif line.startswith("Private_Dirty:"):
            current["private_dirty"] = int(line.split()[1])
        elif line.startswith("Private_Clean:"):
            current["private_clean"] = int(line.split()[1])
        elif line.startswith("Swap:"):
            current["swap"] = int(line.split()[1])
    if current:
        entries.append(current)

    groups: dict[str, SmapsGroup] = collections.defaultdict(SmapsGroup)
    for entry in entries:
        group = groups[str(entry["path"])]
        group.rss += int(entry["rss"])
        group.pss += int(entry["pss"])
        group.private_dirty += int(entry["private_dirty"])
        group.private_clean += int(entry["private_clean"])
        group.swap += int(entry["swap"])
        group.count += 1
    return groups


def print_kb(label: str, value: int | None) -> None:
    if value is not None:
        print(f"{label:<16}{value:>10,} KB  ({kb_to_mb(value)})")


def print_summary(package: str, pid: str, summary: MemSummary) -> None:
    print("== GestureEVO ADB Memory Summary ==")
    print(f"Package: {package}")
    print(f"PID:     {pid}")
    print(f"Time:    {dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print()
    print("== App Summary ==")
    print_kb("TOTAL PSS:", summary.total_pss)
    print_kb("TOTAL RSS:", summary.total_rss)
    print_kb("TOTAL SWAP PSS:", summary.total_swap_pss)
    print_kb("Java Heap:", summary.java_heap)
    print_kb("Native Heap:", summary.native_heap)
    print_kb("Graphics:", summary.graphics)
    print_kb("Code:", summary.code)
    print_kb("Stack:", summary.stack)
    print_kb("Private Other:", summary.private_other)
    if summary.views_line:
        print(summary.views_line.strip())
    if summary.activities_line:
        print(summary.activities_line.strip())
    print()


def print_smaps(groups: dict[str, SmapsGroup], limit: int) -> None:
    print("== Top smaps groups by PSS ==")
    for path, group in sorted(groups.items(), key=lambda item: item[1].pss, reverse=True)[:limit]:
        print(
            f"{group.pss:9,} KB ({kb_to_mb(group.pss):>8}) PSS  "
            f"{group.rss:9,} KB RSS  {group.private_dirty:9,} KB PrivateDirty  "
            f"{group.count:4d} maps  {path[:140]}"
        )
    print()

    print("== OCR / TFLite / LiteRT mappings ==")
    ocr_total = 0
    found = False
    for path, group in sorted(groups.items(), key=lambda item: item[1].pss, reverse=True):
        lower = path.lower()
        if any(token in lower for token in ("ocr_", "tflite", "litert", "ppocr")):
            found = True
            ocr_total += group.pss
            print(
                f"{group.pss:9,} KB ({kb_to_mb(group.pss):>8}) PSS  "
                f"{group.rss:9,} KB RSS  {group.count:4d} maps  {path[:180]}"
            )
    if not found:
        print("(none)")
    print(f"OCR file/runtime named mappings total: {ocr_total:,} KB ({kb_to_mb(ocr_total)})")
    print()

    scudo = sum(group.pss for path, group in groups.items() if "scudo" in path.lower())
    graphics_named = sum(
        group.pss
        for path, group in groups.items()
        if any(token in path.lower() for token in ("kgsl", "mali", "gralloc", "dmabuf", "egl", "gles"))
    )
    print("== Heuristic summary ==")
    print(f"scudo native allocator PSS: {scudo:,} KB ({kb_to_mb(scudo)})")
    print(f"graphics-related named PSS: {graphics_named:,} KB ({kb_to_mb(graphics_named)})")
    if scudo > 300 * 1024:
        print("Native allocator is high. If OCR was preloaded or recently used, this is likely OCR runtime/tensor/delegate memory.")
    if found:
        print("OCR model files are mapped, so built-in OCR model/runtime is currently loaded or recently active.")
    else:
        print("No OCR model file mapping found. If memory is high, check Graphics/GL or unnamed native allocator usage.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Summarize GestureEVO memory usage through ADB.")
    parser.add_argument("--package", default=DEFAULT_PACKAGE, help=f"Android package name. Default: {DEFAULT_PACKAGE}")
    parser.add_argument("--device", default=None, help="ADB serial. Useful when multiple devices are connected.")
    parser.add_argument("--no-smaps", action="store_true", help="Skip run-as /proc/<pid>/smaps parsing.")
    parser.add_argument("--top", type=int, default=18, help="Number of smaps groups to print. Default: 18")
    args = parser.parse_args()

    devices = [line for line in run_adb(["devices"], args.device).splitlines()[1:] if line.strip()]
    if not devices:
        raise RuntimeError("No adb device found. Please connect a device and run 'adb devices'.")
    if args.device is None and len(devices) > 1:
        print("Multiple devices found; use --device <serial> if needed.", file=sys.stderr)

    pid_output = run_adb(["shell", "pidof", args.package], args.device, check=False).strip()
    if not pid_output:
        raise RuntimeError(f"Process not found for package '{args.package}'. Is the app running?")
    pid = pid_output.split()[0]

    meminfo = run_adb(["shell", "dumpsys", "meminfo", "-a", args.package], args.device)
    summary = parse_meminfo(meminfo)
    print_summary(args.package, pid, summary)

    if args.no_smaps:
        print("smaps parsing skipped by --no-smaps.")
        return 0

    smaps = run_adb(["shell", "run-as", args.package, "cat", f"/proc/{pid}/smaps"], args.device, check=False)
    if re.search(r"Permission denied|not debuggable|run-as", smaps, re.IGNORECASE):
        print("smaps unavailable via run-as; package may not be debuggable.", file=sys.stderr)
        return 0

    groups = parse_smaps(smaps)
    print_smaps(groups, args.top)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(1)
