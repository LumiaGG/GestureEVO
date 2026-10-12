#!/usr/bin/env python3
"""Check tracked repository hygiene and declared binary inputs without network access."""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FORBIDDEN_PARTS = {".idea", ".gradle", ".kotlin", ".cxx", "__pycache__", ".agents", ".codex", ".signing"}
FORBIDDEN_SUFFIXES = {".pyc", ".pyo", ".log", ".apk", ".aab", ".jks", ".keystore", ".p12", ".pfx", ".idsig"}
REQUIRED_FILES = ("LICENSE", "README.md", "NOTICE", "CONTRIBUTING.md", "SECURITY.md",
                  "third_party/binary-manifest.json")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def forbidden_path(path: str) -> bool:
    source = Path(path)
    return (bool(set(source.parts) & FORBIDDEN_PARTS)
            or "build" in source.parts
            or source.suffix.lower() in FORBIDDEN_SUFFIXES
            or source.name in {"local.properties", "keystore.properties", "output-metadata.json"}
            or source.name == ".env" or source.name.startswith(".env.") and source.name != ".env.example")


def contains_private_key(content: bytes) -> bool:
    return re.search(
        rb"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED |PGP )?PRIVATE KEY(?: BLOCK)?-----",
        content,
    ) is not None


def main() -> int:
    paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).decode("utf-8").split("\0")
    failures = [f"Generated/private file is tracked: {path}" for path in paths if path and forbidden_path(path)]
    failures.extend(f"Required document is missing: {path}" for path in REQUIRED_FILES if not (ROOT / path).is_file())
    for relative in paths:
        path = ROOT / relative
        if path.is_file():
            with path.open("rb") as stream:
                content = stream.read(1024 * 1024)
            if contains_private_key(content):
                failures.append(f"Private key material is tracked: {relative}")
    manifest = ROOT / "third_party/binary-manifest.json"
    if manifest.is_file():
        records = json.loads(manifest.read_text(encoding="utf-8"))
        declared = set()
        for entry in records["files"]:
            relative = entry["path"]
            path = (ROOT / relative).resolve()
            if not path.is_relative_to(ROOT) or not path.is_file():
                failures.append(f"Declared binary is missing or outside repository: {relative}")
                continue
            declared.add(relative)
            if path.stat().st_size != entry["sizeBytes"] or sha256_file(path) != entry["sha256"]:
                failures.append(f"Binary differs from its manifest: {relative}")
        for relative in paths:
            if Path(relative).suffix in {".so", ".jar", ".tflite"} and relative not in declared:
                failures.append(f"Binary input lacks a manifest entry: {relative}")
    for relative in ("build_release.ps1", "build_release.sh", "tools/build_release.py", "tools/publish_test_build.py"):
        if (ROOT / relative).exists():
            failures.append(f"Removed legacy release tool must not be reintroduced: {relative}")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("Repository hygiene and binary manifest checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
