(ns isaac.discord.feature-bootstrap
  "Loaded after isaac.**-steps so duplicate session-tier steps that collide
   with server-tier definitions can be dropped from the gherclj registry."
  (:require [clojure.string :as str]
            [gherclj.core :as gherclj]))

(defn- session-duplicate-step? [{:keys [template]}]
  (or (= "config:" template)
      (= "default Grover setup" template)))

(swap! @(ns-resolve 'gherclj.core 'registry) update 'isaac.session.session-steps
       (fn [entries]
         (vec (remove session-duplicate-step? entries))))