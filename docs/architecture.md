# Architecture

## Components

- `open-jpn-mynumber-process-api`: starts BPMN instances and exposes task-facing
  endpoints. In this scaffold it is represented by dispatcher-compatible NSIDs.
- `open-jpn-mynumber-langserver-worker`: Python worker for service tasks.
- `identity-vault`: encrypted store for `person_ref`, agency aliases, consent
  receipts, certificate fingerprints, and payload references.
- `audit-ledger`: append-only operational and disclosure history.
- `adapter-jpki`: JPKI certificate verification boundary.
- `adapter-myna-portal`: Myna Portal API consent and data retrieval boundary.
- `adapter-local-common`: local-government common-function APIs, including
  non-resident address and OAuth/file-exchange boundaries.
- `adapter-info-network`: information-provision-network request/response
  boundary.

## Data Model

| Entity | Purpose |
| --- | --- |
| `person_ref` | Internal opaque subject id. Never store raw Individual Number. |
| `agency_alias` | Agency-scoped identifier linked to `person_ref`. |
| `certificate_assertion` | JPKI certificate fingerprint, status, method, and checked time. |
| `consent_receipt` | User consent scope, purpose, requester, expiry, and revocation state. |
| `information_request` | Legal-purpose request from a requester to a holder agency. |
| `information_response` | Payload reference and data classification, not inline special PII. |
| `provision_history` | User-facing and operator-facing record of data transfer. |

## Security Controls

- Purpose binding: every task requires `purpose_code`.
- Least disclosure: each process asks for `dataset_code` and `scope`.
- Data minimization: workers persist payload references, hashes, and metadata.
- Dual audit: append service events plus citizen-visible disclosure events.
- Adapter isolation: external calls are behind allowlisted adapter types.
- Manual review: high-risk request classes route through user tasks before
  release.
