(ns isaac.discord.feature-bootstrap
  "Loaded after isaac.**-steps so duplicate session-tier steps that collide
   with server-tier definitions can be dropped from the gherclj registry."
  (:require [clojure.string :as str]))

(def ^:private server-ns 'isaac.server.server-steps)
(def ^:private session-ns 'isaac.session.session-steps)

(defn- without-templates [entries templates]
  (let [drop? (set templates)]
    (vec (remove #(contains? drop? (:template %)) entries))))

;; Server owns config: and default Grover setup (hot-reload for lifecycle).
;; Session duplicates drop so crew-tool steps still resolve via unique templates.
(when-let [registry-var (some-> (find-ns 'gherclj.core) ns-interns (get 'registry))]
  (swap! @registry-var
         (fn [m]
           (into {}
                 (map (fn [[ns-sym entries]]
                        [ns-sym (if (= ns-sym session-ns)
                                  (without-templates entries
                                    ["config:" "default Grover setup"])
                                  entries)]))
                 m))))