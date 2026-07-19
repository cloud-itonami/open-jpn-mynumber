# Graph Schema 2026-04-26

## Rule

Durable domain state uses `vertex_` and `edge_` tables. `mv_` is allowed for
derived read models and query projections only.

## Vertices

- `vertex_open_jpn_mynumber_audit_event`
- `vertex_open_jpn_mynumber_consent_receipt`
- `vertex_open_jpn_mynumber_agency_alias`
- `vertex_open_jpn_mynumber_oauth_token`
- `vertex_open_jpn_mynumber_file_manifest`
- `vertex_open_jpn_mynumber_file_transfer`
- `vertex_open_jpn_mynumber_electronic_application`
- `vertex_open_jpn_mynumber_medical_info_request`

## Edges

- `edge_open_jpn_mynumber_event_subject`
- `edge_open_jpn_mynumber_transfer_manifest`
- `edge_open_jpn_mynumber_request_consent`

## Materialized-View Projections

RisingWave deployment uses Kysely-managed materialized views for projections.

- `mv_open_jpn_mynumber_oauth_token_status`
- `mv_open_jpn_mynumber_file_transfer_status`
- `mv_open_jpn_mynumber_electronic_application_status`
- `mv_open_jpn_mynumber_medical_info_status`
- `mv_open_jpn_mynumber_audit_timeline`

## Worker Policy

- Writes go to `vertex_` / `edge_`.
- Status reads go through `mv_`.
- SQLite fallback is retired; missing `RW_URL` / `DATABASE_URL` is a runtime
  configuration error.
- Worker responses expose vertex ids such as `audit_event_vertex_id`,
  `token_ref`, `transfer_id`, `file_manifest_vertex_id`, `application_id`,
  and `medical_request_id`.
