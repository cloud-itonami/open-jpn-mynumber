# open-jpn-mynumber

**A reference architecture for Japan's My Number platform boundary, modelled
from public specifications only.** It is a set of executable process contracts —
BPMN processes, a DMN decision table, DoDAF views, and a LangGraph server that
runs them against mock adapters — plus an ingest pipeline that collects the
public government documents the model is derived from.

**It is not a government system.** It holds no Individual Numbers, no citizen
PII, no J-LIS / Digital PMO / GCAS material, and no production connector. Every
external adapter (JPKI, Myna Portal, information-provision network,
local-government common functions) is a mock, and asking for `real` adapter mode
raises rather than silently degrading.

Start with **[`docs/operator-quickstart.md`](docs/operator-quickstart.md)** — a
verified path from clone to a running server answering a consent-gated
information request.

## What this repo is for

The public record — Digital Agency's 共通機能標準仕様書, the Myna Portal API
index, the Local Authentication Platform pages — is detailed enough to state
*where the boundaries are* (what consent must exist before data moves, what must
be audited, what must never be stored inline) but not enough to build a
production-compatible connector. This repo takes the first half seriously and
refuses the second: it encodes the boundaries as runnable contracts so they can
be reviewed, tested, and argued with, and it stops at the adapter line.

## Layout

| Path | What lives there | Runtime |
| --- | --- | --- |
| `lg-clj/` | **The executable core.** 17 task handlers + 18 StateGraphs, HTTP server, in-memory store, append-only audit ledger | Clojure on `bb` |
| `bpmn/` | 9 BPMN process contracts (identity proofing, consent, brokering, disclosure, OAuth, file exchange) | orchestration contract |
| `dmn/` | `disclosure-risk.dmn` — disclosure risk decision table | DMN |
| `dodaf/` | AV-1 / OV-1 / OV-5b / SV-1 architecture views | JSON |
| `forms/` | Human-task form schemas for the review steps | JSON |
| `ingest/` | Public-source collection → corpus build → search | Python 3 |
| `kotoba/` | Document-catalog registry on AT PDS (source + document records) | TypeScript |
| `worker/` | Legacy Python worker + JSON-LD descriptor | superseded by `lg-clj/` |
| `docs/` | Architecture, public-spec basis, dated run records | — |

`lg-clj/` supersedes the Python twin under `worker/python/` (ADR-2606280030);
the Clojure port is canonical and is the one under test.

## The invariants worth knowing

These are enforced in `lg-clj/`, not just described:

- **Never store a raw Individual Number.** The store holds a `person_ref` and
  agency-scoped alias hashes.
- **No transfer without consent.** `brokerInformationRequest` returns
  `{approved: false, reason: "missing-consent"}` unless a `consentId` is present
  — and audits the denial.
- **Purpose binding.** Every task requires a `purpose_code`.
- **No inline special PII.** Approved requests return a `response_ref` and a
  classification; `data_inline` is `null`.
- **Dual audit.** Every task appends a service event; disclosure additionally
  produces citizen-visible history.
- **Mock-by-default, explicitly.** `ensure-mock-mode` throws on `real`, and the
  server refuses to start without an explicitly passed HTTP capability.

The quickstart walks you through observing the consent gate and the audit
ledger directly.

## Testing

```bash
cd lg-clj && bb test
```

29 tests / 102 assertions, covering graph topology, dispatch, the consent gate,
the OAuth issue→introspect→revoke roundtrip, file-manifest validation, the
medical-info consent gate, and camelCase→snake_case input normalization.

## Public sources

Derived only from material public as of 2026-04-26 — Digital Agency
`地方公共団体情報システム共通機能標準仕様書` 第2.7版 and its API annexes, the
Myna Portal public API index, and the Local Authentication Platform pages. The
full list with version numbers is in [`docs/spec-basis.md`](docs/spec-basis.md);
the fetchable seed set is `ingest/sources.json`.

## Boundaries this repo will not cross

No real Individual Number collection or generation. No reverse engineering of
private interfaces. No bypassing official API onboarding, NDA, GCAS, Digital
PMO, or agency approval. No claim of legal compliance without separate legal and
security review. See `CLAUDE.md` for the substrate and residency posture.
