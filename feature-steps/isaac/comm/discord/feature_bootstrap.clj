(ns isaac.comm.discord.feature-bootstrap
  "Loaded after isaac.**-steps so duplicate session-tier steps that collide
   with server-tier definitions can be dropped from the gherclj registry."
  (:require [clojure.string :as str]
            [isaac.foundation.logger :as log]))

;; Foundation logger defaults to a real log file; feature harnesses use mem-fs.
(log/set-output! :memory)

(def ^:private server-ns 'isaac.http.server-steps)
(def ^:private session-ns 'isaac.agent.session.session-steps)
(def ^:private delivery-ns 'isaac.agent.comm.delivery.worker-steps)

(defn- without-templates [entries templates]
  (let [drop? (set templates)]
    (vec (remove #(contains? drop? (:template %)) entries))))

(defn- server-owns-config? [registry]
  (some (fn [[ns-sym entries]]
          (when (= ns-sym server-ns)
            (some #(= "config:" (:template %)) entries)))
        registry))

(defn- session-drop-templates [registry]
  (cond-> ["default Grover setup"]
    (server-owns-config? registry) (conj "config:")))

;; Server owns config: (when registered) and default Grover setup for
;; hot-reload lifecycle scenarios. Session duplicates drop so crew-tool
;; steps still resolve via unique templates.
(when-let [registry-var (some-> (find-ns 'gherclj.core) ns-interns (get 'registry))]
  (swap! @registry-var
         (fn [m]
           (into {}
                 (map (fn [[ns-sym entries]]
                        [ns-sym (cond
                                  (= ns-sym session-ns)
                                  (without-templates entries (session-drop-templates m))
                                  (= ns-sym delivery-ns)
                                  (without-templates entries ["the delivery worker ticks"])
                                  :else entries)]))
                 m))))