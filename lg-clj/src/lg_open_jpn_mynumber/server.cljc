(ns lg-open-jpn-mynumber.server
  "HTTP surface — clj/bb port of lg/lg_open_jpn_mynumber/server.py
  (FastAPI/uvicorn -> org.httpkit.server, ADR-2606280030).

  Same routes:
    GET  /ok                 -> {ok true}
    GET  /health             -> health graph result
    POST /runs               -> invoke a graph by assistant_id
    POST /xrpc/{nsid}        -> invoke graph mapped from NSID (JSON body)
    GET  /xrpc/{nsid}        -> invoke graph mapped from NSID (query params)

  Input keys arrive camelCase (XRPC) and are normalized to snake_case before
  unpacking (server._camel_to_snake), then wrapped as {:input ..} for the graph.

  `dispatch-*` are pure (assistant/nsid + input -> {:status :body :elapsed_s}) so
  routing is testable without a socket. `app`/`start!`/`-main` bind an
  org.httpkit.server listener (bb built-in). The DEPLOYED runtime remains the
  FastAPI pod (lg/) — this clj server COEXISTS and is the additive, verified twin
  (py_removed=0 by design)."
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [lg-open-jpn-mynumber.graphs :as graphs]
            [lg-open-jpn-mynumber.util :as u]
            #?(:clj [cheshire.core :as json])))

(def GRAPHS graphs/GRAPHS)
(def NSID->ASSISTANT graphs/NSID->ASSISTANT)

(defn- now-ms [] #?(:clj (System/currentTimeMillis) :cljs 0))
(defn- elapsed-s [t0] (/ (Math/round (* 1000.0 (/ (- (now-ms) t0) 1000.0))) 1000.0))

(defn- safe-json
  "server._safe_json — keep json-serializable values, else stringify."
  [v]
  (cond
    (map? v) (into {} (map (fn [[k x]] [k (safe-json x)]) v))
    (sequential? v) (mapv safe-json v)
    (or (string? v) (number? v) (boolean? v) (nil? v) (keyword? v)) v
    :else (str v)))

(defn- run-graph
  "Invoke `graph` with {:input snake-input}; project to server.py response shape."
  [graph raw-input]
  (let [snake-input (u/snake-keys (or raw-input {}))
        t0    (now-ms)
        state (g/invoke graph {:input snake-input})
        el    (elapsed-s t0)]
    (if (:error state)
      {:status 500 :body {:error (:error state) :elapsed_s el}}
      {:status 200 :body {:output (safe-json (:result state)) :elapsed_s el}})))

(defn dispatch-run
  "POST /runs body -> {:status :body}. body: {:assistant_id .. :input ..}."
  [body]
  (let [aid   (or (:assistant_id body) (get body "assistant_id") "health")
        graph (get GRAPHS aid)]
    (if (nil? graph)
      {:status 404 :body {:detail (str "graph '" aid "' not found")}}
      (run-graph graph (or (:input body) (get body "input") {})))))

(defn dispatch-xrpc
  "POST|GET /xrpc/{nsid} -> {:status :body}. NSID mapped to assistant_id."
  [nsid raw-input]
  (let [aid (get NSID->ASSISTANT nsid)]
    (cond
      (nil? aid) {:status 501 :body {:detail (str "NSID not mapped: " nsid)}}
      (nil? (get GRAPHS aid)) {:status 404 :body {:detail (str "graph '" aid "' not found")}}
      :else (run-graph (get GRAPHS aid) raw-input))))

(defn health
  "GET /health -> health graph result."
  []
  (let [state (g/invoke (get GRAPHS "health") {:input {}})]
    {:status 200 :body (or (:result state) {:status "ok"})}))

(defn ok [] {:status 200 :body {:ok true}})

;; ── ring/httpkit adapter ─────────────────────────────────────────────────────

#?(:clj
   (defn- json-response [{:keys [status body]}]
     {:status status
      :headers {"Content-Type" "application/json"}
      :body (json/generate-string body)}))

#?(:clj
   (defn- parse-json-body [req]
     (let [b (:body req)
           s (cond (nil? b) "" (string? b) b :else (slurp b))]
       (if (str/blank? s) {} (json/parse-string s true)))))

#?(:clj
   (defn- query->map [^String qs]
     (if (str/blank? qs)
       {}
       (into {} (for [pair (str/split qs #"&")
                      :let [[k v] (str/split pair #"=" 2)]
                      :when (seq k)]
                  [(keyword k) (or v "")])))))

#?(:clj
   (defn app
     "httpkit ring handler — mirrors the FastAPI route table."
     [req]
     (let [method (:request-method req)
           uri    (:uri req)]
       (json-response
        (cond
          (and (= :get method) (= uri "/ok")) (ok)
          (and (= :get method) (= uri "/health")) (health)
          (and (= :post method) (= uri "/runs")) (dispatch-run (parse-json-body req))
          (str/starts-with? uri "/xrpc/")
          (let [nsid (subs uri (count "/xrpc/"))]
            (dispatch-xrpc nsid (if (= :post method)
                                  (parse-json-body req)
                                  (query->map (:query-string req)))))
          :else {:status 404 :body {:detail "not found"}})))))

#?(:clj
   (defn start!
     "Boot through an explicitly supplied Ring server capability."
     [run-server & [{:keys [port] :or {port 8080}}]]
     (when-not (fn? run-server)
       (throw (ex-info "explicit HTTP server capability required"
                       {:capability :http-server})))
     (println (str "lg-open-jpn-mynumber clj server on :" port
                   " — " (count GRAPHS) " graphs"))
     (run-server app {:port port})))

#?(:clj
   (defn -main [& _]
     (throw (ex-info "host adapter required; use the bb serve task"
                     {:capability :host-adapter}))))
