/**
 * open-jpn-mynumber kotoba — public My Number reference-document catalog:
 * source + document.
 *
 * Per ADR-2606011400 (Consensys pattern) + ADR-2605172400 (3-axis OR-test).
 *
 * AXIS NOTE: (a) axis-clean PUBLIC open-data. Despite the name, this is a
 * reference corpus of PUBLIC My Number system policy/spec/API documents
 * published by the Digital Agency / Myna Portal / 自治体 (data.go.jp, gov web
 * pages). Tranche-F-judged etzhayyim: no citizen PII, no fiduciary, no commerce
 * (only public links ingested; NDA/application-gated specs excluded). External
 * authority = the government publisher (source URL). The LangGraph ingest /
 * corpus-build (gov-source fetch + extraction) is COMPUTE and stays etzhayyim; this
 * package is the public document catalog.
 *
 * AT-Lexicon: no float. Plain string/enum fields only (no numeric metrics).
 *
 * Identity hierarchy:
 *   did:web:open-jpn-mynumber.etzhayyim.com                  — controller
 *   did:web:open-jpn-mynumber.etzhayyim.com:src:{sourceId}   — a publisher source
 *   did:web:open-jpn-mynumber.etzhayyim.com:doc:{docId}      — a document
 */

export const OJM_DID_PREFIX = "did:web:open-jpn-mynumber.etzhayyim.com:" as const;

export const SOURCE_COLLECTION = "com.etzhayyim.apps.openJpnMynumber.source";
export const DOCUMENT_COLLECTION = "com.etzhayyim.apps.openJpnMynumber.document";

// ─── Enums ──────────────────────────────────────────────────────────

export type DocFormat = "html" | "pdf" | "xlsx" | "docx" | "csv" | "json" | "zip" | "other";
export type DocCategory = "policy" | "spec" | "api" | "form" | "guideline" | "faq" | "other";

export const DOC_FORMATS: ReadonlySet<string> = new Set(["html", "pdf", "xlsx", "docx", "csv", "json", "zip", "other"]);
export const DOC_CATEGORIES: ReadonlySet<string> = new Set([
  "policy",
  "spec",
  "api",
  "form",
  "guideline",
  "faq",
  "other",
]);

// ─── Source (publisher seed) ────────────────────────────────────────

export interface SourceRecord {
  did: string;
  sourceId: string;
  url: string;
  publisher: string;
  licenseNote?: string;
  createdAt: string;
}
export interface SourceView extends SourceRecord {
  sourceUri: string;
}
export interface RegisterSourceInput {
  sourceId: string;
  url: string;
  publisher: string;
  licenseNote?: string;
}
export interface RegisterSourceOutput {
  status: "registered" | "alreadyExists" | "rejected";
  sourceUri?: string;
  did?: string;
  sourceId?: string;
  error?: string;
}
export interface ListSourcesInput {
  publisher?: string;
  q?: string;
  limit?: number;
  cursor?: string;
}
export interface ListSourcesOutput {
  items: SourceView[];
  cursor?: string;
  total: number;
}

// ─── Document (FK→source) ───────────────────────────────────────────

export interface DocumentRecord {
  did: string;
  docId: string;
  /** FK → source. */
  sourceId: string;
  title: string;
  url: string;
  format: DocFormat;
  category: DocCategory;
  publishedDate?: string;
  summary?: string;
  tags?: string[];
  createdAt: string;
}
export interface DocumentView extends DocumentRecord {
  documentUri: string;
}
export interface IngestDocumentInput {
  docId: string;
  sourceId: string;
  title: string;
  url: string;
  format: DocFormat;
  category: DocCategory;
  publishedDate?: string;
  summary?: string;
  tags?: string[];
}
export interface IngestDocumentOutput {
  status: "ingested" | "alreadyExists" | "rejected" | "sourceNotFound";
  documentUri?: string;
  did?: string;
  docId?: string;
  error?: string;
}
export interface GetDocumentInput {
  docId: string;
}
export interface GetDocumentOutput {
  document?: DocumentView;
  error?: string;
}
export interface ListDocumentsInput {
  sourceId?: string;
  category?: DocCategory;
  format?: DocFormat;
  tag?: string;
  /** App-layer substring search over title + summary. */
  q?: string;
  limit?: number;
  cursor?: string;
}
export interface ListDocumentsOutput {
  items: DocumentView[];
  cursor?: string;
  total: number;
}

// ─── Coverage ───────────────────────────────────────────────────────

export interface CoverageInput {
  maxScan?: number;
}
export interface CoverageOutput {
  sourceCount?: number;
  documentCount?: number;
  documentsByCategory?: Record<string, number>;
  documentsByFormat?: Record<string, number>;
  truncated?: boolean;
  error?: string;
}

// ─── Helpers ────────────────────────────────────────────────────────

export function sourceDidFor(id: string): string {
  return `${OJM_DID_PREFIX}src:${id.toLowerCase()}`;
}
export function sourceRkey(id: string): string {
  return `src-${id.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`;
}
export function documentDidFor(id: string): string {
  return `${OJM_DID_PREFIX}doc:${id.toLowerCase()}`;
}
export function documentRkey(id: string): string {
  return `doc-${id.toLowerCase().replace(/[^a-z0-9]+/g, "-")}`;
}
