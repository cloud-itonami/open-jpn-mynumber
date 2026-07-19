import { describe, it, expect, beforeEach } from "vitest";
import { MockEtzhayyim } from "@etzhayyim/sdk-mock";
import {
  registerSource,
  listSources,
  ingestDocument,
  getDocument,
  listDocuments,
  coverage,
} from "../src/index.js";

const DA = "https://www.digital.go.jp/policies/mynumber";

describe("open-jpn-mynumber kotoba", () => {
  let e: any;
  beforeEach(() => {
    e = new MockEtzhayyim({ did: "did:web:open-jpn-mynumber.etzhayyim.com" });
  });

  describe("publisher sources", () => {
    it("registers sources, lists, searches", async () => {
      expect((await registerSource(e, { sourceId: "digital-agency-mynumber", url: DA, publisher: "Digital Agency", licenseNote: "Public government web page." })).status).toBe("registered");
      expect((await registerSource(e, { sourceId: "digital-agency-mynumber", url: DA, publisher: "Digital Agency" })).status).toBe("alreadyExists");
      await registerSource(e, { sourceId: "myna-portal-api", url: "https://myna.go.jp/html/api/index.html", publisher: "Myna Portal" });
      expect((await listSources(e, { publisher: "Digital Agency" })).total).toBe(1);
      expect((await listSources(e, { q: "myna" })).total).toBe(1);
    });
  });

  describe("documents FK→source", () => {
    beforeEach(async () => {
      await registerSource(e, { sourceId: "da", url: DA, publisher: "Digital Agency" });
    });
    it("ingests documents (FK→source, format/category validated), reads, filters", async () => {
      expect((await ingestDocument(e, { docId: "d-1", sourceId: "da", title: "共通機能仕様", url: "https://x/spec.pdf", format: "pdf", category: "spec", publishedDate: "2026-04-01", tags: ["common-feature"] })).status).toBe("ingested");
      expect((await getDocument(e, { docId: "d-1" })).document?.category).toBe("spec");
      expect((await ingestDocument(e, { docId: "d-X", sourceId: "da", title: "x", url: "u", format: "wav" as any, category: "spec" })).status).toBe("rejected"); // format
      expect((await ingestDocument(e, { docId: "d-Y", sourceId: "da", title: "y", url: "u", format: "pdf", category: "manga" as any })).status).toBe("rejected"); // category
      expect((await ingestDocument(e, { docId: "d-G", sourceId: "ghost", title: "g", url: "u", format: "pdf", category: "spec" })).status).toBe("sourceNotFound");
      await ingestDocument(e, { docId: "d-2", sourceId: "da", title: "API一覧", url: "https://x/api.html", format: "html", category: "api" });
      expect((await listDocuments(e, { category: "spec" })).total).toBe(1);
      expect((await listDocuments(e, { format: "html" })).total).toBe(1);
      expect((await listDocuments(e, { q: "共通" })).total).toBe(1);
    });
    it("coverage rolls up by category / format", async () => {
      await ingestDocument(e, { docId: "d-1", sourceId: "da", title: "t", url: "u", format: "pdf", category: "policy" });
      await ingestDocument(e, { docId: "d-2", sourceId: "da", title: "t2", url: "u2", format: "xlsx", category: "form" });
      const cov = await coverage(e);
      expect(cov.sourceCount).toBe(1);
      expect(cov.documentCount).toBe(2);
      expect(cov.documentsByCategory?.policy).toBe(1);
      expect(cov.documentsByFormat?.xlsx).toBe(1);
    });
  });
});
