(ns lg-open-jpn-mynumber.smoke-test
  "Smoke + behaviour tests for the lg-open-jpn-mynumber clj port — clojure.test
  analogue of lg/tests/test_smoke.py, plus handler-behaviour tests the Python
  suite could not run offline (its DB writes needed a live RisingWave, so it only
  exercised health + 404s). Here the Store is an in-memory MemStore, so the full
  handler logic + audit ledger verify under bb."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [lg-open-jpn-mynumber.graphs :as graphs]
            [lg-open-jpn-mynumber.server :as server]
            [lg-open-jpn-mynumber.store :as store]
            [lg-open-jpn-mynumber.tasks :as tasks]
            [lg-open-jpn-mynumber.util :as u]))

(def task-tails
  ["verifyJpki" "registerPerson" "lookupNonresidentAddress" "assignNonresidentAddress"
   "createConsentSession" "brokerInformationRequest" "discloseSelfInformation"
   "issueOauthToken" "introspectOauthToken" "revokeOauthToken" "validateFileManifest"
   "registerFileTransfer" "pollFileTransferStatus" "submitElectronicApplication"
   "getElectronicApplicationStatus" "requestMedicalInfo" "getMedicalInfoStatus"])

(def expected-graphs (set (cons "health" task-tails)))

(def health-nsid "com.etzhayyim.apps.openJpnMynumber.health")

;; ── server registry parity (mirrors test_smoke.py) ──────────────────────────

(deftest graphs-match-expected-set
  (is (= expected-graphs (set (keys server/GRAPHS)))))

(deftest health-graph-present
  (is (contains? server/GRAPHS "health")))

(deftest health-nsid-in-nsid-map
  (is (contains? server/NSID->ASSISTANT health-nsid))
  (is (= "health" (get server/NSID->ASSISTANT health-nsid))))

(deftest nsid-map-references-known-graphs
  (doseq [[nsid gname] server/NSID->ASSISTANT]
    (is (contains? server/GRAPHS gname) (str nsid " -> " gname " not in GRAPHS"))))

(deftest all-graphs-invocable
  (doseq [[nm graph] server/GRAPHS]
    (is (some? graph) (str "GRAPHS[" nm "] nil"))))

(deftest tasks-count-17
  (is (= 17 (count tasks/TASKS))))

;; NOTE: a former `langgraph-json-graphs-match-server` drift-guard read the
;; Python `lg/langgraph.json` deploy descriptor to assert python↔clj graph
;; parity (and that it carried no `:crons`). That python descriptor was retired
;; with the rest of the python twin (ADR-2606280030 — the clj twin is now
;; canonical), so the test was removed. `graphs-match-expected-set` above keeps
;; the clj `server/GRAPHS` pinned to `expected-graphs` (the 17 tasks + health).

;; ── dispatch surface (/ok, /health, /runs, /xrpc) ───────────────────────────

(deftest ok-endpoint
  (is (= 200 (:status (server/ok))))
  (is (true? (get-in (server/ok) [:body :ok]))))

(deftest health-endpoint
  (let [r (server/health)]
    (is (= 200 (:status r)))
    (is (= "ok" (get-in r [:body :status])))
    (is (= "lg-open-jpn-mynumber" (get-in r [:body :service])))))

(deftest unknown-assistant-404
  (is (= 404 (:status (server/dispatch-run {:assistant_id "nope" :input {}})))))

(deftest unknown-nsid-xrpc-501
  ;; server.py raises 501 for an unmapped NSID (not 404). We match the server
  ;; CODE; note the Python test_smoke asserts 404, a latent inconsistency there.
  (is (= 501 (:status (server/dispatch-xrpc "com.etzhayyim.apps.openJpnMynumber.unknownMethod" {})))))

(deftest runs-health-output
  (let [r (server/dispatch-run {:assistant_id "health" :input {}})]
    (is (= 200 (:status r)))
    (is (= "ok" (get-in r [:body :output :status])))))

;; ── util parity ─────────────────────────────────────────────────────────────

(deftest camel-to-snake-cases
  (is (= "person_ref" (u/camel->snake "personRef")))
  (is (= "requester_agency" (u/camel->snake "requesterAgency")))
  (is (= "ttl_minutes" (u/camel->snake "ttlMinutes")))
  (is (= {:person_ref 1 :purpose_code 2} (u/snake-keys {:personRef 1 :purposeCode 2}))))

