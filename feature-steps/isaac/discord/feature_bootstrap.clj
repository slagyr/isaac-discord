(ns isaac.discord.feature-bootstrap
  "Loaded after isaac.**-steps so duplicate session-tier steps that collide
   with server-tier definitions can be dropped from the gherclj registry."
  (:require [clojure.string :as str]))

(def ^:private server-ns 'isaac.server.server-steps)
(def ^:private session-ns 'isaac.session.session-steps)
(def ^:private configurator-ns 'isaac.configurator-steps)

(defn- without-templates [entries templates]
  (let [drop? (set templates)]
    (vec (remove #(contains? drop? (:template %)) entries))))

(when-let [registry-var (some-> (find-ns 'gherclj.core) ns-interns (get 'registry))]
  (swap! @registry-var
         (fn [m]
           (into {}
                 (map (fn [[ns-sym entries]]
                        [ns-sym (cond
                                  (= ns-sym server-ns) entries
                                  (= ns-sym session-ns) (without-templates entries ["config:"])
                                  (= ns-sym configurator-ns) (without-templates entries ["default Grover setup"])
                                  :else entries)]))
                 m))))