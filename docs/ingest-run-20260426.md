# Public Ingest Run 2026-04-26

## Result

- Source URLs discovered: 240
- Original artifacts fetched and IPFS-pinned: 236
- PDF original artifacts: 17
- PDF page WebP derivatives generated and IPFS-pinned: 696
- Original bytes: 116,684,823
- WebP bytes: 79,928,306

## Media Types

- `text/html`: 45
- `application/pdf`: 17
- `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`: 125
- `application/vnd.openxmlformats-officedocument.wordprocessingml.document`: 36
- `application/zip`: 13

## IPFS Path

The public `https://ipfs.etzhayyim.com/api/v0` path returned Cloudflare `HTTP 403:
error code 1010` from local automation. The successful run used:

```bash
kubectl -n ipfs port-forward svc/kubo 5001:5001
MYNUMBER_IPFS_API=http://127.0.0.1:5001/api/v0 \
  python3 ingest/ingest_public_sources.py --ipfs
```

## Representative CIDs

- Digital Agency common-function specification PDF, 2026-02-27:
  `bafybeie72vmy7sugdptvxfkgxx2i3gbpcjt6ymmwsy7fjmty7adiml6bb4`
- Same PDF WebP page derivatives: 59 pages, each listed in
  `data/ingest/manifest.json`.
- Myna Portal API employment-insurance procedure notice PDF:
  `bafybeidx6jyj3qq2u3bgqazw2bwuypvlkm24yew77ngczofmbtpu2mjqlm`
- Myna Portal API terms PDF:
  `bafybeigas7jz3fb5ts3zoot4hif25r5ghfo3llsycjqmewrgvvtszjp2ye`

## Failures

Four Myna Portal URLs returned redirect-loop HTTP 302 errors and were recorded
as failures in the manifest:

- `https://myna.go.jp/html/hokenshoriyou_top.html`
- `https://myna.go.jp/html/passport_information.html`
- `https://myna.go.jp/html/pension_qualification.html`
- `https://myna.go.jp/`

## Manifest

The full operational manifest is intentionally ignored by git because it points
to local blob paths and can be regenerated:

```text
60-apps/etzhayyim-project-open-jpn-mynumber/data/ingest/manifest.json
```

