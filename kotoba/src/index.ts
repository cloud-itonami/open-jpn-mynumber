/**
 * open-jpn-mynumber kotoba — barrel.
 *
 * Per ADR-2606011400. Public My Number reference-document catalog (source +
 * document) on the etzhayyim substrate (AT PDS records; no RW).
 *
 *   source   : registerSource / listSources (publisher seed pages)
 *   document : ingestDocument (FK→source, format/category enums) / getDocument /
 *              listDocuments (title+summary search; category/format/tag filters)
 *   coverage
 *
 * (a) PUBLIC open-data: gov-published My Number policy/spec/API docs (Digital
 * Agency / Myna Portal / 自治体, external authority via source URL). No PII, no
 * commerce. The LangGraph ingest / corpus-build compute stays etzhayyim.
 */

export * from "./types.js";
export {
  registerSource,
  listSources,
  ingestDocument,
  getDocument,
  listDocuments,
  coverage,
} from "./registry.js";
