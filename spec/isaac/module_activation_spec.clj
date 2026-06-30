(ns isaac.module-activation-spec
  "Compile and comm-factory activation smoke: a non-compiling discord module
   must fail CI before it can silently dead-letter comm_send in production."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [isaac.comm.factory :as comm-factory]
    [isaac.module.loader :as module-loader]
    [speclj.core :refer :all]))

(defn- discord-module-index []
  (when-let [manifest (some-> (io/resource "isaac-manifest.edn") slurp edn/read-string)]
    {:isaac.comm.discord {:coord {} :manifest manifest :path nil}}))

(describe "Discord module activation"

  (around [it]
    (module-loader/clear-activations!)
    (it)
    (module-loader/clear-activations!))

  (it "reloads production namespaces without compile errors"
    (require 'isaac.comm.discord :reload)
    (require 'isaac.comm.discord.service :reload)
    (require 'isaac.comm.discord.gateway :reload)
    (require 'isaac.comm.discord.rest :reload))

  (it "activates from the manifest and installs the :discord comm factory"
    (let [index (discord-module-index)]
      (should-not-be-nil index)
      (module-loader/activate! :isaac.comm.discord index)
      (should-not= :failed (@#'comm-factory/ensure-impl! index :discord))
      (should (get-method comm-factory/create :discord)))))