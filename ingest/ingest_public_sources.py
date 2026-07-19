#!/usr/bin/env python3
"""Ingest public My Number-related documents and convert PDFs to WebP.

The script is intentionally conservative:
  - seed pages are explicit in sources.json;
  - discovered links are restricted to allowlisted hosts;
  - private/NDA/member-only URLs are skipped by default;
  - IPFS writes happen only when --ipfs and credentials/tooling are available.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import mimetypes
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parents[1]
INGEST_ROOT = PROJECT_ROOT / "data" / "ingest"
ORIGINAL_DIR = INGEST_ROOT / "blobs" / "original"
WEBP_DIR = INGEST_ROOT / "blobs" / "webp"
TMP_DIR = INGEST_ROOT / "tmp"
SOURCES_PATH = PROJECT_ROOT / "ingest" / "sources.json"
MANIFEST_PATH = INGEST_ROOT / "manifest.json"

PRIVATE_HINTS = (
    "gcas",
    "digital-pmo",
    "members",
    "member",
    "login",
    "申請フォーム",
    "仕様書取得フォーム",
    "利用申請",
)


class LinkParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.links: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag not in {"a", "link"}:
            return
        values = dict(attrs)
        href = values.get("href")
        if href:
            self.links.append(href)


@dataclass
class Artifact:
    source_url: str
    path: Path
    media_type: str
    sha256: str
    cidv1_raw_sha256: str
    bytes: int
    role: str
    ipfs: dict[str, Any] | None = None
    derivatives: list[dict[str, Any]] | None = None


def load_config() -> dict[str, Any]:
    return json.loads(SOURCES_PATH.read_text(encoding="utf-8"))


def ensure_dirs() -> None:
    ORIGINAL_DIR.mkdir(parents=True, exist_ok=True)
    WEBP_DIR.mkdir(parents=True, exist_ok=True)
    TMP_DIR.mkdir(parents=True, exist_ok=True)


def is_allowed_url(url: str, config: dict[str, Any]) -> bool:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        return False
    if parsed.hostname not in set(config["same_hosts"]):
        return False
    lowered = url.lower()
    if any(hint in lowered for hint in PRIVATE_HINTS):
        return False
    path = parsed.path.lower()
    if not path or path.endswith("/"):
        return True
    return any(path.endswith(ext) for ext in config["include_extensions"])


def discover_urls(config: dict[str, Any]) -> list[str]:
    urls: list[str] = []
    seen: set[str] = set()
    for seed in config["seed_pages"]:
        seed_url = seed["url"]
        if seed_url not in seen:
            urls.append(seed_url)
            seen.add(seed_url)
        try:
            body, _ = fetch(seed_url)
        except Exception as exc:  # noqa: BLE001
            print(f"warn: failed to discover {seed_url}: {exc}", file=sys.stderr)
            continue
        parser = LinkParser()
        parser.feed(body.decode("utf-8", "replace"))
        for href in parser.links:
            url = urllib.parse.urljoin(seed_url, href).split("#", 1)[0]
            if url not in seen and is_allowed_url(url, config):
                urls.append(url)
                seen.add(url)
    return urls


def fetch(url: str) -> tuple[bytes, str]:
    req = urllib.request.Request(
        url,
        headers={
            "user-agent": "etzhayyim-open-jpn-mynumber-public-ingest/0.1 (+https://etzhayyim.com)",
            "accept": "*/*",
        },
    )
    with urllib.request.urlopen(req, timeout=90) as resp:
        body = resp.read()
        media_type = resp.headers.get_content_type() or guess_media_type(url)
    return body, media_type


def guess_media_type(url_or_path: str | Path) -> str:
    guessed, _ = mimetypes.guess_type(str(url_or_path))
    return guessed or "application/octet-stream"


def safe_name(url: str, body: bytes, media_type: str) -> str:
    parsed = urllib.parse.urlparse(url)
    base = Path(urllib.parse.unquote(parsed.path)).name or "index.html"
    digest = hashlib.sha256(body).hexdigest()[:16]
    if "." not in base:
        ext = mimetypes.guess_extension(media_type) or ".bin"
        base = f"{base}{ext}"
    return f"{digest}-{base}"


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def varint(value: int) -> bytes:
    out = bytearray()
    while value >= 0x80:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def cidv1_raw_sha256(data: bytes) -> str:
    # CIDv1 = cid-version + raw multicodec + multihash(sha2-256, digest)
    digest = hashlib.sha256(data).digest()
    cid_bytes = varint(1) + varint(0x55) + varint(0x12) + varint(len(digest)) + digest
    encoded = base64.b32encode(cid_bytes).decode("ascii").lower().rstrip("=")
    return f"b{encoded}"


def file_artifact(source_url: str, path: Path, role: str) -> Artifact:
    data = path.read_bytes()
    return Artifact(
        source_url=source_url,
        path=path,
        media_type=guess_media_type(path),
        sha256=sha256_hex(data),
        cidv1_raw_sha256=cidv1_raw_sha256(data),
        bytes=len(data),
        role=role,
    )


def convert_pdf_to_webp(pdf_path: Path, source_url: str, quality: int, max_pages: int | None) -> list[Artifact]:
    if not shutil.which("pdftoppm"):
        raise RuntimeError("pdftoppm is required for PDF rendering")
    if not shutil.which("cwebp"):
        raise RuntimeError("cwebp is required for WebP conversion")
    out: list[Artifact] = []
    with tempfile.TemporaryDirectory(dir=TMP_DIR) as tmp_name:
        tmp = Path(tmp_name)
        prefix = tmp / "page"
        cmd = ["pdftoppm", "-r", "150", "-png"]
        if max_pages:
            cmd += ["-f", "1", "-l", str(max_pages)]
        cmd += [str(pdf_path), str(prefix)]
        subprocess.run(cmd, check=True, capture_output=True)
        page_pngs = sorted(tmp.glob("page-*.png"))
        pdf_stem = pdf_path.stem
        for idx, png_path in enumerate(page_pngs, start=1):
            webp_path = WEBP_DIR / f"{pdf_stem}-page-{idx:04d}.webp"
            subprocess.run(
                ["cwebp", "-q", str(quality), "-mt", "-quiet", str(png_path), "-o", str(webp_path)],
                check=True,
                capture_output=True,
            )
            out.append(file_artifact(source_url, webp_path, "pdf-page-webp"))
    return out


def ipfs_add_cli(path: Path) -> dict[str, Any] | None:
    if not shutil.which("ipfs"):
        return None
    proc = subprocess.run(
        ["ipfs", "add", "--pin=true", "--cid-version=1", "--raw-leaves=true", "-Q", str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    return {"method": "ipfs-cli", "cid": proc.stdout.strip()}


def ipfs_add_http(path: Path) -> dict[str, Any] | None:
    api = os.environ.get("MYNUMBER_IPFS_API", "").rstrip("/")
    if not api:
        return None
    boundary = f"etzhayyim{hashlib.sha256(path.name.encode()).hexdigest()[:24]}"
    data = path.read_bytes()
    body = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
        f"Content-Type: {guess_media_type(path)}\r\n\r\n"
    ).encode("utf-8") + data + f"\r\n--{boundary}--\r\n".encode("utf-8")
    headers = {"content-type": f"multipart/form-data; boundary={boundary}"}
    secret = os.environ.get("MYNUMBER_IPFS_HMAC")
    if secret:
        headers["X-etzhayyim-Ipfs-Auth"] = hmac.new(secret.encode("utf-8"), body, hashlib.sha256).hexdigest()
    req = urllib.request.Request(
        f"{api}/add?pin=true&cid-version=1&raw-leaves=true",
        data=body,
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            raw = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return {"method": "ipfs-http", "error": f"HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')[:300]}"}
    last = [line for line in raw.splitlines() if line.strip()][-1]
    parsed = json.loads(last)
    return {"method": "ipfs-http", "cid": parsed.get("Hash"), "raw": parsed}


def maybe_ipfs_add(path: Path, enabled: bool) -> dict[str, Any] | None:
    if not enabled:
        return None
    cli = ipfs_add_cli(path)
    if cli:
        return cli
    return ipfs_add_http(path)


def artifact_to_dict(artifact: Artifact) -> dict[str, Any]:
    return {
        "source_url": artifact.source_url,
        "role": artifact.role,
        "path": str(artifact.path.relative_to(PROJECT_ROOT)),
        "media_type": artifact.media_type,
        "sha256": artifact.sha256,
        "cidv1_raw_sha256": artifact.cidv1_raw_sha256,
        "bytes": artifact.bytes,
        "ipfs": artifact.ipfs,
        "derivatives": artifact.derivatives or [],
    }


def ingest(args: argparse.Namespace) -> dict[str, Any]:
    ensure_dirs()
    config = load_config()
    urls = discover_urls(config)
    if args.limit:
        urls = urls[: args.limit]

    artifacts: list[dict[str, Any]] = []
    failures: list[dict[str, str]] = []
    for idx, url in enumerate(urls, start=1):
        print(f"[{idx}/{len(urls)}] fetch {url}", file=sys.stderr)
        try:
            body, media_type = fetch(url)
            original_path = ORIGINAL_DIR / safe_name(url, body, media_type)
            original_path.write_bytes(body)
            original = file_artifact(url, original_path, "source-original")
            original.media_type = media_type
            original.ipfs = maybe_ipfs_add(original_path, args.ipfs)
            if original_path.suffix.lower() == ".pdf" or media_type == "application/pdf":
                derivatives = convert_pdf_to_webp(original_path, url, args.webp_quality, args.max_pdf_pages)
                for derivative in derivatives:
                    derivative.ipfs = maybe_ipfs_add(derivative.path, args.ipfs)
                original.derivatives = [artifact_to_dict(item) for item in derivatives]
            artifacts.append(artifact_to_dict(original))
        except Exception as exc:  # noqa: BLE001
            failures.append({"url": url, "error": f"{type(exc).__name__}: {exc}"})
            print(f"warn: {url}: {exc}", file=sys.stderr)
        time.sleep(args.delay)

    manifest = {
        "project": "open-jpn-mynumber",
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "ipfs_requested": args.ipfs,
        "ipfs_available": bool(shutil.which("ipfs") or os.environ.get("MYNUMBER_IPFS_API")),
        "source_count": len(urls),
        "artifact_count": len(artifacts),
        "failure_count": len(failures),
        "artifacts": artifacts,
        "failures": failures,
    }
    MANIFEST_PATH.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=0, help="limit URLs for smoke runs")
    parser.add_argument("--delay", type=float, default=0.2)
    parser.add_argument("--webp-quality", type=int, default=82)
    parser.add_argument("--max-pdf-pages", type=int, default=0, help="0 means all pages")
    parser.add_argument("--ipfs", action="store_true", help="add/pin artifacts to IPFS when configured")
    args = parser.parse_args()
    if args.max_pdf_pages <= 0:
        args.max_pdf_pages = None
    manifest = ingest(args)
    print(json.dumps({
        "manifest": str(MANIFEST_PATH.relative_to(PROJECT_ROOT)),
        "source_count": manifest["source_count"],
        "artifact_count": manifest["artifact_count"],
        "failure_count": manifest["failure_count"],
        "ipfs_available": manifest["ipfs_available"],
    }, ensure_ascii=False))
    return 1 if manifest["failure_count"] else 0


if __name__ == "__main__":
    raise SystemExit(main())

