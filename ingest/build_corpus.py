#!/usr/bin/env python3
"""Build a searchable corpus from the public ingest manifest.

Output:
  data/ingest/corpus.jsonl
"""

from __future__ import annotations

import argparse
import html
import json
import re
import subprocess
import tempfile
import zipfile
from html.parser import HTMLParser
from pathlib import Path
from typing import Any
import defusedxml.ElementTree as ElementTree


PROJECT_ROOT = Path(__file__).resolve().parents[1]
INGEST_ROOT = PROJECT_ROOT / "data" / "ingest"
MANIFEST_PATH = INGEST_ROOT / "manifest.json"
CORPUS_JSONL = INGEST_ROOT / "corpus.jsonl"


class TextHTMLParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.parts: list[str] = []
        self.skip_depth = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in {"script", "style", "noscript"}:
            self.skip_depth += 1
        if tag in {"p", "div", "section", "article", "li", "tr", "br", "h1", "h2", "h3", "h4"}:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style", "noscript"} and self.skip_depth:
            self.skip_depth -= 1
        if tag in {"p", "div", "section", "article", "li", "tr", "h1", "h2", "h3", "h4"}:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if not self.skip_depth:
            self.parts.append(data)


def clean_text(text: str) -> str:
    text = html.unescape(text)
    text = text.replace("\u3000", " ")
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    text = re.sub(r"\n[ \t]+", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def extract_html(path: Path) -> str:
    parser = TextHTMLParser()
    parser.feed(path.read_text(encoding="utf-8", errors="replace"))
    return clean_text("".join(parser.parts))


def extract_pdf(path: Path) -> str:
    with tempfile.TemporaryDirectory() as tmp_name:
        out = Path(tmp_name) / "out.txt"
        subprocess.run(
            ["pdftotext", "-layout", str(path), str(out)],
            check=True,
            capture_output=True,
        )
        return clean_text(out.read_text(encoding="utf-8", errors="replace"))


def xml_text(data: bytes) -> str:
    root = ElementTree.fromstring(data)
    return clean_text("\n".join(node.text or "" for node in root.iter()))


def extract_docx(path: Path) -> str:
    with zipfile.ZipFile(path) as zf:
        names = [name for name in zf.namelist() if name.startswith("word/") and name.endswith(".xml")]
        preferred = ["word/document.xml"] + sorted(name for name in names if name != "word/document.xml")
        parts = []
        for name in preferred:
            try:
                parts.append(xml_text(zf.read(name)))
            except Exception:  # noqa: BLE001
                continue
    return clean_text("\n".join(parts))


def extract_xlsx(path: Path) -> str:
    with zipfile.ZipFile(path) as zf:
        names = zf.namelist()
        parts = []
        if "xl/sharedStrings.xml" in names:
            parts.append(xml_text(zf.read("xl/sharedStrings.xml")))
        sheet_names = sorted(name for name in names if name.startswith("xl/worksheets/") and name.endswith(".xml"))
        for name in sheet_names:
            try:
                parts.append(xml_text(zf.read(name)))
            except Exception:  # noqa: BLE001
                continue
    return clean_text("\n".join(parts))


def extract_zip_listing(path: Path) -> str:
    with zipfile.ZipFile(path) as zf:
        return clean_text("\n".join(zf.namelist()))


def extract_text(path: Path, media_type: str) -> str:
    suffix = path.suffix.lower()
    if media_type == "text/html" or suffix in {".html", ".htm"}:
        return extract_html(path)
    if media_type == "application/pdf" or suffix == ".pdf":
        return extract_pdf(path)
    if suffix == ".docx" or "wordprocessingml.document" in media_type:
        return extract_docx(path)
    if suffix == ".xlsx" or "spreadsheetml.sheet" in media_type:
        return extract_xlsx(path)
    if suffix == ".zip" or media_type == "application/zip":
        return extract_zip_listing(path)
    return ""


def chunks(text: str, size: int = 1800, overlap: int = 180) -> list[str]:
    if not text:
        return []
    paras = [part.strip() for part in re.split(r"\n{2,}", text) if part.strip()]
    out: list[str] = []
    buf = ""
    for para in paras:
        if len(buf) + len(para) + 2 <= size:
            buf = f"{buf}\n\n{para}".strip()
            continue
        if buf:
            out.append(buf)
        if len(para) <= size:
            buf = para
            continue
        start = 0
        while start < len(para):
            out.append(para[start : start + size])
            start += max(1, size - overlap)
        buf = ""
    if buf:
        out.append(buf)
    return out


def artifact_id(artifact: dict[str, Any], index: int) -> str:
    return f"{index:04d}-{artifact['sha256'][:16]}"


def build(args: argparse.Namespace) -> dict[str, Any]:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    total_chunks = 0
    failures: list[dict[str, str]] = []
    with CORPUS_JSONL.open("w", encoding="utf-8") as out:
        for artifact_index, artifact in enumerate(manifest["artifacts"], start=1):
            rel = artifact["path"]
            path = PROJECT_ROOT / rel
            doc_id = artifact_id(artifact, artifact_index)
            try:
                text = extract_text(path, artifact["media_type"])
                doc_chunks = chunks(text, args.chunk_size, args.chunk_overlap)
                for idx, chunk in enumerate(doc_chunks):
                    chunk_id = f"{doc_id}:{idx:04d}"
                    row = {
                        "chunk_id": chunk_id,
                        "document_id": doc_id,
                        "chunk_index": idx,
                        "source_url": artifact["source_url"],
                        "path": rel,
                        "media_type": artifact["media_type"],
                        "sha256": artifact["sha256"],
                        "cid": (artifact.get("ipfs") or {}).get("cid") or artifact.get("cidv1_raw_sha256"),
                        "extracted_chars": len(text),
                        "text": chunk,
                    }
                    out.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
                    total_chunks += 1
            except Exception as exc:  # noqa: BLE001
                failures.append({"path": rel, "error": f"{type(exc).__name__}: {exc}"})
    summary = {
        "documents": len(manifest["artifacts"]),
        "chunks": total_chunks,
        "failures": failures,
        "jsonl": str(CORPUS_JSONL.relative_to(PROJECT_ROOT)),
    }
    (INGEST_ROOT / "corpus-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return summary


def match_query(text: str, query: str) -> bool:
    terms = [term for term in re.split(r"\s+", query.strip()) if term]
    if not terms:
        return False
    lowered = text.casefold()
    return all(term.casefold() in lowered for term in terms)


def snippet(text: str, query: str, width: int = 180) -> str:
    lowered = text.casefold()
    positions = [lowered.find(term.casefold()) for term in re.split(r"\s+", query.strip()) if term]
    positions = [pos for pos in positions if pos >= 0]
    start = max(0, (min(positions) if positions else 0) - width // 2)
    excerpt = " ".join(text[start : start + width].split())
    return excerpt


def search(args: argparse.Namespace) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with CORPUS_JSONL.open("r", encoding="utf-8") as corpus:
        for line in corpus:
            row = json.loads(line)
            if not match_query(row.get("text", ""), args.query):
                continue
            rows.append({
                "chunk_id": row["chunk_id"],
                "document_id": row["document_id"],
                "chunk_index": row["chunk_index"],
                "source_url": row["source_url"],
                "path": row["path"],
                "media_type": row["media_type"],
                "cid": row.get("cid"),
                "snippet": snippet(row.get("text", ""), args.query),
            })
            if len(rows) >= args.limit:
                break
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("build")
    b.add_argument("--chunk-size", type=int, default=1800)
    b.add_argument("--chunk-overlap", type=int, default=180)
    s = sub.add_parser("search")
    s.add_argument("query")
    s.add_argument("--limit", type=int, default=10)
    args = parser.parse_args()
    if args.command == "build":
        print(json.dumps(build(args), ensure_ascii=False, indent=2))
        return 0
    print(json.dumps(search(args), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
