/**
 * open-jpn-mynumber kotoba — source + document registries + coverage.
 * AT PDS records (no RW). Documents FK→source. Public gov-published My Number
 * reference docs only (no PII); ingest/corpus-build compute stays etzhayyim.
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  DOCUMENT_COLLECTION,
  DOC_CATEGORIES,
  DOC_FORMATS,
  SOURCE_COLLECTION,
  documentDidFor,
  documentRkey,
  sourceDidFor,
  sourceRkey,
  type CoverageInput,
  type CoverageOutput,
  type DocumentRecord,
  type DocumentView,
  type GetDocumentInput,
  type GetDocumentOutput,
  type IngestDocumentInput,
  type IngestDocumentOutput,
  type ListDocumentsInput,
  type ListDocumentsOutput,
  type ListSourcesInput,
  type ListSourcesOutput,
  type RegisterSourceInput,
  type RegisterSourceOutput,
  type SourceRecord,
  type SourceView,
} from "./types.js";

const PAGE_LIMIT = 100;
const DEFAULT_MAX_SCAN = 10_000;

async function exists(e: Etzhayyim, collection: string, rkey: string): Promise<boolean> {
  const resp = await e.read({ collection, rkey }).catch(() => ({ records: [] }));
  return Boolean(resp.records[0]?.value);
}

async function scanAll<T>(e: Etzhayyim, collection: string, maxScan: number, onRow: (v: T) => void): Promise<number> {
  let cursor: string | undefined;
  let scanned = 0;
  while (scanned < maxScan) {
    const page = await e.read<T>({ collection, cursor, limit: PAGE_LIMIT });
    for (const r of page.records) {
      if (scanned >= maxScan) break;
      onRow(r.value);
      scanned += 1;
    }
    if (scanned >= maxScan || !page.cursor || page.records.length < PAGE_LIMIT) break;
    cursor = page.cursor;
  }
  return scanned;
}

// ─── Source ─────────────────────────────────────────────────────────

export async function registerSource(e: Etzhayyim, input: RegisterSourceInput): Promise<RegisterSourceOutput> {
  if (!input.sourceId || !input.url || !input.publisher) return { status: "rejected", error: "missingRequiredFields" };
  const rkey = sourceRkey(input.sourceId);
  const existing = await e.read<SourceRecord>({ collection: SOURCE_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", sourceUri: existing.records[0].uri, did: existing.records[0].value.did, sourceId: input.sourceId };
  }
  const did = sourceDidFor(input.sourceId);
  const record: SourceRecord = {
    did,
    sourceId: input.sourceId,
    url: input.url,
    publisher: input.publisher,
    licenseNote: input.licenseNote,
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: SOURCE_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "registered", sourceUri: receipt.uri, did, sourceId: input.sourceId };
}

export async function listSources(e: Etzhayyim, input: ListSourcesInput = {}): Promise<ListSourcesOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<SourceRecord>({ collection: SOURCE_COLLECTION, cursor: input.cursor, limit });
  const q = input.q?.toLowerCase();
  const items: SourceView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.publisher && v.publisher !== input.publisher) return false;
      if (q && !v.publisher.toLowerCase().includes(q) && !v.sourceId.toLowerCase().includes(q)) return false;
      return true;
    })
    .map((r) => ({ ...r.value, sourceUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Document ───────────────────────────────────────────────────────

export async function ingestDocument(e: Etzhayyim, input: IngestDocumentInput): Promise<IngestDocumentOutput> {
  if (!input.docId || !input.sourceId || !input.title || !input.url) return { status: "rejected", error: "missingRequiredFields" };
  if (!DOC_FORMATS.has(input.format)) return { status: "rejected", error: "invalidFormat" };
  if (!DOC_CATEGORIES.has(input.category)) return { status: "rejected", error: "invalidCategory" };
  if (!(await exists(e, SOURCE_COLLECTION, sourceRkey(input.sourceId)))) {
    return { status: "sourceNotFound", error: `sourceNotFound:${input.sourceId}` };
  }
  const rkey = documentRkey(input.docId);
  const existing = await e.read<DocumentRecord>({ collection: DOCUMENT_COLLECTION, rkey }).catch(() => ({ records: [] }));
  if (existing.records[0]?.value) {
    return { status: "alreadyExists", documentUri: existing.records[0].uri, did: existing.records[0].value.did, docId: input.docId };
  }
  const did = documentDidFor(input.docId);
  const record: DocumentRecord = {
    did,
    docId: input.docId,
    sourceId: input.sourceId,
    title: input.title,
    url: input.url,
    format: input.format,
    category: input.category,
    publishedDate: input.publishedDate,
    summary: input.summary,
    tags: input.tags,
    createdAt: new Date().toISOString(),
  };
  const receipt = await e.write({ collection: DOCUMENT_COLLECTION, record: record as unknown as Record<string, unknown>, rkey });
  return { status: "ingested", documentUri: receipt.uri, did, docId: input.docId };
}

export async function getDocument(e: Etzhayyim, input: GetDocumentInput): Promise<GetDocumentOutput> {
  if (!input.docId) return { error: "invalidDocId" };
  const resp = await e.read<DocumentRecord>({ collection: DOCUMENT_COLLECTION, rkey: documentRkey(input.docId) }).catch(() => ({ records: [] }));
  const r = resp.records[0];
  if (!r) return { error: "notFound" };
  return { document: { ...r.value, documentUri: r.uri } };
}

export async function listDocuments(e: Etzhayyim, input: ListDocumentsInput = {}): Promise<ListDocumentsOutput> {
  const limit = Math.min(input.limit ?? 50, 200);
  const resp = await e.read<DocumentRecord>({ collection: DOCUMENT_COLLECTION, cursor: input.cursor, limit });
  const q = input.q?.toLowerCase();
  const items: DocumentView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.sourceId && v.sourceId !== input.sourceId) return false;
      if (input.category && v.category !== input.category) return false;
      if (input.format && v.format !== input.format) return false;
      if (input.tag && !(v.tags ?? []).includes(input.tag)) return false;
      if (q) {
        const hay = [v.title, v.summary ?? ""].join(" ").toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    })
    .map((r) => ({ ...r.value, documentUri: r.uri }));
  return { items, cursor: resp.cursor, total: items.length };
}

// ─── Coverage ───────────────────────────────────────────────────────

export async function coverage(e: Etzhayyim, input: CoverageInput = {}): Promise<CoverageOutput> {
  const maxScan = Math.min(input.maxScan ?? DEFAULT_MAX_SCAN, DEFAULT_MAX_SCAN);
  const documentsByCategory: Record<string, number> = {};
  const documentsByFormat: Record<string, number> = {};
  const sourceCount = await scanAll<SourceRecord>(e, SOURCE_COLLECTION, maxScan, () => {});
  const documentCount = await scanAll<DocumentRecord>(e, DOCUMENT_COLLECTION, maxScan, (v) => {
    documentsByCategory[v.category] = (documentsByCategory[v.category] ?? 0) + 1;
    documentsByFormat[v.format] = (documentsByFormat[v.format] ?? 0) + 1;
  });
  return {
    sourceCount,
    documentCount,
    documentsByCategory,
    documentsByFormat,
    truncated: sourceCount >= maxScan || documentCount >= maxScan,
  };
}
