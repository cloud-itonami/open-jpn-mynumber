# Coverage Map 2026-04-26

## Result

- Covered topics: 10
- Partial topics: 0
- Gap topics: 0
- Not observed topics: 0

Coverage was generated from:

- Corpus: `data/ingest/corpus.jsonl`
- Topic definitions: `coverage/topics.json`
- BPMN inventory: `bpmn/*.bpmn`
- Worker inventory: `worker/python/open_jpn_mynumber_worker.py`

Derived outputs are ignored by git:

- `coverage/coverage.json`
- `coverage/coverage.md`

## Covered

- JPKI identity proofing.
- Agency-scoped person alias registration.
- Non-resident address-number lookup and assignment.
- Inter-agency information request brokering.
- Self-information and provision-history disclosure.
- Myna Portal API consent session.
- OAuth token issue, introspection, and revocation.
- Local-government common-function file exchange.
- Myna Portal electronic application APIs.
- Medical insurance, medical exam, and PMH information APIs.

## Gaps

No observed coverage gaps remain in `coverage/topics.json`.

## Commands

```bash
python3 ingest/build_corpus.py build
python3 coverage/build_coverage.py
```

## Implementation Slices Completed

- OAuth token lifecycle.
- Local-government common-function file exchange.
- Electronic application submit/status.
- Medical/PMH information request/status.
