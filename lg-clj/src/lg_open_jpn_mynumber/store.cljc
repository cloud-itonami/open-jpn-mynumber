(ns lg-open-jpn-mynumber.store
  "Injectable persistence seam — clj/bb port of the psycopg/RisingWave writes in
  worker/python/open_jpn_mynumber_worker.py (ADR-2606280030).

  SUBSTRATE BOUNDARY (charter / ADR-2605262130): the Python worker persists every
  vertex/edge/audit row into RisingWave via psycopg (`RW_URL`). RisingWave is a
  forbidden centralized substrate — the worker itself carries the standing
  `TODO(substrate-boundary)` + `CHARTER-VIOLATION §substrate` markers. This port
  therefore does NOT reproduce psycopg/RW; instead it defines a narrow `Store`
  protocol the handlers speak to, with two implementations:

  - `->MemStore`   — in-memory atom of {table {id row}}; the default. Lets the
                     whole graph + handler logic run & verify offline (this is the
                     faithful analogue of the worker's `mock` ADAPTER_MODE, which
                     is the only mode the scaffold implements anyway).
  - `->KotobaStore`— production seam targeting the kotoba Datom log (the repo's
                     canonical state per CLAUDE.md / ADR-2605172000: collection
                     `com.etzhayyim.apps.openJpnMynumber.*`). Left as a documented
                     pass-through over an injected `:db-api` so a human can wire it
                     at cutover WITHOUT touching the handlers or graph topology.

  The Python `mv_*` materialized-view reads (oauth/file-transfer/application/
  medical status) are computed in the HANDLERS from the base-table rows here, so
  the data semantics are preserved without a SQL view engine."
  (:require [clojure.string :as str]))

(defprotocol Store
  (put! [this table id row]
    "INSERT a row (map) into `table` (keyword) keyed by `id`. Idempotent on id.")
  (get-row [this table id]
    "SELECT the row for `id` from `table`, or nil.")
  (update-row! [this table id attrs]
    "UPDATE: merge `attrs` into the row for `id`. Returns true if the row existed
    (mirrors psycopg cursor.rowcount > 0).")
  (list-rows [this table]
    "All rows in `table` (seq of maps)."))

;; ── in-memory (default; offline-verifiable, == worker mock mode) ────────────

(defrecord MemStore [db]
  Store
  (put! [_ table id row]
    (swap! db assoc-in [table id] row)
    row)
  (get-row [_ table id]
    (get-in @db [table id]))
  (update-row! [_ table id attrs]
    (if (get-in @db [table id])
      (do (swap! db update-in [table id] merge attrs) true)
      false))
  (list-rows [_ table]
    (vec (vals (get @db table)))))

(defn ->mem-store [] (->MemStore (atom {})))

;; ── kotoba Datom-log seam (production cutover target) ───────────────────────
;;
;; `db-api` is the langchain.db `{:q :transact! :db :pull :entid}` map described
;; in CLAUDE.md (in-process `langchain.db/api` OR kotoba-server XRPC
;; `kotoba-db/kotoba-api` — same record either way). A human wires it at cutover;
;; until then the deployed runtime is the Python pod and this stays unused.

(defrecord KotobaStore [db-api graph]
  Store
  (put! [_ _table _id _row]
    (throw (ex-info "KotobaStore not wired — cutover pending (use ->MemStore)"
                    {:graph graph})))
  (get-row [_ _table _id]
    (throw (ex-info "KotobaStore not wired — cutover pending" {:graph graph})))
  (update-row! [_ _table _id _attrs]
    (throw (ex-info "KotobaStore not wired — cutover pending" {:graph graph})))
  (list-rows [_ _table]
    (throw (ex-info "KotobaStore not wired — cutover pending" {:graph graph}))))

(defn ->kotoba-store
  ([db-api] (->kotoba-store db-api "open-jpn-mynumber-v1"))
  ([db-api graph] (->KotobaStore db-api graph)))

;; ── default store selection ─────────────────────────────────────────────────

(defn default-store
  "Pick the store backend. RisingWave (`RW_URL`) is intentionally NOT honored
  here (substrate boundary); we always return an in-memory MemStore unless a
  caller injects a KotobaStore. Mirrors the worker defaulting to `mock` mode."
  []
  (->mem-store))
