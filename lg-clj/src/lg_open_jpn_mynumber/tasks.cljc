(ns lg-open-jpn-mynumber.tasks
  "The 17 My Number BPMN service-task handlers — clj/bb port of the async
  handlers in worker/python/open_jpn_mynumber_worker.py (ADR-2606280030).

  Each handler is `(handler store payload) -> result-map`, faithful to its Python
  twin: same `require_fields` validation, same `ensure_mock_mode` gate (the only
  implemented adapter mode), same result shape, and the same trailing
  `audit(...)` append. Persistence goes through the injected `Store` (MemStore by
  default; RisingWave is the forbidden substrate — see store.cljc). The Python
  `mv_*` materialized-view reads are recomputed here from base rows.

  `TASKS` mirrors the Python `TASKS` dict (full NSID -> handler)."
  (:require [clojure.string :as str]
            [lg-open-jpn-mynumber.audit :refer [audit]]
            [lg-open-jpn-mynumber.store :as store]
            [lg-open-jpn-mynumber.util :as u]
            #?(:clj [cheshire.core :as json])))

;; ── helpers ─────────────────────────────────────────────────────────────────

(defn- canonical
  "Recursively sort map keys / stringify keyword keys (json.dumps sort_keys=True)."
  [x]
  (cond
    (map? x) (into (sorted-map)
                   (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) (canonical v)]) x))
    (sequential? x) (mapv canonical x)
    :else x))

(defn- chash [x]
  #?(:clj (u/stable-hash (json/generate-string (canonical x)))
     :cljs (u/stable-hash (str (canonical x)))))

