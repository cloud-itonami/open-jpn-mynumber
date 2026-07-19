(ns lg-open-jpn-mynumber.audit
  "Append-only audit ledger — clj/bb port of worker.audit() (ADR-2606280030).

  Every handler appends one immutable event (and per-subject edges) describing
  the write/disclosure/issue/cert it performed; this IS the data-sovereignty /
  traceability core of the actor pattern (CLAUDE.md §Actors). The Python writes a
  `vertex_open_jpn_mynumber_audit_event` row + `edge_open_jpn_mynumber_event_subject`
  edges into RisingWave; here we append into the injected `Store` (MemStore by
  default — RisingWave is the forbidden substrate)."
  (:require [lg-open-jpn-mynumber.store :as store]
            [lg-open-jpn-mynumber.util :as u]
            #?(:clj [cheshire.core :as json])))

(defn- json-str [x]
  #?(:clj (json/generate-string x) :cljs (js/JSON.stringify (clj->js x))))

(defn audit
  "Append an audit event for `event-type`/`result` derived from `payload`.
  Returns {:audit_event_vertex_id .. :audited_at ..} (worker.audit return)."
  [st event-type result payload]
  (let [event-id   (u/new-id "vertex_evt")
        created-at (u/now-iso)
        row {:vertex_id        event-id
             :event_type       event-type
             :person_ref       (:person_ref payload)
             :requester_agency (:requester_agency payload)
             :holder_agency    (:holder_agency payload)
             :purpose_code     (:purpose_code payload)
             :dataset_code     (:dataset_code payload)
             :result           result
             :payload_json     (json-str payload)
             :created_at       created-at}]
    (store/put! st :audit_event event-id row)
    (doseq [[ref-kind ref-value] [[:person_ref (:person_ref row)]
                                  [:requester_agency (:requester_agency row)]
                                  [:holder_agency (:holder_agency row)]]]
      (when ref-value
        (let [edge-id (u/new-id "edge_evt")]
          (store/put! st :edges edge-id
                      {:edge_id        edge-id
                       :from_vertex_id event-id
                       :to_ref         ref-value
                       :to_ref_kind    (name ref-kind)
                       :edge_type      "audits"
                       :created_at     created-at}))))
    {:audit_event_vertex_id event-id :audited_at created-at}))
