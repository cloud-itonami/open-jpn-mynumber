# Operator quickstart

From a fresh clone to a running server answering a consent-gated information
request. **Every command here was executed on 2026-08-15**; the outputs shown are
real, not illustrative. Where something does not work on a current machine, this
document says so rather than leaving you to discover it.

Verified on macOS 15 (arm64), babashka 1.12.218, Python 3.14.5, node 26.3.0 /
npm 11.16.0.

## 1. Run the test suite (2 min, no network after deps resolve)

This is the fastest proof the checkout is intact.

```bash
cd lg-clj
bb test
```

```
Testing lg-open-jpn-mynumber.smoke-test

Ran 29 tests containing 102 assertions.
0 failures, 0 errors.
```

The first run resolves `langgraph-clj` and `langchain-clj` from GitHub into
`~/.gitlibs` and takes noticeably longer; later runs are fast. If you are
offline and have never resolved them, this step is the one that will fail.

## 2. Start the server

```bash
cd lg-clj
bb serve 18123        # or: PORT=18123 bb serve
```

```
lg-open-jpn-mynumber clj server on :18123 — 18 graphs
```

18 graphs = the 17 task graphs plus `health`. The server runs in mock adapter
mode by default. `OPEN_JPN_MYNUMBER_ADAPTER_MODE=real` does **not** enable a real
connector — it raises `real adapter mode is not implemented`, deliberately.

Startup takes a few seconds. Poll rather than guessing a sleep:

```bash
until curl -sf -o /dev/null http://127.0.0.1:18123/ok; do sleep 1; done
```

Check it is alive:

```bash
curl -s http://127.0.0.1:18123/ok       # {"ok":true}
curl -s http://127.0.0.1:18123/health   # {"status":"ok","service":"lg-open-jpn-mynumber"}
```

## 3. Exercise the consent gate

This is the invariant the whole repo exists to state: **no information transfer
without recorded consent.** Three calls demonstrate it. `B` is the base URL.

```bash
B=http://127.0.0.1:18123
```

**Verify a certificate** (note camelCase in, snake_case out — the server
normalizes):

```bash
curl -s -X POST $B/xrpc/com.etzhayyim.apps.openJpnMynumber.verifyJpki \
  -H 'content-type: application/json' \
  -d '{"personRef":"did:example:p1","purposeCode":"PC-DEMO"}'
```

```json
{"output":{"person_ref":"did:example:p1",
 "certificate_fingerprint":"ec864fe99b539704b8872ac591067ef22d836a8d942087f2dba274b301ebe6e5",
 "certificate_status":"valid","verification_method":"mock-ocsp",
 "checked_at":"2026-08-15T02:28:14Z",
 "audit_event_vertex_id":"vertex_evt_CuVK5m2nEoFA7bbV",
 "audited_at":"2026-08-15T02:28:14Z"},"elapsed_s":0.001}
```

Note `verification_method: "mock-ocsp"` — the mock announces itself in the
response rather than looking like a real check.

**Request information without consent — expect refusal:**

```bash
curl -s -X POST $B/xrpc/com.etzhayyim.apps.openJpnMynumber.brokerInformationRequest \
  -H 'content-type: application/json' \
  -d '{"personRef":"did:example:p1","requesterAgency":"AGENCY-A",
       "holderAgency":"AGENCY-B","purposeCode":"PC-DEMO","datasetCode":"DS-RESIDENCE"}'
```

```json
{"output":{"approved":false,"reason":"missing-consent",
 "audit_event_vertex_id":"vertex_evt_Z0Gbj0oYpLfVsnrV",
 "audited_at":"2026-08-15T02:28:14Z"},"elapsed_s":0.0}
```

The refusal is itself audited — a denied request leaves a record.

**Now with a consent id — expect approval:**

```bash
curl -s -X POST $B/xrpc/com.etzhayyim.apps.openJpnMynumber.brokerInformationRequest \
  -H 'content-type: application/json' \
  -d '{"personRef":"did:example:p1","requesterAgency":"AGENCY-A",
       "holderAgency":"AGENCY-B","purposeCode":"PC-DEMO",
       "datasetCode":"DS-RESIDENCE","consentId":"cns_demo"}'
```

```json
{"output":{"approved":true,"response_ref":"payload_f2090a4797222199fe4a",
 "classification":"special-personal-information","data_inline":null,
 "audit_event_vertex_id":"vertex_evt_WYrK9fKvLHdAkU5f",
 "audited_at":"2026-08-15T02:28:14Z"},"elapsed_s":0.0}
```