(deftest require-fields-throws-on-missing
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
               (u/require-fields {:person_ref ""} [:person_ref :purpose_code]))))

(deftest adapter-mode-is-explicit-and-safe-by-default
  (is (nil? (u/ensure-mock-mode)))
  (binding [u/*adapter-mode* "real"]
    (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                          #"real adapter mode is not implemented"
                          (u/ensure-mock-mode)))))

(deftest server-start-requires-explicit-capability
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                        #"explicit HTTP server capability required"
                        (server/start! nil {:port 0}))))

;; ── handler behaviour (MemStore; the heart of the port) ─────────────────────

(deftest verify-jpki-happy
  (let [st (store/->mem-store)
        r  (tasks/verify-jpki st {:person_ref "did:p1" :purpose_code "PC1"})]
    (is (= "valid" (:certificate_status r)))
    (is (= (u/stable-hash "mock") (:certificate_fingerprint r)))
    (is (some? (:audit_event_vertex_id r)))))

(deftest register-person-persists
  (let [st (store/->mem-store)
        r  (tasks/register-person st {:person_ref "did:p1" :agency_code "A1" :subject_token "tok"})]
    (is (= (u/stable-hash "tok") (:agency_alias_hash r)))
    (is (= 1 (count (store/list-rows st :agency_alias))))))

(deftest broker-requires-consent
  (let [st (store/->mem-store)
        base {:person_ref "p" :requester_agency "A" :holder_agency "B"
              :purpose_code "PC" :dataset_code "DS"}]
    (testing "no consent -> denied"
      (let [r (tasks/broker-information-request st base)]
        (is (false? (:approved r)))
        (is (= "missing-consent" (:reason r)))))
    (testing "with consent -> approved"
      (let [r (tasks/broker-information-request st (assoc base :consent_id "cns_x"))]
        (is (true? (:approved r)))
        (is (= "special-personal-information" (:classification r)))))))

(deftest oauth-issue-introspect-revoke-roundtrip
  (let [st (store/->mem-store)
        issued (tasks/issue-oauth-token st {:requester_agency "A" :client_id "cid"
                                            :purpose_code "PC" :scope ["read"]})
        tref   (:token_ref issued)
        intro1 (tasks/introspect-oauth-token st {:token_ref tref :requester_agency "A" :purpose_code "PC"})
        revoked (tasks/revoke-oauth-token st {:token_ref tref :requester_agency "A" :purpose_code "PC"})
        intro2 (tasks/introspect-oauth-token st {:token_ref tref :requester_agency "A" :purpose_code "PC"})]
    (is (= ["read"] (:scope issued)))
    (is (true? (:active intro1)))
    (is (= "active" (:status intro1)))
    (is (true? (:revoked revoked)))
    (is (false? (:active intro2)))
    (is (= "revoked" (:status intro2)))))

(deftest oauth-introspect-not-found
  (let [st (store/->mem-store)
        r (tasks/introspect-oauth-token st {:token_ref "nope" :requester_agency "A" :purpose_code "PC"})]
    (is (false? (:active r)))
    (is (= "not-found" (:reason r)))))

(deftest file-manifest-transfer-flow
  (let [st (store/->mem-store)
        files [{:name "a.pdf" :sha256 "ABCD" :bytes 10}
               {:name "b.pdf" :sha256 "EF01" :bytes 20}]
        manifest (tasks/validate-file-manifest st {:requester_agency "A" :purpose_code "PC" :files files})
        mh (:file_manifest_hash manifest)
        xfer (tasks/register-file-transfer st {:requester_agency "A" :purpose_code "PC"
                                               :file_manifest_hash mh :file_count 2 :holder_agency "B"})
        poll (tasks/poll-file-transfer-status st {:transfer_id (:transfer_id xfer)
                                                  :requester_agency "A" :purpose_code "PC"})]
    (is (true? (:valid manifest)))
    (is (= 2 (:file_count manifest)))
    (is (= 30 (:total_bytes manifest)))
    (is (= "registered" (:status xfer)))
    (is (= "registered" (:status poll)))
    (is (= 30 (:total_bytes poll)))
    (is (= (:file_manifest_vertex_id manifest) (:file_manifest_vertex_id poll)))))

(deftest file-manifest-rejects-empty
  (let [st (store/->mem-store)]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                 (tasks/validate-file-manifest st {:requester_agency "A" :purpose_code "PC" :files []})))))

(deftest electronic-application-flow
  (let [st (store/->mem-store)
        sub (tasks/submit-electronic-application st {:person_ref "p" :requester_agency "A"
                                                     :procedure_code "PR" :purpose_code "PC"
                                                     :application_payload {:foo "bar"}})
        stat (tasks/get-electronic-application-status st {:application_id (:application_id sub)
                                                          :requester_agency "A" :purpose_code "PC"})]
    (is (= "submitted" (:status sub)))
    (is (str/starts-with? (:external_reference sub) "myna_app_"))
    (is (= "submitted" (:status stat)))
    (is (= (:application_id sub) (:application_id stat)))))

(deftest medical-info-consent-gate-and-flow
  (let [st (store/->mem-store)
        base {:person_ref "p" :requester_agency "A" :purpose_code "PC" :dataset_code "PMH"}]
    (testing "no consent -> denied"
      (let [r (tasks/request-medical-info st base)]
        (is (= "denied" (:status r)))
        (is (nil? (:medical_request_id r)))))
    (testing "with consent -> available + status roundtrip"
      (let [r (tasks/request-medical-info st (assoc base :consent_id "cns_x"))
            s (tasks/get-medical-info-status st {:medical_request_id (:medical_request_id r)
                                                 :requester_agency "A" :purpose_code "PC"})]
        (is (= "available" (:status r)))
        (is (= "available" (:status s)))
        (is (= (:response_ref r) (:response_ref s)))))))

(deftest disclose-self-returns-history
  (let [st (store/->mem-store)]
    (tasks/verify-jpki st {:person_ref "did:p1" :purpose_code "PC"})
    (tasks/assign-nonresident-address st {:person_ref "did:p1" :requester_agency "A" :purpose_code "PC"})
    (let [r (tasks/disclose-self-information st {:person_ref "did:p1" :purpose_code "PC"})]
      ;; two prior events for did:p1 (the self.disclose audit is appended after the read)
      (is (= 2 (count (:history r))))
      (is (every? #(= "did:p1" %) (map :person_ref (filter :person_ref (:history r)))) ))))

(deftest nonresident-lookup-known-prefix
  (let [st (store/->mem-store)]
    (is (true? (:match_found (tasks/lookup-nonresident-address
                              st {:requester_agency "A" :purpose_code "PC" :search_token "known:x"}))))
    (is (false? (:match_found (tasks/lookup-nonresident-address
                               st {:requester_agency "A" :purpose_code "PC" :search_token "other"}))))))

;; ── graph invoke path (StateGraph topology + node error handling) ───────────

(deftest graph-result-and-error-channels
  (binding [graphs/*store* (store/->mem-store)]
    (testing "success -> {:result ..}"
      (let [out (g/invoke (get graphs/GRAPHS "verifyJpki")
                          {:input {:person_ref "p" :purpose_code "PC"}})]
        (is (= "valid" (get-in out [:result :certificate_status])))))
    (testing "handler exception -> {:error ..} (node catches)"
      (let [out (g/invoke (get graphs/GRAPHS "verifyJpki") {:input {:person_ref ""}})]
        (is (re-find #"missing required field" (:error out)))
        (is (nil? (:result out)))))))

(deftest health-graph-invokes
  (is (= "ok" (get-in (g/invoke (get graphs/GRAPHS "health") {:input {}}) [:result :status]))))

;; ── xrpc dispatch with camelCase input normalization ────────────────────────

(deftest xrpc-camel-input-normalized
  (binding [graphs/*store* (store/->mem-store)]
    (let [r (server/dispatch-xrpc "com.etzhayyim.apps.openJpnMynumber.verifyJpki"
                                  {:personRef "did:p1" :purposeCode "PC"})]
      (is (= 200 (:status r)))
      (is (= "valid" (get-in r [:body :output :certificate_status]))))))
