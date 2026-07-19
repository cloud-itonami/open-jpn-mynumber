# Public Source Ingest

This pipeline ingests only public My Number-related source material:

- Digital Agency common-function public specification page and linked public
  assets.
- Digital Agency My Number policy pages.
- Myna Portal public API index and linked public assets.

It does not fetch private GCAS, Digital PMO, J-LIS member-only, or NDA-gated
specifications.

## Local Run

```bash
python3 ingest/ingest_public_sources.py --limit 20 --max-pdf-pages 3
```

Outputs are written under `data/ingest/`:

- `manifest.json`: source URL, media type, SHA-256, local CID, optional IPFS
  result, and PDF WebP derivative metadata.
- `blobs/original/`: fetched original public files.
- `blobs/webp/`: PDF page derivatives as WebP.

## Corpus Build

After ingest, build a searchable text corpus:

```bash
python3 ingest/build_corpus.py build
python3 ingest/build_corpus.py search '個人番号'
```

The corpus builder extracts text from HTML, PDF, DOCX, XLSX, and ZIP listings.
It writes `corpus.jsonl`. Durable state and query projections belong in the
Kysely-managed graph schema, not a local SQLite index.

## Coverage Map

After corpus build, generate the specification-to-implementation coverage map:

```bash
python3 coverage/build_coverage.py
```

This writes `coverage/coverage.json` and `coverage/coverage.md`, both ignored
because they are derived from the manifest and corpus.

## IPFS Add/Pin

The script computes CIDv1 raw SHA-256 locally for every artifact. Actual IPFS
write requires one of:

- Local `ipfs` CLI in `PATH`.
- `MYNUMBER_IPFS_API`, for example `https://ipfs.etzhayyim.com/api/v0`, plus optional
  `MYNUMBER_IPFS_HMAC` when the proxy requires `X-etzhayyim-Ipfs-Auth`.

Example:

```bash
export MYNUMBER_IPFS_API=https://ipfs.etzhayyim.com/api/v0
export MYNUMBER_IPFS_HMAC="$(security find-generic-password -s etzhayyim.cloudflare -a IPFS_HMAC -w)"
python3 ingest/ingest_public_sources.py --ipfs
```

Cluster-local Kubo API path:

```bash
kubectl -n ipfs port-forward svc/kubo 5001:5001
MYNUMBER_IPFS_API=http://127.0.0.1:5001/api/v0 \
  python3 ingest/ingest_public_sources.py --ipfs
```

The public `https://ipfs.etzhayyim.com/api/v0` proxy can be blocked by Cloudflare
security policy from local automation. Prefer port-forward for operator runs.
