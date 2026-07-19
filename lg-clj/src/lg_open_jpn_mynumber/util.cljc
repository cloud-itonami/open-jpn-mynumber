(ns lg-open-jpn-mynumber.util
  "Shared helpers — clj/bb port of the free functions in
  worker/python/open_jpn_mynumber_worker.py and lg/lg_open_jpn_mynumber/server.py
  (ADR-2606280030).

  Faithful re-implementations of:
    now_iso / stable_hash / new_id          (worker)
    require_fields / ensure_mock_mode       (worker)
    _camel_to_snake                          (server)

  All keep the snake_case identifier vocabulary of the Python handlers; XRPC
  callers send camelCase, which `snake-keys` normalizes before unpacking."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.security MessageDigest SecureRandom]
                   [java.time ZonedDateTime ZoneOffset]
                   [java.time.temporal ChronoUnit]
                   [java.util Base64])))

(def ^:dynamic *adapter-mode*
  "Host-supplied adapter mode. The safe portable default is mock."
  "mock")

(defn now-iso
  "datetime.now(UTC).replace(microsecond=0).isoformat() — e.g. 2026-06-27T12:00:00+00:00."
  []
  #?(:clj (-> (ZonedDateTime/now ZoneOffset/UTC)
              (.truncatedTo ChronoUnit/SECONDS)
              (.toOffsetDateTime)
              (.toString))
     :cljs (.toISOString (js/Date.))))

(defn now-plus-minutes-iso
  "now(UTC) + `minutes`, truncated to seconds, isoformat (TTL expiries)."
  [minutes]
  #?(:clj (-> (ZonedDateTime/now ZoneOffset/UTC)
              (.plusMinutes (long minutes))
              (.truncatedTo ChronoUnit/SECONDS)
              (.toOffsetDateTime)
              (.toString))
     :cljs (.toISOString (js/Date. (+ (.now js/Date) (* minutes 60000))))))

(defn ->int
  "Coerce to long (payloads may carry ints or numeric strings)."
  [v default]
  (cond
    (number? v) (long v)
    (and (string? v) (seq v)) (try #?(:clj (Long/parseLong (str/trim v)) :cljs (js/parseInt v)) (catch #?(:clj Exception :cljs :default) _ default))
    :else default))

(defn stable-hash
  "sha256 hex of the utf-8 bytes of `value` (worker.stable_hash)."
  [value]
  #?(:clj (let [md (MessageDigest/getInstance "SHA-256")
                bs (.digest md (.getBytes (str value) "UTF-8"))]
            (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))
     :cljs (throw (js/Error. "stable-hash not implemented for cljs"))))

(defn new-id
  "prefix + '_' + 16 url-safe random chars (worker.new_id; secrets.token_urlsafe
  with '-'/'_' stripped, truncated to 16)."
  [prefix]
  #?(:clj (let [buf (byte-array 18)
                _   (.nextBytes (SecureRandom.) buf)
                tok (-> (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) buf)
                        (str/replace "-" "") (str/replace "_" ""))]
            (str prefix "_" (subs tok 0 (min 16 (count tok)))))
     :cljs (str prefix "_" (subs (str (random-uuid)) 0 16))))

(defn camel->snake
  "server._camel_to_snake — splits acronym/word and lower/upper boundaries."
  [name]
  (-> (str name)
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1_$2")
      (str/replace #"([a-z\d])([A-Z])" "$1_$2")
      str/lower-case))

(defn snake-keys
  "Normalize a payload map's keys camelCase/PascalCase → snake_case keyword
  (server applies _camel_to_snake to every input key before unpacking)."
  [m]
  (into {} (map (fn [[k v]] [(keyword (camel->snake (name k))) v]) m)))

(defn require-fields
  "worker.require_fields — throws ex-info if any field is blank/missing.
  Fields are snake_case keywords."
  [payload fields]
  (let [missing (remove (fn [f] (seq (str/trim (str (get payload f ""))))) fields)]
    (when (seq missing)
      (throw (ex-info (str "missing required field(s): "
                           (str/join ", " (map name missing)))
                      {:missing (vec missing)})))))

(defn ensure-mock-mode
  "worker.ensure_mock_mode — real adapter mode is not implemented in this scaffold."
  []
  (when (not= (-> *adapter-mode* str/trim str/lower-case) "mock")
    (throw (ex-info "real adapter mode is not implemented in this scaffold" {}))))

(defn clip [s n]
  (let [s (str s)] (subs s 0 (min n (count s)))))
