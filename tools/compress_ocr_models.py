#!/usr/bin/env python3
import argparse
import hashlib
import io
import json
import lzma
import tarfile
from pathlib import Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_manifest_version(source_dir: Path) -> str:
    manifest_path = source_dir / "manifest.json"
    if not manifest_path.exists():
        return "0"
    with manifest_path.open("r", encoding="utf-8") as file:
        manifest = json.load(file)
    return str(manifest.get("version", "0"))


def collect_files(source_dir: Path) -> list[Path]:
    return sorted(path for path in source_dir.rglob("*") if path.is_file())


def build_version(source_dir: Path, files: list[Path]) -> str:
    digest = hashlib.sha256()
    for path in files:
        relative = path.relative_to(source_dir).as_posix()
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(sha256_file(path).encode("ascii"))
        digest.update(b"\0")
    return f"v{load_manifest_version(source_dir)}-{digest.hexdigest()[:16]}"


def add_file(tar: tarfile.TarFile, source_dir: Path, path: Path) -> None:
    relative = path.relative_to(source_dir).as_posix()
    data = path.read_bytes()
    info = tarfile.TarInfo(relative)
    info.size = len(data)
    info.mtime = 0
    info.mode = 0o644
    info.uid = 0
    info.gid = 0
    info.uname = ""
    info.gname = ""
    tar.addfile(info, io.BytesIO(data))


def write_archive(source_dir: Path, archive_path: Path, files: list[Path]) -> None:
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    with lzma.open(
        archive_path,
        "wb",
        format=lzma.FORMAT_XZ,
        check=lzma.CHECK_CRC64,
        preset=9 | lzma.PRESET_EXTREME,
    ) as compressed:
        with tarfile.open(fileobj=compressed, mode="w|") as tar:
            for path in files:
                add_file(tar, source_dir, path)


def main() -> None:
    parser = argparse.ArgumentParser(description="Compress OCR model assets into a deterministic tar.xz archive.")
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--version-output", required=True, type=Path)
    args = parser.parse_args()

    source_dir = args.source.resolve()
    if not source_dir.is_dir():
        raise SystemExit(f"source directory does not exist: {source_dir}")

    files = collect_files(source_dir)
    if not files:
        raise SystemExit(f"no model files found in: {source_dir}")

    version = build_version(source_dir, files)
    write_archive(source_dir, args.output.resolve(), files)
    args.version_output.parent.mkdir(parents=True, exist_ok=True)
    args.version_output.write_text(version + "\n", encoding="utf-8")

    print(f"models archive: {args.output}")
    print(f"models version: {version}")
    print(f"archive sha256: {sha256_file(args.output.resolve())}")


if __name__ == "__main__":
    main()
