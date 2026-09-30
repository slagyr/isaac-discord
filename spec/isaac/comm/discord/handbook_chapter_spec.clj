(ns isaac.comm.discord.handbook-chapter-spec
  "Lint for isaac-discord's own handbook chapter (isaac-0xp9): every backtick
   `config:<dotted.path>` reference (no angle-bracket placeholder inside the
   path) must resolve against the composed config schema, and the word right
   after `isaac ` in every `isaac <command>` invocation must name a
   registered top-level CLI command. Keep both literal and real when you
   edit the chapter — this lint fails the build once either drifts from what
   Isaac actually exposes. `<placeholder>` shapes (e.g. `config:<dotted.path>`
   itself, or an angle-bracket id) are intentionally skipped. isaac-discord's
   own config fields (`discord/token`, `discord/channels`, …) are
   deliberately written in the chapter as plain inline code, never a
   backtick `config:` reference — they're contributed dynamically to the
   shared `comms` table via the `:isaac.agent/comm` berth and don't resolve
   through the composed schema the same way a builtin module's fields do.
   Mirrors isaac.foundation.handbook-chapter-spec, adapted like
   isaac.google's and isaac.cron's own to read raw manifests rather than
   isaac.foundation.module.berths' introspection helpers, which aren't available at
   every foundation pin a module may carry."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.foundation.config.schema-compose :as schema-compose]
    [isaac.foundation.config.schema.resolve :as schema-resolve]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.module.discovery :as discovery]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private chapter-resource "isaac/comm/discord/handbook.md")

(defn- chapter-text []
  (some-> (io/resource chapter-resource) slurp))

(defn- config-refs
  "Backtick `config:<path>` references in `text`, skipping `<placeholder>`
   shapes (any reference whose path still contains an angle bracket)."
  [text]
  (->> (re-seq #"`config:([^`]+)`" text)
       (map second)
       (remove #(str/includes? % "<"))
       distinct))

(defn- cli-commands-mentioned
  "The word immediately following `isaac ` wherever it appears — inline
   code, fenced examples, or plain prose — for every top-level `isaac
   <command>` invocation in `text`."
  [text]
  (->> (re-seq #"isaac\s+([a-zA-Z][a-zA-Z0-9_-]*)" text)
       (map second)
       distinct))

(defn- known-cli-commands
  "Top-level command names contributed to the :isaac/cli berth by every
   module in `index` (builtin only, for this repo's own spec) — read
   directly off each module's manifest rather than through
   isaac.foundation.module.berths, whose report helpers vary across pinned foundation
   shas."
  [index]
  (->> (vals index)
       (mapcat (fn [entry] (keys (get-in entry [:manifest :isaac/cli]))))
       (map name)
       set))

(describe "isaac-discord handbook chapter (isaac-0xp9)"

  (around [example] (nexus/-with-nexus {:fs (fs/real-fs)} (example)))

  (it "ships at the manifest's declared classpath resource"
    (should-not-be-nil (chapter-text)))

  (it "every `config:<path>` reference resolves against the composed config schema"
    (let [text        (chapter-text)
          root-schema (schema-compose/effective-root-schema (discovery/builtin-index))
          unresolved  (remove #(schema-resolve/schema-for-data-path root-schema %)
                              (config-refs text))]
      (should= [] unresolved)))

  (it "every `isaac <command>` invocation names a registered top-level CLI command"
    (let [text    (chapter-text)
          known   (known-cli-commands (discovery/builtin-index))
          unknown (remove known (cli-commands-mentioned text))]
      (should= [] unknown))))
