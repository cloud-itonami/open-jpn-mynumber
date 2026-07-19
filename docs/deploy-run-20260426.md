# Build and Deploy Run 2026-04-26

## Build Validation

- Python compile passed for worker, ingest, corpus, coverage, and BPMN deploy
  helper scripts.
- BPMN and DMN XML validation passed with `xmllint`.
- Kubernetes client dry-run passed for the worker Deployment and BPMN deploy
  Job.
- Coverage after OAuth/file-exchange implementation:
  - covered: 8
  - partial: 0
  - gap: 2
  - not observed: 0

## Deployed Resources

Namespace: `mitama-udf`

- `deployment/open-jpn-mynumber-langserver-worker`
- `job/open-jpn-mynumber-bpmn-deploy`
- `configmap/open-jpn-mynumber-worker-source`
- `configmap/open-jpn-mynumber-zeebe-resources`
- `configmap/open-jpn-mynumber-bpmn-deploy-script`

## LangServer Resources

The deploy Job successfully deployed:

- `disclosure-risk.dmn`
- `electronic-application.bpmn`
- `file-exchange.bpmn`
- `identity-proofing.bpmn`
- `interagency-information-request.bpmn`
- `medical-pmh.bpmn`
- `myna-api-consent.bpmn`
- `nonresident-address.bpmn`
- `oauth-token-lifecycle.bpmn`
- `self-information-disclosure.bpmn`

## Runtime Smoke

Started `open_jpn_mynumber_oauth_token_lifecycle` with `operation=issue`.

Result observed in the initial worker pod-local prototype state:

- `vertex_open_jpn_mynumber_oauth_token`: 1 row
- `vertex_open_jpn_mynumber_audit_event`: 1 row
- `mv_open_jpn_mynumber_oauth_token_status`: 1 active token

## Graph DB Promotion

Follow-up deployment promoted runtime state to shared RisingWave through
Kysely-managed graph-schema migrations. SQLite fallback is retired; worker
write tasks now require `RW_URL` or `DATABASE_URL`.

- Added graph migration
  `20260427003000_vertex_open_jpn_mynumber.ts`.
- Created `vertex_open_jpn_mynumber_*` runtime vertices.
- Created `edge_open_jpn_mynumber_*` relationship tables.
- Created `mv_open_jpn_mynumber_*` read-model projections.
- Regenerated `30-graph/graph-schema/src/database.ts`.
- `pnpm db:drift` reported no drift.

RisingWave smoke via LangServer:

- `vertex_open_jpn_mynumber_oauth_token`: 1 `lg-rw-smoke` row
- `vertex_open_jpn_mynumber_audit_event`: 1 `lg-rw-smoke` row
- `mv_open_jpn_mynumber_oauth_token_status`: 1 `active` row

## Electronic Application and PMH Extension

Next-phase deployment added the remaining coverage topics:

- `electronic-application.bpmn`
- `medical-pmh.bpmn`
- `submitElectronicApplication`
- `getElectronicApplicationStatus`
- `requestMedicalInfo`
- `getMedicalInfoStatus`

Added graph migration:

- `20260427033000_open_jpn_mynumber_application_medical.ts`

Validation:

- `python3 -m py_compile`: passed
- `xmllint --noout`: passed
- `python3 coverage/build_coverage.py`: covered 10, gap 0
- `pnpm db:drift`: no drift
- `pnpm exec tsc --noEmit`: passed

RisingWave smoke via LangServer:

- `vertex_open_jpn_mynumber_electronic_application`: 1 next-phase row
- `mv_open_jpn_mynumber_electronic_application_status`: `submitted`
- `vertex_open_jpn_mynumber_medical_info_request`: 1 next-phase row
- `mv_open_jpn_mynumber_medical_info_status`: `available`

## Notes

- First BPMN deploy attempt failed because `Task_Classify` was a LangServer
  `businessRuleTask` without a `zeebe:calledDecision`. The BPMN was fixed and
  DMN deployment was added before the successful run.
- Worker no longer falls back to pod-local SQLite. Missing `RW_URL` /
  `DATABASE_URL` is a configuration error.
