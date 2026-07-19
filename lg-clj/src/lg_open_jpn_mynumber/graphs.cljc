(ns lg-open-jpn-mynumber.graphs
  "langgraph-clj StateGraphs — clj/bb port of the graph factory in
  lg/lg_open_jpn_mynumber/server.py (ADR-2606280030).

  The Python server builds one single-node StateGraph per task handler plus a
  `health` graph, each with the topology START -> <node> -> END over a `_State`
  TypedDict whose `input` channel carries the kwargs. Same here:

      START -> :execute -> END     (per task; node calls the handler)
      START -> :ping    -> END     (health)

  The node returns {:result ..} on success or {:error ..} on exception (faithful
  to the Python node which catches and returns {'error': str(exc)[:300]}).

  Handlers persist through an injected Store; `*store*` is the dynamic seam the
  server/tests bind. It defaults to a shared in-memory MemStore so the compiled
  GRAPHS are invocable even unbound (RisingWave is the forbidden substrate)."
  (:require [langgraph.graph :as g]
            [lg-open-jpn-mynumber.store :as store]
            [lg-open-jpn-mynumber.tasks :as tasks]
            [lg-open-jpn-mynumber.util :as u]))

(defonce shared-store (store/->mem-store))
(def ^:dynamic *store* shared-store)

(defn- single-node-graph
  "Compile a one-node StateGraph wrapping `handler` (START -> :execute -> END)."
  [handler]
  (-> (g/state-graph)
      (g/add-node :execute
                  (fn [state]
                    (let [kwargs (or (:input state) {})]
                      (try
                        {:result (handler *store* kwargs)}
                        (catch #?(:clj Exception :cljs :default) e
                          {:error (u/clip #?(:clj (.getMessage e) :cljs (str e)) 300)})))))
      (g/set-entry-point :execute)
      (g/set-finish-point :execute)
      (g/compile-graph)))

(defn- health-graph []
  (-> (g/state-graph)
      (g/add-node :ping
                  (fn [_state]
                    {:result {:status "ok" :service "lg-open-jpn-mynumber"}}))
      (g/set-entry-point :ping)
      (g/set-finish-point :ping)
      (g/compile-graph)))

(defn- tail
  "NSID tail (assistant_id) — nsid.rsplit('.', 1)[-1]."
  [nsid]
  (last (clojure.string/split nsid #"\.")))

;; GRAPHS: health + one graph per NSID tail (parity with server.py GRAPHS).
(def GRAPHS
  (into {"health" (health-graph)}
        (map (fn [[nsid handler]] [(tail nsid) (single-node-graph handler)]))
        tasks/TASKS))

;; NSID -> assistant_id (parity with server.py _NSID_TO_ASSISTANT).
(def NSID->ASSISTANT
  (into {"com.etzhayyim.apps.openJpnMynumber.health" "health"}
        (map (fn [[nsid _]] [nsid (tail nsid)]))
        tasks/TASKS))
