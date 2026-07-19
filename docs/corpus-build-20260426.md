# Corpus Build 2026-04-26

## Result

- Documents indexed: 236
- Text chunks: 5,148
- Extraction failures: 0
- JSONL corpus: `data/ingest/corpus.jsonl`
- JSONL size: 20 MiB

## Extractors

- HTML: stdlib `html.parser`, script/style stripping.
- PDF: `pdftotext -layout`.
- DOCX: zipped Office XML text extraction.
- XLSX: shared strings and worksheet XML text extraction.
- ZIP: file listing extraction.

## Search Smoke Tests

```bash
python3 ingest/build_corpus.py search '個人番号' --limit 5
python3 ingest/build_corpus.py search 'OAuth' --limit 5
```

Both returned source URL, local path, media type, CID, chunk id, and snippet.
Representative `OAuth` result:

- Source:
  `https://www.digital.go.jp/assets/contents/node/basic_page/field_ref_resources/4d056a04-6eba-4109-9850-a786d3e71971/eea166d9/20260227_policies_local_governments_common_22.xlsx`
- CID: `bafkreicnyp2oomjj7zakjrrnw5huierqfu52q4m7hj3rj2wxltgyi4gwra`
- Snippet includes `[oauth]/v1/token` and OAuth2.0 access-token issue API text.

## Next Work

- Add section-aware metadata for page, sheet, and heading positions.
- Promote corpus chunks into Kysely-managed `vertex_` / `edge_` graph tables or
  expose the JSONL corpus through a service endpoint.
- Generate requirement-to-BPMN coverage maps from the corpus.