**`data_inline` is `null` on purpose.** An approved request yields a *reference*
and a classification, never the special personal information itself. If you ever
see a payload inline here, that is a defect, not a feature.

### The other two dispatch paths

```bash
# Named graph rather than NSID
curl -s -X POST $B/runs -H 'content-type: application/json' \
  -d '{"assistant_id":"health","input":{}}'
# {"output":{"status":"ok","service":"lg-open-jpn-mynumber"},"elapsed_s":0.0}

# Unmapped NSID -> 501 Not Implemented (not 404 — the route exists, the method doesn't)
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  $B/xrpc/com.etzhayyim.apps.openJpnMynumber.nope \
  -H 'content-type: application/json' -d '{}'
# 501
```

State is in-memory. Restarting the server clears the store and the audit ledger.

## 4. Ingest public sources (network; hits digital.go.jp)

The model is derived from public government documents; this pipeline is how they
are collected. It fetches **public web pages only** — nothing NDA-gated.

```bash
python3 -m venv .venv
.venv/bin/pip install -r ingest/requirements.txt      # defusedxml
.venv/bin/python ingest/ingest_public_sources.py --limit 2 --max-pdf-pages 1
```

```json
{"manifest": "data/ingest/manifest.json", "source_count": 2,
 "artifact_count": 2, "failure_count": 0, "ipfs_available": true}
```

Keep `--limit` small the first time — the unbounded seed set walks linked PDFs
and XLSX annexes and is much slower. Each artifact is recorded with its source
URL, SHA-256, and a locally computed CIDv1 (`bafkrei…`); `"ipfs": null` means
nothing was pinned, which is correct unless you passed `--ipfs`.

Then build and query the text corpus:

```bash
.venv/bin/python ingest/build_corpus.py build
# {"documents": 2, "chunks": 8, "failures": [], "jsonl": "data/ingest/corpus.jsonl"}

.venv/bin/python ingest/build_corpus.py search '共通機能'
# [{"chunk_id": "0001-7391e7d24ae2c267:0000", ...
#   "source_url": "https://www.digital.go.jp/policies/local_governments/common-feature-specification", ...}]
```

**A `[]` result is usually a small corpus, not a broken index.** With
`--limit 2` you have only the two seed landing pages, so a term that lives in a
linked PDF annex — `個人番号` was empty in this run — legitimately has nothing to
match. Raise `--limit` before concluding the search is broken.

Everything under `data/ingest/` is gitignored; it is a rebuildable derivative,
not repo content.

## Known blockers

Two steps in this repo do not currently work. Both are recorded here so you stop
at the fence instead of climbing it.

**`kotoba/` does not install on npm ≥ 11.** Its `@etzhayyim/sdk` git dependencies
need a prepare script, which project-scoped installs now refuse:

```
npm error code EALLOWSCRIPTS
npm error --allow-scripts is not allowed in project-scoped installs.
Add the entries to the "allowScripts" field in package.json, or to .npmrc, instead.
```

The fix is to add an `allowScripts` entry for those two git deps in
`kotoba/package.json`, which is a change to the package's trust posture and so
is left for a deliberate decision rather than done in passing. Until then
`kotoba/test/open-jpn-mynumber.test.ts` cannot run, and the TypeScript registry
is unverified on this machine. **`lg-clj/` is unaffected** — it is the
executable core and is fully tested.

**`coverage/build_coverage.py` is missing.** `ingest/README.md` documents

```bash
python3 coverage/build_coverage.py
```

but no `coverage/` directory exists in this repo. `.gitignore` carries
`!coverage/build_coverage.py` and `!coverage/topics.json` — explicit
un-ignores — so the file was meant to be committed and was dropped when this app
was extracted from `etzhayyim/root` (`009af19`, 2026-07-20). The coverage map in
`docs/coverage-map-20260426.md` was produced by that missing script and cannot
currently be regenerated.

## Where to go next

- `docs/architecture.md` — components, data model, security controls
- `docs/spec-basis.md` — which public documents, at which version, and what they
  do *not* license us to build
- `lg-clj/src/lg_open_jpn_mynumber/tasks.cljc` — all 17 handlers; the consent and
  purpose checks are legible in one file
- `lg-clj/test/lg_open_jpn_mynumber/smoke_test.cljc` — the invariants above,
  written as assertions
- `bpmn/` — the process contracts, viewable in any BPMN 2.0 editor
