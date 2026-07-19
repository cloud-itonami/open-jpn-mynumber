# open-jpn-mynumber.etzhayyim.com — public-spec My Number reference architecture

**Status**: Tranche F move target — judged etzhayyim per `etzhayyim/etzhayyim-root` deps `tranche-f-open-jpn-mynumber-classification-2026-05-20` (3-axis OR-test all clean: PUBLIC gov-published policy docs, no fiduciary, no citizen PII, no commerce). Project scaffold landed pre-Tranche F (2026-04-26); this entry records the Tranche F classification + corpus residency.

This is not a government production system and does not contain private J-LIS, Digital PMO, GCAS, or agency-only interface material.

## Tranche F cross-repo state (2026-05-21)

| Artifact | Vendor (`etzhayyim/etzhayyim-root`) | etzhayyim (this repo) |
|---|---|---|
| Spec (`CLAUDE.md`, project dir) | absent | **present** (this dir) |
| Worker / BPMN / DMN / forms / lg / ingest pipeline | absent | **present** (`bpmn/`, `dmn/`, `forms/`, `lg/`, `worker/`, `ingest/`) |
| Lexicons (`openJpnMynumber/health.json`) | present (1 file) → mirrored | **present** (`00-contracts/lexicons/com/etzhayyim/apps/openJpnMynumber/`) |
| Corpus blobs (923 files, ~177 MB on disk; ~280 MB before pruning) | **present** (`data/ingest/`) — kept per Option A | absent (read-fresh from gov sources) |
| `corpus.sqlite3` / `corpus.jsonl` / `manifest.json` | present | absent |

**Corpus residency = Option A (vendor RW mirror, etzhayyim worker reads fresh from gov sources).** Same architectural shape as ADR-2605202400 GTFS-RT carve-out and `public-malak-scaffold-2026-05-21`. Vendor `60-apps/etzhayyim-project-open-jpn-mynumber/data/ingest/` stays vendor-side as historical artifact.

## Substrate-boundary notes

Per `etzhayyim/root/CLAUDE.md` §"Substrate boundary":
- This project is kotoba. No `createKyselyDb` / `env.HYPERDRIVE` in any deploy from this directory.
- Public-source ingest pulls fresh from `data.go.jp` + 自治体公開 PDF/Excel/HTML on each cycle. Vendor `data/ingest/` is historical-only; not consumed by this repo's worker.
- No commerce. No PII. No `did:web:openJpnMynumber.etzhayyim.com` payments wiring.

## Scope

This project models the public functional boundary of Japan's My Number-related
platforms as an open Japanese public-sector BPMN + Python worker application:

- JPKI-based identity proofing and certificate status checks.
- Person registration and local-agency subject resolution.
- Non-resident address-number lookup and assignment.
- Inter-agency information request brokering with consent, purpose, and audit
  checks.
- Citizen self-information and information-provision-history disclosure.
- Myna Portal-style API consent session orchestration.
- File exchange and OAuth token lifecycle boundaries used by local-government
  common functions.
- Public-source ingest for government pages and public assets, with PDF pages
  rendered to WebP derivatives and artifacts prepared for IPFS add/pin.

## Public Sources Used

- Digital Agency, `地方公共団体情報システム共通機能標準仕様書`, latest public
  link set updated 2026-02-27 and page updated 2026-03-25.
- Digital Agency, Myna Portal API specification site, API list and update
  history, including 2026-04 and 2025-09 updates.
- Digital Agency, Local Authentication Platform page for JPKI use on LGWAN and
  My Number-use administrative networks.

## Hard Boundaries

- Store a `person_ref` and agency-scoped aliases, not raw My Number values.
- Use tokenized identifiers, encrypted payload references, and immutable audit
  events for all special personal information flows.
- Require explicit legal purpose, requester agency, dataset class, and retention
  policy on every information request.
- Mock JPKI, Myna Portal, information-provision-network, and local-government
  adapters until a real connector is approved and separately reviewed.
- Do not put production PII, Individual Number, certificate private keys, or
  government credentials in this repository.

## Runtime Pattern

- BPMN is the orchestration contract, deployable to LangServer/BPMN-contract-compatible
  engines.
- Python worker owns adapter calls, input normalization, policy checks, and
  audit writes.
- `ingest/ingest_public_sources.py` owns public document collection, PDF to
  WebP conversion, manifest generation, and optional IPFS writes.
- Runtime state is RisingWave-only through the Kysely graph-schema migrations;
  the worker requires `RW_URL` or `DATABASE_URL` when executing write tasks.