(defn- json-str [x]
  #?(:clj (json/generate-string x) :cljs (js/JSON.stringify (clj->js x))))

(defn- json-parse [s]
  #?(:clj (json/parse-string s) :cljs (js/JSON.parse s)))

(defn- with-audit [result st event-type res-code payload]
  (merge result (audit st event-type res-code (merge payload result))))

;; ── 1. verify-jpki ──────────────────────────────────────────────────────────

(defn verify-jpki [st p]
  (u/require-fields p [:person_ref :purpose_code])
  (u/ensure-mock-mode)
  (let [cert (str (or (:certificate_pem p) (:certificate_serial p) "mock"))
        result {:person_ref (:person_ref p)
                :certificate_fingerprint (u/stable-hash cert)
                :certificate_status "valid"
                :verification_method "mock-ocsp"
                :checked_at (u/now-iso)}]
    (with-audit result st "jpki.verify" "valid" p)))

;; ── 2. register-person ──────────────────────────────────────────────────────

(defn register-person [st p]
  (u/require-fields p [:person_ref :agency_code :subject_token])
  (let [alias-id   (u/new-id "vertex_alias")
        alias-hash (u/stable-hash (str (:subject_token p)))]
    (store/put! st :agency_alias alias-id
                {:vertex_id alias-id
                 :person_ref (:person_ref p)
                 :agency_code (:agency_code p)
                 :alias_kind (or (:alias_kind p) "subject_token")
                 :alias_value_hash alias-hash
                 :created_at (u/now-iso)})
    (with-audit {:person_ref (:person_ref p) :agency_alias_hash alias-hash}
      st "person.register" "registered" p)))

;; ── 3. lookup-nonresident-address ───────────────────────────────────────────

(defn lookup-nonresident-address [st p]
  (u/require-fields p [:requester_agency :purpose_code :search_token])
  (u/ensure-mock-mode)
  (let [found (str/starts-with? (str (:search_token p)) "known:")
        result {:match_found found
                :candidate_count (if found 1 0)
                :address_ref (when found (str "addr_" (subs (u/stable-hash (str (:search_token p))) 0 16)))}]
    (with-audit result st "nonresident.lookup" (if found "found" "not_found") p)))

;; ── 4. assign-nonresident-address ───────────────────────────────────────────

(defn assign-nonresident-address [st p]
  (u/require-fields p [:person_ref :requester_agency :purpose_code])
  (let [address-ref (or (:address_ref p) (u/new-id "addr"))]
    (with-audit {:person_ref (:person_ref p) :address_ref address-ref :assigned true}
      st "nonresident.assign" "assigned" p)))

;; ── 5. create-consent-session ───────────────────────────────────────────────

(defn create-consent-session [st p]
  (u/require-fields p [:person_ref :requester_agency :purpose_code :scope])
  (let [consent-id (u/new-id "cns")
        expires-at (u/now-plus-minutes-iso (u/->int (:ttl_minutes p) 30))]
    (store/put! st :consent_receipt consent-id
                {:vertex_id consent-id
                 :person_ref (:person_ref p)
                 :requester_agency (:requester_agency p)
                 :purpose_code (:purpose_code p)
                 :scope_json (json-str (:scope p))
                 :expires_at expires-at
                 :created_at (u/now-iso)})
    (with-audit {:consent_id consent-id :status "pending-user-auth" :expires_at expires-at}
      st "consent.create" "pending" p)))

;; ── 6. broker-information-request ───────────────────────────────────────────

(defn broker-information-request [st p]
  (u/require-fields p [:person_ref :requester_agency :holder_agency :purpose_code :dataset_code])
  (if (and (not (:consent_id p)) (get p :requires_consent true))
    (with-audit {:approved false :reason "missing-consent"} st "info.request" "denied" p)
    (do
      (u/ensure-mock-mode)
      (let [response-ref (str "payload_" (subs (chash p) 0 20))]
        (with-audit {:approved true
                     :response_ref response-ref
                     :classification "special-personal-information"
                     :data_inline nil}
          st "info.request" "approved" p)))))

;; ── 7. disclose-self-information ────────────────────────────────────────────

(defn disclose-self-information [st p]
  (u/require-fields p [:person_ref :purpose_code])
  (let [limit (u/->int (:limit p) 50)
        history (->> (store/list-rows st :audit_event)
                     (filter #(= (:person_ref %) (:person_ref p)))
                     (sort-by :created_at)
                     reverse
                     (take limit)
                     (mapv #(select-keys % [:vertex_id :event_type :requester_agency
                                            :holder_agency :purpose_code :dataset_code
                                            :result :created_at])))]
    (with-audit {:history history}
      st "self.disclose" "returned" (assoc p :dataset_code "provision_history"))))

;; ── 8. issue-oauth-token ────────────────────────────────────────────────────

(defn issue-oauth-token [st p]
  (u/require-fields p [:requester_agency :client_id :purpose_code :scope])
  (u/ensure-mock-mode)
  (let [scope     (if (sequential? (:scope p)) (vec (:scope p)) [(str (:scope p))])
        token-ref (u/new-id "vertex_tok")
        expires   (u/now-plus-minutes-iso (u/->int (:ttl_minutes p) 60))]
    (store/put! st :oauth_token token-ref
                {:vertex_id token-ref
                 :requester_agency (:requester_agency p)
                 :client_id_hash (u/stable-hash (str (:client_id p)))
                 :scope_json (json-str scope)
                 :purpose_code (:purpose_code p)
                 :active true
                 :revoked_at nil
                 :expires_at expires
                 :created_at (u/now-iso)})
    (merge {:token_ref token-ref :token_type "Bearer" :scope scope :active true :expires_at expires}
           (audit st "oauth.issue" "issued"
                  (merge p {:token_ref token-ref :scope scope :client_id "<redacted>"})))))

;; ── 9. introspect-oauth-token ───────────────────────────────────────────────

(defn introspect-oauth-token [st p]
  (u/require-fields p [:token_ref :requester_agency :purpose_code])
  (if-let [row (store/get-row st :oauth_token (:token_ref p))]
    (let [status   (if (:active row) "active" "revoked")
          active   (and (= status "active") (> (compare (:expires_at row) (u/now-iso)) 0))
          result {:token_ref (:vertex_id row)
                  :requester_agency (:requester_agency row)
                  :scope (json-parse (:scope_json row))
                  :active active
                  :status status
                  :expires_at (:expires_at row)
                  :revoked_at (:revoked_at row)}]
      (with-audit result st "oauth.introspect" (if active "active" "inactive") p))
    (with-audit {:token_ref (:token_ref p) :active false :reason "not-found"}
      st "oauth.introspect" "inactive" p)))

;; ── 10. revoke-oauth-token ──────────────────────────────────────────────────

(defn revoke-oauth-token [st p]
  (u/require-fields p [:token_ref :requester_agency :purpose_code])
  (let [revoked-at (u/now-iso)
        existed (store/update-row! st :oauth_token (:token_ref p)
                                   {:active false :revoked_at revoked-at})]
    (with-audit {:token_ref (:token_ref p) :revoked existed :revoked_at revoked-at}
      st "oauth.revoke" (if existed "revoked" "not-found") p)))

;; ── 11. validate-file-manifest ──────────────────────────────────────────────

(defn normalize-file-manifest [p]
  (let [files (:files p)]
    (when-not (and (sequential? files) (seq files))
      (throw (ex-info "files must be a non-empty array" {})))
    (vec (map-indexed
          (fn [idx item]
            (when-not (map? item)
              (throw (ex-info (str "files[" idx "] must be an object") {})))
            (doseq [f [:name :sha256 :bytes]]
              (when-not (contains? item f)
                (throw (ex-info (str "files[" idx "]." (name f) " is required") {}))))
            {:name (str (:name item))
             :sha256 (str/lower-case (str (:sha256 item)))
             :bytes (u/->int (:bytes item) 0)
             :media_type (str (or (:media_type item) "application/octet-stream"))})
          files))))

(defn validate-file-manifest [st p]
  (u/require-fields p [:requester_agency :purpose_code])
  (let [files         (normalize-file-manifest p)
        manifest-hash (chash files)
        manifest-id   (str "vertex_manifest_" (subs manifest-hash 0 24))
        total-bytes   (reduce + 0 (map :bytes files))]
    (store/put! st :file_manifest manifest-id
                {:vertex_id manifest-id
                 :requester_agency (:requester_agency p)
                 :purpose_code (:purpose_code p)
                 :file_manifest_hash manifest-hash
                 :file_count (count files)
                 :total_bytes total-bytes
                 :manifest_json (json-str files)
                 :created_at (u/now-iso)})
    (with-audit {:valid true
                 :file_manifest_vertex_id manifest-id
                 :file_count (count files)
                 :total_bytes total-bytes
                 :file_manifest_hash manifest-hash}
      st "file.manifest.validate" "valid" p)))

;; ── 12. register-file-transfer ──────────────────────────────────────────────

(defn register-file-transfer [st p]
  (u/require-fields p [:requester_agency :purpose_code :file_manifest_hash])
  (let [transfer-id (or (:transfer_id p) (u/new-id "vertex_xfer"))
        now (u/now-iso)]
    (store/put! st :file_transfer transfer-id
                {:vertex_id transfer-id
                 :requester_agency (:requester_agency p)
                 :holder_agency (:holder_agency p)
                 :purpose_code (:purpose_code p)
                 :file_manifest_hash (:file_manifest_hash p)
                 :file_count (u/->int (:file_count p) 0)
                 :status "registered"
                 :created_at now
                 :updated_at now})
    (when-let [manifest (first (filter #(= (:file_manifest_hash %) (:file_manifest_hash p))
                                       (store/list-rows st :file_manifest)))]
      (let [edge-id (u/new-id "edge_xfer_manifest")]
        (store/put! st :edges edge-id
                    {:edge_id edge-id :from_vertex_id transfer-id
                     :to_vertex_id (:vertex_id manifest)
                     :edge_type "uses_manifest" :created_at now})))
    (with-audit {:transfer_id transfer-id :status "registered" :registered_at now}
      st "file.transfer.register" "registered" p)))

;; ── 13. poll-file-transfer-status ───────────────────────────────────────────

(defn poll-file-transfer-status [st p]
  (u/require-fields p [:transfer_id :requester_agency :purpose_code])
  (if-let [row (store/get-row st :file_transfer (:transfer_id p))]
    (let [manifest (first (filter #(= (:file_manifest_hash %) (:file_manifest_hash row))
                                  (store/list-rows st :file_manifest)))
          result {:transfer_id (:vertex_id row)
                  :status (:status row)
                  :file_manifest_hash (:file_manifest_hash row)
                  :file_manifest_vertex_id (:vertex_id manifest)
                  :file_count (:file_count row)
                  :total_bytes (:total_bytes manifest)
                  :updated_at (:updated_at row)}]
      (with-audit result st "file.transfer.status" (:status row) p))
    (with-audit {:transfer_id (:transfer_id p) :status "not-found"}
      st "file.transfer.status" "not-found" p)))

;; ── 14. submit-electronic-application ───────────────────────────────────────

(defn submit-electronic-application [st p]
  (u/require-fields p [:person_ref :requester_agency :procedure_code :purpose_code])
  (u/ensure-mock-mode)
  (let [app-payload    (or (:application_payload p) {})
        payload-hash   (chash app-payload)
        application-id (or (:application_id p) (u/new-id "vertex_app"))
        now            (u/now-iso)
        external-ref   (str "myna_app_" (subs (u/stable-hash application-id) 0 18))]
    (store/put! st :electronic_application application-id
                {:vertex_id application-id
                 :person_ref (:person_ref p)
                 :requester_agency (:requester_agency p)
                 :procedure_code (:procedure_code p)
                 :purpose_code (:purpose_code p)
                 :application_payload_hash payload-hash
                 :status "submitted"
                 :external_reference external-ref
                 :submitted_at now
                 :updated_at now})
    (when (:consent_id p)
      (let [edge-id (u/new-id "edge_app_consent")]
        (store/put! st :edges edge-id
                    {:edge_id edge-id :from_vertex_id application-id
                     :to_vertex_id (:consent_id p) :edge_type "authorized_by" :created_at now})))
    (with-audit {:application_id application-id
                 :status "submitted"
                 :external_reference external-ref
                 :application_payload_hash payload-hash
                 :submitted_at now}
      st "electronic_application.submit" "submitted" p)))

;; ── 15. get-electronic-application-status ───────────────────────────────────

(defn get-electronic-application-status [st p]
  (u/require-fields p [:application_id :requester_agency :purpose_code])
  (if-let [row (store/get-row st :electronic_application (:application_id p))]
    (with-audit {:application_id (:vertex_id row)
                 :person_ref (:person_ref row)
                 :procedure_code (:procedure_code row)
                 :purpose_code (:purpose_code row)
                 :status (:status row)
                 :external_reference (:external_reference row)
                 :updated_at (:updated_at row)}
      st "electronic_application.status" (:status row) p)
    (with-audit {:application_id (:application_id p) :status "not-found"}
      st "electronic_application.status" "not-found" p)))

;; ── 16. request-medical-info ────────────────────────────────────────────────

(defn request-medical-info [st p]
  (u/require-fields p [:person_ref :requester_agency :purpose_code :dataset_code])
  (if (and (not (:consent_id p)) (get p :requires_consent true))
    (with-audit {:medical_request_id nil :status "denied" :reason "missing-consent"}
      st "medical_info.request" "denied" p)
    (do
      (u/ensure-mock-mode)
      (let [request-id   (or (:medical_request_id p) (u/new-id "vertex_med"))
            now          (u/now-iso)
            response-ref (str "pmh_" (subs (chash p) 0 20))]
        (store/put! st :medical_info_request request-id
                    {:vertex_id request-id
                     :person_ref (:person_ref p)
                     :requester_agency (:requester_agency p)
                     :purpose_code (:purpose_code p)
                     :dataset_code (:dataset_code p)
                     :consent_id (:consent_id p)
                     :status "available"
                     :response_ref response-ref
                     :requested_at now
                     :updated_at now})
        (when (:consent_id p)
          (let [edge-id (u/new-id "edge_med_consent")]
            (store/put! st :edges edge-id
                        {:edge_id edge-id :from_vertex_id request-id
                         :to_vertex_id (:consent_id p) :edge_type "authorized_by" :created_at now})))
        (with-audit {:medical_request_id request-id
                     :status "available"
                     :response_ref response-ref
                     :requested_at now}
          st "medical_info.request" "available" p)))))

;; ── 17. get-medical-info-status ─────────────────────────────────────────────

(defn get-medical-info-status [st p]
  (u/require-fields p [:medical_request_id :requester_agency :purpose_code])
  (if-let [row (store/get-row st :medical_info_request (:medical_request_id p))]
    (with-audit {:medical_request_id (:vertex_id row)
                 :person_ref (:person_ref row)
                 :purpose_code (:purpose_code row)
                 :dataset_code (:dataset_code row)
                 :status (:status row)
                 :response_ref (:response_ref row)
                 :updated_at (:updated_at row)}
      st "medical_info.status" (:status row) p)
    (with-audit {:medical_request_id (:medical_request_id p) :status "not-found"}
      st "medical_info.status" "not-found" p)))

;; ── TASKS registry (mirrors the Python TASKS dict, full NSID -> handler) ─────

(def ns-prefix "com.etzhayyim.apps.openJpnMynumber")

(def TASKS
  {(str ns-prefix ".verifyJpki")                 verify-jpki
   (str ns-prefix ".registerPerson")             register-person
   (str ns-prefix ".lookupNonresidentAddress")   lookup-nonresident-address
   (str ns-prefix ".assignNonresidentAddress")   assign-nonresident-address
   (str ns-prefix ".createConsentSession")       create-consent-session
   (str ns-prefix ".brokerInformationRequest")   broker-information-request
   (str ns-prefix ".discloseSelfInformation")    disclose-self-information
   (str ns-prefix ".issueOauthToken")            issue-oauth-token
   (str ns-prefix ".introspectOauthToken")       introspect-oauth-token
   (str ns-prefix ".revokeOauthToken")           revoke-oauth-token
   (str ns-prefix ".validateFileManifest")       validate-file-manifest
   (str ns-prefix ".registerFileTransfer")       register-file-transfer
   (str ns-prefix ".pollFileTransferStatus")     poll-file-transfer-status
   (str ns-prefix ".submitElectronicApplication") submit-electronic-application
   (str ns-prefix ".getElectronicApplicationStatus") get-electronic-application-status
   (str ns-prefix ".requestMedicalInfo")         request-medical-info
   (str ns-prefix ".getMedicalInfoStatus")       get-medical-info-status})
