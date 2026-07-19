# Public Specification Basis

This design is based only on public material available on 2026-04-26.

## Current Public Documents

The Digital Agency common-function page says the latest local-government common
function standard specification link set was updated on 2026-02-27, and the page
itself was last updated on 2026-03-25. The current public set includes:

- `地方公共団体情報システム共通機能標準仕様書` 第2.7版.
- Functional requirements 第2.7版.
- Item definitions for application management, non-resident address-number
  management, intra-organization integrated address, integrated collection, and
  integrated delinquency.
- Non-resident address basic-information inquiry API 第2.5版.
- Non-resident address-number assignment API 第2.4版.
- Detailed file-exchange technical specification 第2.6版.
- OAuth 2.0 token issue and introspection APIs 第1.3版.
- OAuth 2.0 token revocation API 第1.2版.

The Myna Portal API public site describes public API categories for:

- Self-information retrieval.
- Medical-insurance information retrieval.
- PMH information linkage.
- Notice and private-delivery information retrieval.
- User registration.
- Private-delivery connection.
- Attribute-linkage settings.
- Electronic applications.
- Corporate establishment, social-insurance, tax, and residence procedures.

The Local Authentication Platform page describes JPKI use by municipalities on
LGWAN-connected and My Number-use administrative networks, with certificate
validity checks through OCSP and CRL-style approaches.

## Design Translation

The public sources expose enough to model process boundaries, controls, and data
contracts, but not enough to implement a production-compatible government
connector. Therefore this project creates:

- BPMN process contracts for identity proofing, address resolution,
  information-request brokering, self-disclosure, and API consent.
- A Python worker with typed, mockable task handlers and an append-only audit
  store.
- Adapter stubs for JPKI, Myna Portal API, local-government common-function
  APIs, and the information-provision network.

## Non-Goals

- No real Individual Number collection or generation.
- No private interface reverse engineering.
- No attempt to bypass official API onboarding, NDA, GCAS, Digital PMO, or
  agency approval processes.
- No claim of legal compliance without a separate legal and security review.

